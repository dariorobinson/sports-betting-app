package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.AssociatedEvent;
import com.kalshi.betting.client.dto.CreateMarketInMultivariateEventCollectionRequest;
import com.kalshi.betting.client.dto.EventData;
import com.kalshi.betting.client.dto.MultivariateEventCollection;
import com.kalshi.betting.client.dto.TickerPair;
import com.kalshi.betting.web.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final String SPORTS_CATEGORY = "Sports";
    private static final int LEG_RESOLUTION_LIMIT = 60;
    private static final int BATCH_SIZE = 50;
    /** How many legs to sample when just checking whether a collection has any sports legs at all. */
    private static final int SPORTS_CHECK_SAMPLE_SIZE = 25;

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
     * Materializes the real Kalshi market for one specific set of legs and returns its actual
     * price. Doesn't place an order — just creates/looks up the listing (rate-limited by Kalshi
     * to 5,000 creations/week per account).
     */
    public ComboPriceResponse priceCombo(String collectionTicker, List<LegSelection> legs) {
        List<TickerPair> selectedMarkets = legs.stream()
                .map(leg -> new TickerPair(leg.marketTicker(), leg.eventTicker(), leg.side().toLowerCase()))
                .toList();

        var request = new CreateMarketInMultivariateEventCollectionRequest(selectedMarkets, true);
        var response = client.createComboMarket(collectionTicker, request);
        return ComboPriceResponse.from(response);
    }
}
