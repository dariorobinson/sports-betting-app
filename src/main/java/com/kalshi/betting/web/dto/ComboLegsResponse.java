package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.MultivariateEventCollection;

import java.util.List;
import java.util.Map;

/**
 * Either {@code legs} (resolved games/markets for a manageable leg count, or after filtering by
 * {@code seriesTicker}) or {@code legCountsBySeries} (a breakdown to filter by, when there are too
 * many legs to resolve in one response) is populated — never both.
 */
public record ComboLegsResponse(
        String collectionTicker,
        String title,
        String functionalDescription,
        Integer sizeMin,
        Integer sizeMax,
        Boolean isOrdered,
        int totalLegCount,
        String note,
        List<ComboLegEvent> legs,
        Map<String, Long> legCountsBySeries
) {
    public static ComboLegsResponse resolved(MultivariateEventCollection c, List<ComboLegEvent> legs, int totalLegCount) {
        return new ComboLegsResponse(c.collectionTicker(), c.title(), c.functionalDescription(),
                c.sizeMin(), c.sizeMax(), c.isOrdered(), totalLegCount, null, legs, null);
    }

    public static ComboLegsResponse tooManyLegs(MultivariateEventCollection c, int totalLegCount,
                                                 Map<String, Long> legCountsBySeries) {
        String note = "This collection has " + totalLegCount + " legs spanning many series — too many to "
                + "resolve at once. Pass ?seriesTicker=<one of legCountsBySeries's keys> to see the actual "
                + "games/markets/prices for just that series.";
        return new ComboLegsResponse(c.collectionTicker(), c.title(), c.functionalDescription(),
                c.sizeMin(), c.sizeMax(), c.isOrdered(), totalLegCount, note, null, legCountsBySeries);
    }
}
