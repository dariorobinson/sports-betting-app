package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMarketInMultivariateEventCollectionResponse(
        String eventTicker,
        String marketTicker,
        Market market
) {
}
