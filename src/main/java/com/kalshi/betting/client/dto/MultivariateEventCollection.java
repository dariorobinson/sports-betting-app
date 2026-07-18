package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultivariateEventCollection(
        String collectionTicker,
        String seriesTicker,
        String title,
        String description,
        OffsetDateTime openDate,
        OffsetDateTime closeDate,
        List<AssociatedEvent> associatedEvents,
        Boolean isOrdered,
        Integer sizeMin,
        Integer sizeMax,
        String functionalDescription
) {
}
