package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.OrderbookCountFp;

import java.util.List;

public record OrderbookView(List<PriceLevel> yesBids, List<PriceLevel> noBids) {

    public record PriceLevel(String priceDollars, String contracts) {
    }

    public static OrderbookView from(OrderbookCountFp raw) {
        return new OrderbookView(toLevels(raw.yesDollars()), toLevels(raw.noDollars()));
    }

    private static List<PriceLevel> toLevels(List<List<String>> levels) {
        return levels.stream()
                .map(level -> new PriceLevel(level.get(0), level.get(1)))
                .toList();
    }
}
