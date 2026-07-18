package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.CreateOrderV2Response;

public record BetPlacedResponse(
        String orderId,
        String fillCount,
        String remainingCount,
        String averageFillPriceDollars,
        Long tsMs
) {
    public static BetPlacedResponse from(CreateOrderV2Response r) {
        return new BetPlacedResponse(r.orderId(), r.fillCount(), r.remainingCount(), r.averageFillPrice(), r.tsMs());
    }
}
