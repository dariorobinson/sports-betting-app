package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderV2Response(
        String orderId,
        String clientOrderId,
        String fillCount,
        String remainingCount,
        String averageFillPrice,
        String averageFeePaid,
        Long tsMs
) {
}
