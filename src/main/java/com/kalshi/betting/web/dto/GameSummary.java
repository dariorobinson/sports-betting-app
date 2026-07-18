package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.EventData;

import java.time.OffsetDateTime;
import java.util.List;

public record GameSummary(
        String eventTicker,
        String seriesTicker,
        String title,
        String subTitle,
        OffsetDateTime strikeDate,
        List<MarketSummary> markets
) {
    public static GameSummary from(EventData e) {
        List<MarketSummary> markets = e.markets() == null
                ? List.of()
                : e.markets().stream().map(MarketSummary::from).toList();
        return new GameSummary(e.eventTicker(), e.seriesTicker(), e.title(), e.subTitle(), e.strikeDate(), markets);
    }
}
