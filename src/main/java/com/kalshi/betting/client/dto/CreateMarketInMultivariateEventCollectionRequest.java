package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateMarketInMultivariateEventCollectionRequest(
        List<TickerPair> selectedMarkets,
        Boolean withMarketPayload
) {
}
