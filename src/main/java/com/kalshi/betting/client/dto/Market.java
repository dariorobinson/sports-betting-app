package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Market(
        String ticker,
        String eventTicker,
        String marketType,
        String yesSubTitle,
        String noSubTitle,
        OffsetDateTime openTime,
        OffsetDateTime closeTime,
        String status,
        String yesBidDollars,
        String yesAskDollars,
        String noBidDollars,
        String noAskDollars,
        String lastPriceDollars,
        String previousPriceDollars,
        String volumeFp,
        String volume24hFp,
        String openInterestFp,
        String result
) {
}
