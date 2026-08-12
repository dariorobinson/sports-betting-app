package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.MultivariateEventCollection;

/**
 * Summary of one combo ("parlay") collection for the browse/listing step. Carries only the COUNT of
 * available legs, not the full ticker list: a collection can have hundreds of legs and there are
 * many collections, so emitting every ticker here dominated the combo-survey tool payload (resent
 * each agentic-loop iteration). The model narrows on this summary, then calls GetComboLegsTool to
 * pull the actual legs of the one collection it picks.
 */
public record ComboCollectionSummary(
        String collectionTicker,
        String seriesTicker,
        String title,
        String description,
        Integer sizeMin,
        Integer sizeMax,
        Boolean isOrdered,
        Integer legEventCount
) {
    public static ComboCollectionSummary from(MultivariateEventCollection c) {
        int legCount = c.associatedEvents() == null ? 0 : c.associatedEvents().size();
        return new ComboCollectionSummary(c.collectionTicker(), c.seriesTicker(), c.title(), c.description(),
                c.sizeMin(), c.sizeMax(), c.isOrdered(), legCount);
    }
}
