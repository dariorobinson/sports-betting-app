package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Identifies one leg of a combo: which market, which event it belongs to, and which side ("yes"/"no"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerPair(String marketTicker, String eventTicker, String side) {
}
