package com.kalshi.betting.ws.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of a "market_position" event on the "market_positions" channel — pushed on every
 *  position change on the account. positionFp == "0" (or "0.00") means the position closed. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketPositionMsg(String marketTicker, String positionFp, String positionCostDollars) {
}
