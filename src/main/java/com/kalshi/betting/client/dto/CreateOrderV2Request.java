package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mirrors Kalshi's CreateOrderV2Request. {@code side} here is the raw book side
 * (bid = buy YES, ask = buy NO / sell YES) — see {@link com.kalshi.betting.service.BettingService}
 * for the user-friendly yes/no -> bid/ask translation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateOrderV2Request(
        String ticker,
        String clientOrderId,
        String side,
        String count,
        String price,
        String timeInForce,
        String selfTradePreventionType,
        Boolean postOnly,
        Boolean reduceOnly
) {
}
