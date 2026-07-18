package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.MultivariateEventCollection;

import java.util.List;

public record ComboCollectionSummary(
        String collectionTicker,
        String seriesTicker,
        String title,
        String description,
        Integer sizeMin,
        Integer sizeMax,
        Boolean isOrdered,
        List<String> legEventTickers
) {
    public static ComboCollectionSummary from(MultivariateEventCollection c) {
        List<String> legTickers = c.associatedEvents() == null
                ? List.of()
                : c.associatedEvents().stream().map(a -> a.ticker()).toList();
        return new ComboCollectionSummary(c.collectionTicker(), c.seriesTicker(), c.title(), c.description(),
                c.sizeMin(), c.sizeMax(), c.isOrdered(), legTickers);
    }
}
