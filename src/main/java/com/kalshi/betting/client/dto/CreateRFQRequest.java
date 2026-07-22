package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateRFQRequest(String marketTicker, Integer contracts, Boolean restRemainder) {
}
