package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetPositionsResponse(
        List<MarketPosition> marketPositions,
        List<EventPosition> eventPositions,
        String cursor
) {
}
