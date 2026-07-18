package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetBalanceResponse(
        Long balance,
        String balanceDollars,
        Long portfolioValue,
        Long updatedTs
) {
}
