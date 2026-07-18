package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssociatedEvent(
        String ticker,
        Boolean isYesOnly,
        Integer sizeMin,
        Integer sizeMax
) {
}
