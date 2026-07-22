package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.EventPosition;

import java.util.List;

public record PositionsView(
        List<MarketPositionView> marketPositions,
        List<EventPosition> eventPositions
) {
}
