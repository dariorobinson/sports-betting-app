package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventData(
        String eventTicker,
        String seriesTicker,
        String subTitle,
        String title,
        Boolean mutuallyExclusive,
        OffsetDateTime strikeDate,
        List<Market> markets
) {
}
