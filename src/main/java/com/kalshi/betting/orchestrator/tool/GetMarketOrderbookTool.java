package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get the live order book (yes/no bid price levels and sizes) for a single market ticker.")
public class GetMarketOrderbookTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetMarketOrderbookTool.class);

    @JsonPropertyDescription("Market ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL-BOS")
    public String marketTicker;

    @JsonPropertyDescription("Optional: depth of levels to return; omit or 0 for all levels")
    public Integer depth;

    @Override
    public String get() {
        int effectiveDepth = depth == null ? 0 : depth;
        log.info("Calling Kalshi: get order book for marketTicker={}, depth={}", marketTicker, effectiveDepth);
        try {
            String result = ToolServices.toJson(
                    ToolServices.sportsCatalogService.getOrderbook(marketTicker, effectiveDepth));
            log.info("Kalshi response (order book, marketTicker={}): {}", marketTicker, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (order book, marketTicker={})", marketTicker, e);
            return "Failed to get order book: " + e.getMessage();
        }
    }
}
