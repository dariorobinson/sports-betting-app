package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventPosition(
        String eventTicker,
        String totalCostDollars,
        String totalCostSharesFp,
        String eventExposureDollars,
        String realizedPnlDollars,
        String feesPaidDollars
) {
}
