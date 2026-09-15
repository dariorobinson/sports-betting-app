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
import com.kalshi.betting.util.ImpliedProbability;
import com.kalshi.betting.web.dto.*;
import com.kalshi.betting.ws.QuoteExecutionSignal;
import com.kalshi.betting.ws.dto.QuoteExecutedMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    /** How many times {@link #placeComboBet} re-prices and retries a candidate (fresh RFQ each time)
     *  before giving up on it — user-specified: 3. Covers thin/fleeting market-maker liquidity (a
     *  degenerate quote or a transient accept/confirm failure) without immediately abandoning an
     *  otherwise-good candidate for a different one. */
    private static final int PLACE_ATTEMPTS = 3;

    // ---- Pre-priced candidate shortlist (buildPricedCandidateShortlist) ----
    /** When a collection has too many legs to resolve at once, how many of its series to resolve
     *  (tennis first, then other leagues). Kept wide so the favorite pool spans many sports — that's
     *  what makes enough leg-DISJOINT combos available to place several bets per cycle (a small pool
     *  of only strong tennis favorites yields just 1-2 disjoint 3-leg combos). Bounds Kalshi calls. */
    private static final int SHORTLIST_MAX_SERIES_PER_COLLECTION = 20;
    /** Top-N strongest per-event favorites kept per collection before forming combinations. Sized so
     *  there's material for several leg-disjoint combos (each combo of strong favorites eats 3+ legs),
     *  while keeping the subset enumeration bounded (2^N masks — keep N ≤ ~16). */
    private static final int SHORTLIST_FAVORITES_PER_COLLECTION = 16;
    /** Cap on candidate leg-sets considered (per collection) after the probability filter. */
    private static final int SHORTLIST_MAX_LEGSETS = 20;
    /** Hard ceiling on how many candidates get RFQ-priced across the whole shortlist build — each
     *  pricing call is a real (money-free) RFQ round-trip, so this bounds latency and Kalshi load. */
    private static final int SHORTLIST_MAX_PRICING_ATTEMPTS = 8;
    /** Stop pricing candidates in a collection after this many consecutive failures — if its legs are
     *  being rejected (e.g. it structurally can't form the combos we generate), don't burn the budget. */
    private static final int SHORTLIST_MAX_CONSECUTIVE_FAILURES = 2;
    /** Sanity floor on a combo's combined probability — reject anything the market maker prices below
     *  this (a >~2.5x payout on 70%+ legs would be an illiquid/degenerate quote, not a real edge).
     *  Keeps results in the intended band: a strong-favorite combo that pays well, not a lottery ticket. */
    private static final BigDecimal SHORTLIST_MIN_COMBO_PROBABILITY = new BigDecimal("0.40");
    /** Buffer subtracted from the payout-implied probability ceiling when GENERATING candidates, so the
     *  real RFQ quote (which can come back a bit worse than the product-of-legs estimate) still tends
     *  to clear the payout floor. E.g. floor 1.6x → keep quotes ≤ 0.625; generate candidates ≤ 0.615. */
    private static final BigDecimal SHORTLIST_CANDIDATE_PROB_BUFFER = new BigDecimal("0.01");
    /** Time zone used to decide what "today" means for the same-day leg filter. */
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    /** Month abbreviations as they appear in Kalshi event tickers (e.g. the SEP in "...-26SEP15..."). */
    private static final List<String> MONTH_ABBREVS =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    private final KalshiApiClient client;
    private final ActiveComboLegTracker activeComboLegTracker;
    private final QuoteExecutionSignal quoteExecutionSignal;

    public ComboService(KalshiApiClient client, ActiveComboLegTracker activeComboLegTracker,
                          QuoteExecutionSignal quoteExecutionSignal) {
        this.client = client;
        this.activeComboLegTracker = activeComboLegTracker;
        this.quoteExecutionSignal = quoteExecutionSignal;
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
     * Deterministically (no model) builds a bounded shortlist of already-RFQ-priced combo candidates
     * for the autonomous scheduler to inject into its prompt — replacing the model's expensive
     * survey + per-candidate pricing round-trips. Strategy: enumerate sports combo collections, take
     * each event's strongest YES favorite whose implied probability clears {@code minLegProbPercent},
     * then stack as MANY of those favorites (2..{@code maxLegs}) as it takes for the combined
     * probability to fall to/under the payout ceiling implied by {@code minPayoutMultiple} — i.e. the
     * combo pays at least that multiple — and RFQ-price the most promising via the existing
     * {@link #priceCombo} path (no money risk — RFQs are deleted). Only real, quoted combos that
     * actually clear the payout floor are returned, safest (fewest legs / highest probability among
     * those that still hit the multiple) first. Pricing is hard-capped at
     * {@link #SHORTLIST_MAX_PRICING_ATTEMPTS}.
     *
     * @param minPayoutMultiple minimum payout multiple a combo must reach (e.g. 1.6 → combined
     *                          probability must be ≤ 1/1.6 = 0.625)
     * @param maxLegs          most legs a combo may have (add legs until the payout floor is met)
     * @param maxCandidates    soft target for how many priced candidates to try to return (also caps
     *                         pricing attempts together with {@link #SHORTLIST_MAX_PRICING_ATTEMPTS})
     * @param maxCollections   how many collections to survey
     */
    public List<PricedComboCandidate> buildPricedCandidateShortlist(
            int minLegProbPercent, BigDecimal minPayoutMultiple, int maxLegs,
            int maxCandidates, int maxCollections, Set<String> excludeEventTickers) {
        BigDecimal minLeg = BigDecimal.valueOf(minLegProbPercent).movePointLeft(2);
        // Payout ≈ 1/probability, so a min payout multiple is a MAX combined probability.
        BigDecimal maxCombo = BigDecimal.ONE.divide(minPayoutMultiple, 4, RoundingMode.HALF_UP);
        BigDecimal candidateCeiling = maxCombo.subtract(SHORTLIST_CANDIDATE_PROB_BUFFER);
        int pricingBudget = Math.min(Math.max(maxCandidates, 1), SHORTLIST_MAX_PRICING_ATTEMPTS);
        // Everything is keyed by underlying GAME (the ticker suffix), not the exact market: that's how
        // we guarantee "no overlap" (a game moneyline, its total, and a player prop on it all share one
        // game key) both against the portfolio and across the combos we build this cycle.
        Set<String> excludeGameKeys = (excludeEventTickers == null ? Set.<String>of() : excludeEventTickers)
                .stream().map(ComboService::gameKey).collect(Collectors.toSet());
        Set<String> sportsSeries = sportsSeriesTickers();
        LocalDate today = LocalDate.now(ZONE);

        List<ComboCollectionSummary> collections = listSportsCombos().stream()
                .limit(Math.max(maxCollections, 1))
                .toList();
        log.info("Shortlist build: surveying {} collection(s), minLeg={}%, minPayout={}x (combined ≤ {}), "
                + "maxLegs={}, budget={}, same-day={}, excluding {} committed game(s)",
                collections.size(), minLegProbPercent, minPayoutMultiple.toPlainString(),
                maxCombo.toPlainString(), maxLegs, pricingBudget, today, excludeGameKeys.size());

        // Phase 1: gather qualifying candidate leg-sets across all collections. Favorites are one per
        // GAME, played TODAY, not already committed — across any sports market type (moneyline, spread,
        // total, player/game props), whichever is the strongest qualifying favorite for that game.
        List<CandidateLegSet> candidates = new ArrayList<>();
        for (ComboCollectionSummary collection : collections) {
            List<FavoriteLeg> favorites = strongestFavoritesInCollection(
                    collection.collectionTicker(), minLeg, sportsSeries, today, excludeGameKeys);
            for (List<FavoriteLeg> legSet :
                    candidateLegSets(favorites, SHORTLIST_MIN_COMBO_PROBABILITY, candidateCeiling, maxLegs)) {
                candidates.add(new CandidateLegSet(collection.collectionTicker(), legSet, legSetProduct(legSet)));
            }
        }

        // Phase 2: dedupe by GAME-key set (the same combo often appears under several collection
        // tickers) — keep the highest-probability instance of each distinct set of games.
        Map<Set<String>, CandidateLegSet> byGames = new LinkedHashMap<>();
        for (CandidateLegSet c : candidates) {
            byGames.merge(c.gameKeys(), c, (a, b) -> a.product().compareTo(b.product()) >= 0 ? a : b);
        }
        List<CandidateLegSet> deduped = new ArrayList<>(byGames.values());

        // Phase 3: fewest legs first, then highest probability (safest that still pays the multiple).
        deduped.sort(Comparator.comparingInt((CandidateLegSet c) -> c.legs().size())
                .thenComparing(Comparator.comparing(CandidateLegSet::product).reversed()));

        // Phase 4: greedily select GAME-DISJOINT candidates so no two combos we place this cycle touch
        // the same underlying game (no correlated/overlapping exposure).
        List<CandidateLegSet> selected = new ArrayList<>();
        Set<String> usedGames = new HashSet<>();
        for (CandidateLegSet c : deduped) {
            if (selected.size() >= pricingBudget) {
                break;
            }
            if (Collections.disjoint(usedGames, c.gameKeys())) {
                selected.add(c);
                usedGames.addAll(c.gameKeys());
            }
        }
        log.info("Shortlist build: {} distinct candidate combo(s) after dedupe; selected {} game-disjoint "
                + "to price", deduped.size(), selected.size());

        // Phase 5: RFQ-price the selected candidates; keep quotes that actually pay the multiple.
        List<PricedComboCandidate> out = new ArrayList<>();
        int consecutiveFailures = 0;
        for (CandidateLegSet c : selected) {
            List<LegSelection> selections = c.legs().stream()
                    .map(f -> new LegSelection(f.eventTicker(), f.marketTicker(), f.side()))
                    .toList();
            ComboPriceResponse priced;
            try {
                priced = priceCombo(c.collectionTicker(), selections);
            } catch (RuntimeException e) {
                log.warn("Shortlist: pricing candidate {} in {} failed: {}",
                        selections, c.collectionTicker(), e.getMessage());
                if (++consecutiveFailures >= SHORTLIST_MAX_CONSECUTIVE_FAILURES && out.isEmpty()) {
                    log.warn("Shortlist: {} consecutive pricing failures and nothing priced yet — stopping",
                            consecutiveFailures);
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;
            // Keep only real quotes that actually pay the required multiple (combined ≤ maxCombo) and
            // aren't an implausibly-cheap outlier (combined ≥ the sanity floor).
            BigDecimal comboProb = parseDollar(priced.yesAskDollars());
            boolean qualifies = priced.quoted() && comboProb != null
                    && comboProb.compareTo(maxCombo) <= 0
                    && comboProb.compareTo(SHORTLIST_MIN_COMBO_PROBABILITY) >= 0;
            if (qualifies) {
                out.add(toCandidate(c.collectionTicker(), c.legs(), priced));
            } else {
                // Log exactly why, so a persistent "0 qualified" is diagnosable instead of guessed at:
                // was it never quoted at all, or quoted but the REAL price came back worse than the
                // pre-priced product-of-legs estimate (a real market-maker margin/spread the estimate
                // doesn't account for)?
                log.info("Shortlist: candidate {} in {} DID NOT qualify — estimatedProduct={}, quoted={}, "
                                + "realYesAskDollars={}, realImpliedProb={} (need ≤ {} and ≥ {})",
                        selections, c.collectionTicker(), c.product().toPlainString(), priced.quoted(),
                        priced.yesAskDollars(), comboProb, maxCombo.toPlainString(),
                        SHORTLIST_MIN_COMBO_PROBABILITY.toPlainString());
            }
        }

        // Present highest-probability-first among the qualifiers — every candidate already pays at
        // least the required multiple, so the model should see the SAFEST-that-still-pays first.
        out.sort(Comparator.comparing(
                (PricedComboCandidate c) -> parseDollarOrZero(c.yesAskDollars())).reversed());
        log.info("Shortlist build: {} priced candidate(s) reached the {}x payout floor after {} pricing "
                + "attempt(s)", out.size(), minPayoutMultiple.toPlainString(), selected.size());
        return out;
    }

    /** A generated (not-yet-priced) candidate: which collection, its favorite legs, and the product of
     *  their leg probabilities (the pre-pricing combined-probability estimate). */
    private record CandidateLegSet(String collectionTicker, List<FavoriteLeg> legs, BigDecimal product) {
        /** Underlying games this combo touches — used for dedupe and no-overlap selection. */
        Set<String> gameKeys() {
            return legs.stream().map(f -> gameKey(f.eventTicker())).collect(Collectors.toSet());
        }
    }

    /** One collection's strongest favorite per underlying GAME that (a) is played today, (b) isn't an
     *  already-committed game, and (c) clears the leg floor. "One per game" across all sports market
     *  types means a combo built from these can never double up on the same game. Capped to
     *  {@link #SHORTLIST_FAVORITES_PER_COLLECTION}, strongest first. */
    private List<FavoriteLeg> strongestFavoritesInCollection(String collectionTicker, BigDecimal minLeg,
                                                             Set<String> sportsSeries, LocalDate today,
                                                             Set<String> excludeGameKeys) {
        Map<String, FavoriteLeg> byGame = new LinkedHashMap<>();
        for (ComboLegEvent leg : resolveLegsForShortlist(collectionTicker, sportsSeries)) {
            String key = gameKey(leg.eventTicker());
            if (excludeGameKeys.contains(key)) {
                continue;
            }
            LocalDate gameDate = gameDate(leg.eventTicker());
            if (gameDate == null || !gameDate.equals(today)) {
                continue; // same-day only — never a future (or unparseable) game
            }
            FavoriteLeg fav = strongestFavorite(leg, minLeg);
            if (fav == null) {
                continue;
            }
            FavoriteLeg current = byGame.get(key);
            if (current == null || fav.prob().compareTo(current.prob()) > 0) {
                byGame.put(key, fav); // keep this game's strongest market (moneyline, total, prop, ...)
            }
        }
        return byGame.values().stream()
                .sorted(Comparator.comparing(FavoriteLeg::prob).reversed())
                .limit(SHORTLIST_FAVORITES_PER_COLLECTION)
                .toList();
    }

    /** The underlying game a market/event belongs to: the ticker's suffix after the series prefix,
     *  which is identical across every market type for the same game (e.g. KXNFLGAME-26SEP17DETBUF,
     *  KXNFLTOTAL-26SEP17DETBUF and a player prop all yield "26SEP17DETBUF"). */
    static String gameKey(String eventTicker) {
        if (eventTicker == null) {
            return "";
        }
        int dash = eventTicker.indexOf('-');
        return dash < 0 ? eventTicker : eventTicker.substring(dash + 1);
    }

    /** The date a game is played, parsed from the ticker suffix (YY MON DD, e.g. "26SEP15..." →
     *  2026-09-15). Null if the suffix isn't a datable game (e.g. a synthetic combo-market ticker). */
    static LocalDate gameDate(String eventTicker) {
        String key = gameKey(eventTicker);
        if (key.length() < 7) {
            return null;
        }
        try {
            int year = 2000 + Integer.parseInt(key.substring(0, 2));
            int month = MONTH_ABBREVS.indexOf(key.substring(2, 5).toUpperCase(java.util.Locale.ROOT)) + 1;
            if (month == 0) {
                return null;
            }
            int day = Integer.parseInt(key.substring(5, 7));
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tickers of all series Kalshi files under the "Sports" category — includes every sports market
     *  type (game/spread/total, player & game props) and excludes non-sports (crypto, indices). */
    private Set<String> sportsSeriesTickers() {
        return client.listSeries(SPORTS_CATEGORY).series().stream()
                .map(s -> s.ticker())
                .collect(Collectors.toSet());
    }

    /** Resolves a collection's legs across ALL sports market types (moneyline, spread, total, player &
     *  game props) — anything filed under Kalshi's Sports category — while excluding non-sports series
     *  (crypto, indices). Series are prioritized tennis → other moneyline (GAME/MATCH) → props, so the
     *  broadest, strongest-favorite markets are resolved first within the series budget. If the
     *  collection is small enough to resolve whole, its legs are filtered to sports series directly. */
    private List<ComboLegEvent> resolveLegsForShortlist(String collectionTicker, Set<String> sportsSeries) {
        ComboLegsResponse resp;
        try {
            resp = getComboLegs(collectionTicker, null);
        } catch (RuntimeException e) {
            log.warn("Shortlist: resolving legs for {} failed: {}", collectionTicker, e.getMessage());
            return List.of();
        }
        List<ComboLegEvent> resolved;
        if (resp.legs() != null) {
            resolved = resp.legs();
        } else if (resp.legCountsBySeries() == null) {
            return List.of();
        } else {
            List<String> series = resp.legCountsBySeries().keySet().stream()
                    .filter(sportsSeries::contains)
                    .sorted(Comparator.comparingInt(ComboService::seriesPriority))
                    .limit(SHORTLIST_MAX_SERIES_PER_COLLECTION)
                    .toList();
            List<ComboLegEvent> all = new ArrayList<>();
            for (String seriesTicker : series) {
                try {
                    ComboLegsResponse r = getComboLegs(collectionTicker, seriesTicker);
                    if (r.legs() != null) {
                        all.addAll(r.legs());
                    }
                } catch (RuntimeException e) {
                    log.warn("Shortlist: resolving series {} in {} failed: {}", seriesTicker, collectionTicker,
                            e.getMessage());
                }
            }
            resolved = all;
        }
        // Keep only sports events (covers the small-collection path, which isn't series-filtered).
        return resolved.stream()
                .filter(l -> sportsSeries.contains(leadingSeriesTicker(l.eventTicker())))
                .toList();
    }

    /** Resolution priority: tennis matches first (deepest source of strong favorites), then other
     *  moneyline (GAME/MATCH) markets, then everything else (spreads/totals/props). Lower = sooner. */
    private static int seriesPriority(String seriesTicker) {
        if (isTennisSeries(seriesTicker)) {
            return 0;
        }
        String s = seriesTicker == null ? "" : seriesTicker.toUpperCase();
        return (s.endsWith("GAME") || s.endsWith("MATCH")) ? 1 : 2;
    }

    private static boolean isTennisSeries(String seriesTicker) {
        String s = seriesTicker == null ? "" : seriesTicker.toUpperCase();
        return s.contains("ATP") || s.contains("WTA") || s.contains("TENNIS");
    }

    /** A market priced at/above this is treated as unusable as a leg — either a settled/all-but-decided
     *  outcome (a finished game shows ~$1.00, and including a settled event makes the whole combo
     *  invalid on Kalshi), OR a near-lock so strong it can't help reach the payout floor: a 0.95 leg
     *  barely moves the combined probability, so no realistic number of them ever gets a combo down to
     *  the ~0.625 needed for 1.6x. Legs must be strong favorites but not near-certainties: [0.70, 0.90). */
    private static final BigDecimal DEGENERATE_PRICE_CEILING = new BigDecimal("0.90");

    /** The single strongest YES outcome across an event's ACTIVE markets (the favorite), or null if
     *  none clears the leg floor. Skips non-active markets: a finished/settled game still comes back
     *  from the events endpoint with a degenerate ask (e.g. $1.00) and status != "active", and
     *  including a settled event in a combo makes Kalshi reject the whole thing (invalid_parameters).
     *  YES-only keeps leg labels clean (the favored team's name). */
    private static FavoriteLeg strongestFavorite(ComboLegEvent leg, BigDecimal minLeg) {
        if (leg.game() == null || leg.game().markets() == null) {
            return null;
        }
        FavoriteLeg best = null;
        for (MarketSummary market : leg.game().markets()) {
            if (!"active".equalsIgnoreCase(market.status())) {
                continue;
            }
            BigDecimal yesProb = parseDollar(market.yesAskDollars());
            if (yesProb == null || yesProb.compareTo(DEGENERATE_PRICE_CEILING) >= 0) {
                continue;
            }
            if (best == null || yesProb.compareTo(best.prob()) > 0) {
                best = new FavoriteLeg(leg.eventTicker(), market.ticker(), "YES",
                        market.yesLabel(), yesProb);
            }
        }
        return (best != null && best.prob().compareTo(minLeg) >= 0) ? best : null;
    }

    /** All combinations of the given favorites, of size 2..{@code maxLegs}, whose product-of-leg-
     *  probabilities lands in [{@code minCombo}, {@code maxCombo}] — i.e. low enough to pay the required
     *  multiple, but not implausibly low. Ordered so the FEWEST-leg, highest-probability qualifying
     *  combos come first: that's "add just enough legs to hit the multiple," which keeps the combo as
     *  safe as possible while still paying out. Capped to {@link #SHORTLIST_MAX_LEGSETS}. Each favorite
     *  is from a distinct event, so combinations never double-pick the same game. */
    private static List<List<FavoriteLeg>> candidateLegSets(List<FavoriteLeg> favs, BigDecimal minCombo,
                                                            BigDecimal maxCombo, int maxLegs) {
        List<List<FavoriteLeg>> sets = new ArrayList<>();
        int n = favs.size();
        int cap = Math.min(maxLegs, n);
        // Enumerate subsets via bitmask (favs is bounded to SHORTLIST_FAVORITES_PER_COLLECTION, so this
        // is at most a few hundred masks). Keep those with 2..cap legs whose product is in range.
        for (int mask = 1; mask < (1 << n); mask++) {
            int size = Integer.bitCount(mask);
            if (size < 2 || size > cap) {
                continue;
            }
            BigDecimal product = BigDecimal.ONE;
            List<FavoriteLeg> legs = new ArrayList<>(size);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    FavoriteLeg f = favs.get(i);
                    legs.add(f);
                    product = product.multiply(f.prob());
                }
            }
            if (product.compareTo(minCombo) >= 0 && product.compareTo(maxCombo) <= 0) {
                sets.add(legs);
            }
        }
        // Fewest legs first, then highest probability (closest to the ceiling) — the safest combo that
        // still pays the multiple, with the least stacking.
        sets.sort(Comparator.comparingInt((List<FavoriteLeg> s) -> s.size())
                .thenComparing(Comparator.comparing(ComboService::legSetProduct).reversed()));
        return sets.stream().limit(SHORTLIST_MAX_LEGSETS).toList();
    }

    private static BigDecimal legSetProduct(List<FavoriteLeg> legSet) {
        BigDecimal p = BigDecimal.ONE;
        for (FavoriteLeg f : legSet) {
            p = p.multiply(f.prob());
        }
        return p;
    }

    private static PricedComboCandidate toCandidate(String collectionTicker, List<FavoriteLeg> legSet,
                                                     ComboPriceResponse priced) {
        List<CandidateLeg> legs = legSet.stream()
                .map(f -> new CandidateLeg(f.eventTicker(), f.marketTicker(), f.side(), f.label(),
                        ImpliedProbability.fromDollarPrice(f.prob().toPlainString())))
                .toList();
        return new PricedComboCandidate(collectionTicker, legs,
                priced.yesAskImpliedProbability(), priced.impliedYesPayoutMultiple(), priced.yesAskDollars());
    }

    private static BigDecimal parseDollar(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDollarOrZero(String s) {
        BigDecimal v = parseDollar(s);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** A single event's favored YES outcome, with its market-implied probability. */
    private record FavoriteLeg(String eventTicker, String marketTicker, String side, String label,
                               BigDecimal prob) {
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

        // Market-maker liquidity for a combo can be thin/fleeting (especially props and lower-tier
        // tennis) — the full-size quote can come back degenerate or the accept/confirm step can fail
        // transiently even though the combo was healthy moments ago at indicative size. Re-price and
        // retry a bounded number of times before giving up on this candidate entirely: a fresh RFQ is
        // cheap (no money moves until accept/confirm succeeds), and this recovers from exactly the kind
        // of transient staleness that caused an otherwise-good candidate to silently not_fill.
        ComboBetResult lastFailure = ComboBetResult.notFilled(eventTicker, marketTicker,
                "No placement attempt completed.");
        for (int attempt = 1; attempt <= PLACE_ATTEMPTS; attempt++) {
            RfqAttempt real = requestQuote(marketTicker, desiredContracts);
            if (real.quote().isEmpty()) {
                cleanupRfq(real.rfqId());
                lastFailure = ComboBetResult.notFilled(eventTicker, marketTicker,
                        "Quoted at 1 contract but no market maker quoted the full requested size ("
                                + desiredContracts + ").");
                log.warn("Placement attempt {}/{} for {}: no quote at full size — will retry.",
                        attempt, PLACE_ATTEMPTS, marketTicker);
                continue;
            }
            Quote quote = real.quote().get();
            BigDecimal actualPrice = yesAskPrice(quote);
            log.info("Real quote for {} (attempt {}/{}): id={}, yesBidDollars={}, noBidDollars={}, "
                            + "contractsFp={}, status={}", marketTicker, attempt, PLACE_ATTEMPTS, quote.id(),
                    quote.yesBidDollars(), quote.noBidDollars(), quote.contractsFp(), quote.status());
            // A null/degenerate price ($0 or $1, i.e. no real liquidity on the side we're about to
            // accept) can legitimately get rejected by Kalshi at accept-time as invalid_parameters, and
            // would otherwise NPE on the multiply below — reprice and retry rather than give up.
            if (actualPrice == null || actualPrice.signum() <= 0 || actualPrice.compareTo(BigDecimal.ONE) >= 0) {
                cleanupRfq(real.rfqId());
                lastFailure = ComboBetResult.notFilled(eventTicker, marketTicker,
                        "Real quote price was invalid or degenerate ($" + actualPrice
                                + ") — no real liquidity to accept.");
                log.warn("Placement attempt {}/{} for {}: degenerate quote (${}) — will retry.",
                        attempt, PLACE_ATTEMPTS, marketTicker, actualPrice);
                continue;
            }
            BigDecimal actualContracts = new BigDecimal(quote.contractsFp());
            BigDecimal actualCost = actualPrice.multiply(actualContracts);
            BigDecimal maxAcceptableCost = targetDollars.multiply(BUDGET_TOLERANCE);
            if (actualCost.compareTo(maxAcceptableCost) > 0) {
                // A deliberate business decision, not a transient technical failure — repricing is
                // unlikely to fix a structurally-too-large minimum lot size, so don't burn retries here.
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
                log.warn("accept/confirm threw for quote {} (RFQ {}) on combo market {} (attempt {}/{}) — "
                                + "checking real state before concluding anything failed: {}",
                        quote.id(), quote.rfqId(), marketTicker, attempt, PLACE_ATTEMPTS, e.getMessage());
                acceptOrConfirmThrew = true;
            }

            Quote finalQuote = pollForExecution(quote.rfqId(), quote.id());
            if ("executed".equalsIgnoreCase(finalQuote.status())) {
                activeComboLegTracker.record(marketTicker, legs);
                return ComboBetResult.executed(eventTicker, marketTicker, actualContracts.toPlainString(),
                        actualPrice.toPlainString(), actualCost.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }

            if (acceptOrConfirmThrew && !"accepted".equalsIgnoreCase(finalQuote.status())
                    && !"confirmed".equalsIgnoreCase(finalQuote.status())) {
                // Genuinely never got anywhere this attempt — still "open" (or similar), nothing to
                // clean up. Transient (e.g. the same staleness that produces a degenerate quote) — retry.
                log.warn("Placement attempt {}/{} for {}: accept/confirm failed and no order resulted "
                        + "(status={}) — will retry.", attempt, PLACE_ATTEMPTS, marketTicker, finalQuote.status());
                lastFailure = ComboBetResult.notFilled(eventTicker, marketTicker,
                        "Accept/confirm failed and no order resulted (status: " + finalQuote.status() + ").");
                continue;
            }

            // Accepted/confirmed (successfully or ambiguously) but not executed within the poll window —
            // a real order may be resting. Clean it up rather than leave it pending indefinitely. This is
            // terminal, not retried: a real order was just created and cancelled on Kalshi's side, and
            // retrying immediately risks stacking confusing resting orders on the same market.
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

        log.warn("Combo bet on {} did not fill after {} reprice attempt(s) — giving up on this candidate.",
                marketTicker, PLACE_ATTEMPTS);
        return lastFailure;
    }

    /** Polls a confirmed quote until it reports "executed" or the window runs out — confirming only
     *  starts execution, it doesn't guarantee it completes promptly (or at all). Each wait races the
     *  REST poll cadence against {@link QuoteExecutionSignal}'s realtime WebSocket-driven signal: if
     *  the WS event arrives first, this wakes immediately instead of waiting out the full interval —
     *  but a WS event is never trusted directly for a money-moving decision, it only triggers an
     *  early, authoritative re-check via REST. If the WS never fires (disabled, disconnected, or
     *  simply slower), this degrades exactly to the original flat-sleep polling behavior. */
    private Quote pollForExecution(String rfqId, String quoteId) {
        try {
            Quote latest = null;
            for (int attempt = 0; attempt < EXECUTION_POLL_ATTEMPTS; attempt++) {
                latest = client.getQuote(rfqId, quoteId).quote();
                if ("executed".equalsIgnoreCase(latest.status())) {
                    return latest;
                }
                if (attempt < EXECUTION_POLL_ATTEMPTS - 1) {
                    CompletableFuture<QuoteExecutedMsg> wsSignal = quoteExecutionSignal.awaitExecution(rfqId, quoteId);
                    try {
                        wsSignal.get(EXECUTION_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                        // WS fired early — confirm via REST before trusting it (the WS payload only
                        // tells us WHEN to re-check sooner, never WHAT the final state is).
                        return client.getQuote(rfqId, quoteId).quote();
                    } catch (TimeoutException e) {
                        // no WS signal within this interval — fall through to the next REST poll,
                        // identical cadence to the original behavior.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return latest;
                    } catch (ExecutionException e) {
                        // QuoteExecutionSignal only ever completes its futures normally; unreachable
                        // in practice, but fall through to the next REST poll defensively.
                    }
                }
            }
            return latest;
        } finally {
            quoteExecutionSignal.cancel(rfqId, quoteId);
        }
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
