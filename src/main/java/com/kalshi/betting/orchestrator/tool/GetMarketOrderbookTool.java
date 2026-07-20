package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get the live order book (yes/no bid price levels and sizes) for a single market ticker.")
public class GetMarketOrderbookTool implements Supplier<String> {

    @JsonPropertyDescription("Market ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL-BOS")
    public String marketTicker;

    @JsonPropertyDescription("Optional: depth of levels to return; omit or 0 for all levels")
    public Integer depth;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(
                    ToolServices.sportsCatalogService.getOrderbook(marketTicker, depth == null ? 0 : depth));
        } catch (Exception e) {
            return "Failed to get order book: " + e.getMessage();
        }
    }
}
