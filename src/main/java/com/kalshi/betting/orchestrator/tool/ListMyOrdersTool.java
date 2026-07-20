package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("List your own orders, optionally filtered by status or market ticker.")
public class ListMyOrdersTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ListMyOrdersTool.class);

    @JsonPropertyDescription("Optional: filter by status - resting, canceled, or executed")
    public String status;

    @JsonPropertyDescription("Optional: filter by market ticker")
    public String ticker;

    @Override
    public String get() {
        log.info("Calling Kalshi: list my orders status={}, ticker={}", status, ticker);
        try {
            String result = ToolServices.toJson(ToolServices.bettingService.listMyOrders(status, ticker));
            log.info("Kalshi response (list my orders): {}", result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (list my orders, status={}, ticker={})", status, ticker, e);
            return "Failed to list orders: " + e.getMessage();
        }
    }
}
