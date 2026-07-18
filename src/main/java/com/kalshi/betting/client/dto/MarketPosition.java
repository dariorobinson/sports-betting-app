package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketPosition(
        String ticker,
        String totalTradedDollars,
        String positionFp,
        String marketExposureDollars,
        String realizedPnlDollars,
        String feesPaidDollars
) {
}
