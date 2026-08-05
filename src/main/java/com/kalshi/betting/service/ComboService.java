package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.AssociatedEvent;
import com.kalshi.betting.client.dto.CreateMarketInMultivariateEventCollectionRequest;
import com.kalshi.betting.client.dto.CreateMarketInMultivariateEventCollectionResponse;
import com.kalshi.betting.client.dto.CreateRFQRequest;
import com.kalshi.betting.client.dto.EventData;
import com.kalshi.betting.client.dto.MultivariateEventCollection;
import com.kalshi.betting.client.dto.Quote;
import com.kalshi.betting.client.dto.TickerPair;
import com.kalshi.betting.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Browses Kalshi's "combo" markets (multivariate event collections) and prices specific
 * leg-combinations. This only handles the mechanical side — enumerating collections/legs and
 * asking Kalshi to materialize a real price for a chosen combination. Estimating each leg's win
 * probability from stats and deciding which combinations are worth pricing is judgment work that
 * happens one layer up (e.g. via the sports-stats-analyst agent), not here.
 * <p>
 * Combo collections can have hundreds of legs (a single collection can span every game across
 * several leagues for the week), so this never resolves legs one-at-a-time: batch lookups via
 * {@link KalshiApiClient#listEventsByTickers} and, above a size threshold, a series breakdown
 * instead of a full dump — see {@link #getComboLegs}.
 */
@Service
public class ComboService {

    private static final Logger log = LoggerFactory.getLogger(ComboService.class);

    private static final String SPORTS_CATEGORY = "Sports";
    private static final int LEG_RESOLUTION_LIMIT = 60;
    private static final int BATCH_SIZE = 50;
    /** How many legs to sample when just checking whether a collection has any sports legs at all. */
    private static final int SPORTS_CHECK_SAMPLE_SIZE = 25;
    /** Combo markets have no resting order book — a real price needs a market maker to respond to
     *  an RFQ, which isn't instant. Poll briefly rather than block indefinitely. */
    private static final int RFQ_POLL_ATTEMPTS = 6;
    private static final long RFQ_POLL_INTERVAL_MILLIS = 1000;
    private static final int RFQ_QUOTE_REQUEST_CONTRACTS = 1;
    /** How much over the target dollar budget a real quote is allowed to cost before we decline it
     *  rather than risk overspending — quoters can offer more/less size than requested. */
    private static final BigDecimal BUDGET_TOLERANCE = new BigDecimal("1.25");
    /** Confirming a quote only "starts a timer for order execution" (per Kalshi's docs) — it does
     *  NOT mean the trade filled. Poll for actual execution rather than trust confirm's 204 alone;
     *  if it hasn't filled by the end of this window, cancel the resulting order instead of leaving
     *  it resting indefinitely (this is exactly the "stuck pending order" failure mode to avoid). */
    private static final int EXECUTION_POLL_ATTEMPTS = 10;
    private static final long EXECUTION_POLL_INTERVAL_MILLIS = 1500;

    private final KalshiApiClient client;

    public ComboService(KalshiApiClient client) {
        this.client = client;
    }

    /**
     * Open combo collections with at least one sports leg. Kalshi files combo *templates* under
     * their own "Exotics" category regardless of what they combine — e.g. "MVE Sport Multi Game"
     * and "MVE Cross Category" are both filed as Exotics even though their legs are NFL/NCAA/NBA
     * games. So the collection's own category is useless here; instead we resolve a sample of
     * each collection's legs to their parent series and check *that* series' category.
     */
    public List<ComboCollectionSummary> listSportsCombos() {
        Set<String> sportsSeriesTickers = client.listSeries(SPORTS_CATEGORY).series().stream()
                .map(s -> s.ticker())
                .collect(Collectors.toSet());

        return client.listMultivariateCollections(null, "open").multivariateContracts().stream()
                .filter(c -> hasSportsLeg(c, sportsSeriesTickers))
                .map(ComboCollectionSummary::from)
                .toList();
    }

    private boolean hasSportsLeg(MultivariateEventCollection collection, Set<String> sportsSeriesTickers) {
        if (collection.associatedEvents() == null || collection.associatedEvents().isEmpty()) {
            return false;
        }
        List<String> sample = collection.associatedEvents().stream()
                .map(AssociatedEvent::ticker)
                .distinct()
                .limit(SPORTS_CHECK_SAMPLE_SIZE)
                .toList();
        try {
            return client.listEventsByTickers(sample, false).events().stream()
                    .anyMatch(e -> sportsSeriesTickers.contains(e.seriesTicker()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Legs available to pick from within a collection. If {@code seriesTickerFilter} is given,
     * only legs from that series are resolved (cheap even for huge collections). Otherwise, legs
     * are resolved in full only if there are few enough to be manageable ({@link #LEG_RESOLUTION_LIMIT});
     * above that, a per-series leg count is returned instead so the caller can filter.
     */
    public ComboLegsResponse getComboLegs(String collectionTicker, String seriesTickerFilter) {
        MultivariateEventCollection collection = client.getMultivariateCollection(collectionTicker)
                .multivariateContract();
        List<AssociatedEvent> allLegs = collection.associatedEvents() == null
                ? List.of() : collection.associatedEvents();

        if (seriesTickerFilter != null && !seriesTickerFilter.isBlank()) {
            List<AssociatedEvent> filtered = allLegs.stream()
                    .filter(leg -> leadingSeriesTicker(leg.ticker()).equalsIgnoreCase(seriesTickerFilter))
                    .toList();
            return ComboLegsResponse.resolved(collection, resolveLegsBatched(filtered), allLegs.size());
        }

        if (allLegs.size() > LEG_RESOLUTION_LIMIT) {
            Map<String, Long> legCountsBySeries = allLegs.stream()
                    .collect(Collectors.groupingBy(leg -> leadingSeriesTicker(leg.ticker()), Collectors.counting()));
            return ComboLegsResponse.tooManyLegs(collection, allLegs.size(), legCountsBySeries);
        }

        return ComboLegsResponse.resolved(collection, resolveLegsBatched(allLegs), allLegs.size());
    }

    private List<ComboLegEvent> resolveLegsBatched(List<AssociatedEvent> legs) {
        Map<String, AssociatedEvent> byTicker = legs.stream()
                .collect(Collectors.toMap(AssociatedEvent::ticker, leg -> leg, (a, b) -> a));
        List<String> tickers = new ArrayList<>(byTicker.keySet());

        List<ComboLegEvent> resolved = new ArrayList<>();
        for (int start = 0; start < tickers.size(); start += BATCH_SIZE) {
            List<String> batch = tickers.subList(start, Math.min(start + BATCH_SIZE, tickers.size()));
            if (batch.isEmpty()) {
                continue;
            }
            List<EventData> events = client.listEventsByTickers(batch, true).events();
            for (EventData event : events) {
                AssociatedEvent leg = byTicker.get(event.eventTicker());
                if (leg != null) {
                    resolved.add(new ComboLegEvent(leg.ticker(), leg.isYesOnly(), leg.sizeMin(), leg.sizeMax(),
                            GameSummary.from(event)));
                }
            }
        }
        return resolved;
    }

    private static String leadingSeriesTicker(String eventTicker) {
        int dashIndex = eventTicker.indexOf('-');
        return dashIndex < 0 ? eventTicker : eventTicker.substring(0, dashIndex);
    }

    /**
     * Materializes the real Kalshi market for one specific set of legs and asks a market maker to
     * quote it. Combo markets have no resting order book — {@code CreateMarketInMultivariateEventCollection}
     * alone (the old, now-deprecated approach) only creates the synthetic market shell and returns
     * degenerate placeholder pricing (yes ask $0.00 / no ask $1.00) since nobody has quoted it yet.
     * A real price requires Kalshi's RFQ (request-for-quote) flow: submit an RFQ referencing the
     * created market, briefly poll for a market maker's response, then clean up the RFQ either way.
     * Doesn't place an order or risk money — the RFQ is deleted before returning, quoted or not.
     */
    public ComboPriceResponse priceCombo(String collectionTicker, List<LegSelection> legs) {
        var createResponse = createComboMarket(collectionTicker, legs);
        String eventTicker = createResponse.eventTicker();
        String marketTicker = createResponse.marketTicker();

        RfqAttempt attempt = requestQuote(marketTicker, RFQ_QUOTE_REQUEST_CONTRACTS);
        try {
            return attempt.quote().map(q -> ComboPriceResponse.quoted(eventTicker, marketTicker, q))
                    .orElseGet(() -> ComboPriceResponse.unquoted(eventTicker, marketTicker));
        } finally {
            cleanupRfq(attempt.rfqId());
        }
    }

    /**
     * Actually places a real combo bet — buys YES on the combo's own synthetic market (the legs
     * already encode the desired outcome per leg, so YES on the combo market means "my whole
     * combination hits"). Unlike {@link #priceCombo}, this doesn't just check a price: it sizes an
     * RFQ to roughly {@code targetDollars} worth of contracts, and if a market maker quotes it
     * within {@link #BUDGET_TOLERANCE} of that budget, accepts and confirms the quote — real money,
     * no further confirmation step.
     * <p>
     * Two-phase: a throwaway 1-contract RFQ first to learn the current price (since it's needed to
     * convert {@code targetDollars} into a contract count), then a real RFQ for that computed size.
     */
    public ComboBetResult placeComboBet(String collectionTicker, List<LegSelection> legs, BigDecimal targetDollars) {
        var createResponse = createComboMarket(collectionTicker, legs);
        String eventTicker = createResponse.eventTicker();
        String marketTicker = createResponse.marketTicker();

        RfqAttempt indicative = requestQuote(marketTicker, RFQ_QUOTE_REQUEST_CONTRACTS);
        cleanupRfq(indicative.rfqId());
        if (indicative.quote().isEmpty()) {
            return ComboBetResult.notFilled(eventTicker, marketTicker,
                    "No market maker quoted this combo at all — can't size a bet without a real price.");
        }
        BigDecimal price = yesAskPrice(indicative.quote().get());
        if (price == null || price.signum() <= 0) {
            return ComboBetResult.notFilled(eventTicker, marketTicker, "Quoted price was invalid ($0 or less).");
        }
        int desiredContracts = targetDollars.divide(price, 0, RoundingMode.DOWN).intValue();
        if (desiredContracts < 1) {
            return ComboBetResult.notFilled(eventTicker, marketTicker,
                    "Target bet size ($" + targetDollars + ") can't buy even 1 contract at the quoted price ($"
                            + price + ").");
        }

        RfqAttempt real = requestQuote(marketTicker, desiredContracts);
        if (real.quote().isEmpty()) {
            cleanupRfq(real.rfqId());
            return ComboBetResult.notFilled(eventTicker, marketTicker,
                    "Quoted at 1 contract but no market maker quoted the full requested size ("
                            + desiredContracts + ").");
        }
        Quote quote = real.quote().get();
        BigDecimal actualPrice = yesAskPrice(quote);
        BigDecimal actualContracts = new BigDecimal(quote.contractsFp());
        BigDecimal actualCost = actualPrice.multiply(actualContracts);
        BigDecimal maxAcceptableCost = targetDollars.multiply(BUDGET_TOLERANCE);
        if (actualCost.compareTo(maxAcceptableCost) > 0) {
            cleanupRfq(real.rfqId());
            return ComboBetResult.declined(eventTicker, marketTicker,
                    "Quoted size/cost ($" + actualCost + ") exceeded the budget ($" + targetDollars
                            + " target, $" + maxAcceptableCost + " max) — declined rather than overspend.");
        }

        boolean acceptOrConfirmThrew = false;
        try {
            // "accepted_side" is which side of the QUOTER's two-sided quote we're matching against,
            // not which side we end up holding — matching their "no" bid means THEY buy no from us,
            // leaving US net long yes (confirmed empirically: real placed bets came back holding
            // "no" positions when this was "yes", the opposite of the intended pick every time).
            client.acceptQuote(quote.rfqId(), quote.id(), "no");
            client.confirmQuote(quote.rfqId(), quote.id());
        } catch (Exception e) {
            // A client-side exception here (timeout, dropped response, etc.) does NOT prove the
            // operation didn't take effect on Kalshi's side — accept/confirm may have gone through
            // even though we never saw a clean response. Never declare failure from this alone; the
            // real state is checked via getQuote below regardless of what happened here.
            log.warn("accept/confirm threw for quote {} (RFQ {}) on combo market {} — checking real "
                            + "state before concluding anything failed: {}",
                    quote.id(), quote.rfqId(), marketTicker, e.getMessage());
            acceptOrConfirmThrew = true;
        }

        Quote finalQuote = pollForExecution(quote.rfqId(), quote.id());
        if ("executed".equalsIgnoreCase(finalQuote.status())) {
            return ComboBetResult.executed(eventTicker, marketTicker, actualContracts.toPlainString(),
                    actualPrice.toPlainString(), actualCost.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        if (acceptOrConfirmThrew && !"accepted".equalsIgnoreCase(finalQuote.status())
                && !"confirmed".equalsIgnoreCase(finalQuote.status())) {
            // Genuinely never got anywhere — still "open" (or similar), nothing to clean up.
            log.error("Accept/confirm failed for quote {} (RFQ {}) on combo market {} and no order resulted "
                    + "(status={})", quote.id(), quote.rfqId(), marketTicker, finalQuote.status());
            return ComboBetResult.notFilled(eventTicker, marketTicker,
                    "Accept/confirm failed and no order resulted (status: " + finalQuote.status() + ").");
        }

        // Accepted/confirmed (successfully or ambiguously) but not executed within the poll window —
        // a real order may be resting. Clean it up rather than leave it pending indefinitely.
        log.warn("Combo bet on {} reached status={} but did not execute within the poll window — "
                        + "cancelling the resulting order instead of leaving it resting.",
                marketTicker, finalQuote.status());
        cancelStalledOrder(finalQuote, marketTicker);
        return ComboBetResult.stalledCancelled(eventTicker, marketTicker,
                "Quote reached status \"" + finalQuote.status() + "\" but never actually filled within "
                        + (EXECUTION_POLL_ATTEMPTS * EXECUTION_POLL_INTERVAL_MILLIS / 1000)
                        + "s — cancelled the resulting order rather than leave it resting indefinitely. "
                        + "No position should remain open.");
    }

    /** Polls a confirmed quote until it reports "executed" or the window runs out — confirming only
     *  starts execution, it doesn't guarantee it completes promptly (or at all). */
    private Quote pollForExecution(String rfqId, String quoteId) {
        Quote latest = null;
        for (int attempt = 0; attempt < EXECUTION_POLL_ATTEMPTS; attempt++) {
            latest = client.getQuote(rfqId, quoteId).quote();
            if ("executed".equalsIgnoreCase(latest.status())) {
                return latest;
            }
            if (attempt < EXECUTION_POLL_ATTEMPTS - 1) {
                try {
                    Thread.sleep(EXECUTION_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return latest;
    }

    /** Best-effort: cancels the resting order left behind by a confirmed-but-unfilled quote, using
     *  the same cancel path as CancelBetTool. If this fails, it's logged loudly — a real order may
     *  still be resting and need manual cleanup via ListMyOrdersTool/CancelBetTool. */
    private void cancelStalledOrder(Quote finalQuote, String marketTicker) {
        String orderId = finalQuote.rfqCreatorOrderId();
        if (orderId == null || orderId.isBlank()) {
            log.error("Combo bet on {} stalled with no rfqCreatorOrderId to cancel — check "
                    + "ListMyOrdersTool/CancelBetTool manually for a stuck resting order.", marketTicker);
            return;
        }
        try {
            client.cancelOrder(orderId, marketTicker);
        } catch (Exception e) {
            log.error("Failed to cancel stalled order {} on combo market {} — it may still be resting, "
                    + "check ListMyOrdersTool/CancelBetTool manually.", orderId, marketTicker, e);
        }
    }

    private CreateMarketInMultivariateEventCollectionResponse createComboMarket(
            String collectionTicker, List<LegSelection> legs) {
        List<TickerPair> selectedMarkets = legs.stream()
                .map(leg -> new TickerPair(leg.marketTicker(), leg.eventTicker(), leg.side().toLowerCase()))
                .toList();
        var createRequest = new CreateMarketInMultivariateEventCollectionRequest(selectedMarkets, true);
        return client.createComboMarket(collectionTicker, createRequest);
    }

    private record RfqAttempt(String rfqId, Optional<Quote> quote) {
    }

    private RfqAttempt requestQuote(String marketTicker, int contracts) {
        String rfqId = client.createRfq(new CreateRFQRequest(marketTicker, contracts, false)).id();
        return new RfqAttempt(rfqId, pollForQuote(rfqId));
    }

    private void cleanupRfq(String rfqId) {
        try {
            client.deleteRfq(rfqId);
        } catch (Exception e) {
            log.warn("Failed to clean up RFQ {}: {}", rfqId, e.getMessage());
        }
    }

    private Optional<Quote> pollForQuote(String rfqId) {
        for (int attempt = 0; attempt < RFQ_POLL_ATTEMPTS; attempt++) {
            List<Quote> quotes = client.getQuotesForRfq(rfqId).quotes();
            if (quotes != null && !quotes.isEmpty()) {
                return Optional.of(quotes.get(0));
            }
            if (attempt < RFQ_POLL_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RFQ_POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /** yes/no on a Quote are the price the quoter is BIDDING to buy that side, so our ask to BUY
     *  yes is (1 - noBidDollars) — Kalshi's yes+no prices always sum to $1. */
    private static BigDecimal yesAskPrice(Quote quote) {
        if (quote.noBidDollars() == null) {
            return null;
        }
        return BigDecimal.ONE.subtract(new BigDecimal(quote.noBidDollars()));
    }
}
