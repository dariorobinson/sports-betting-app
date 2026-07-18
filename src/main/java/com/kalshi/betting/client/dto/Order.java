package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
        String orderId,
        String clientOrderId,
        String ticker,
        String outcomeSide,
        String bookSide,
        String type,
        String status,
        String yesPriceDollars,
        String noPriceDollars,
        String fillCountFp,
        String remainingCountFp,
        String initialCountFp,
        OffsetDateTime createdTime,
        OffsetDateTime lastUpdateTime
) {
}
