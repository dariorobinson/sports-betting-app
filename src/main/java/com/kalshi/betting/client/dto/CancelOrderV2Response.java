package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelOrderV2Response(
        String orderId,
        String clientOrderId,
        String reducedBy,
        Long tsMs
) {
}
