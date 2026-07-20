package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Cancel a resting (not yet filled) order by its order ID.")
public class CancelBetTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(CancelBetTool.class);

    @JsonPropertyDescription("Order ID to cancel")
    public String orderId;

    @JsonPropertyDescription("Optional: market ticker the order belongs to")
    public String marketTicker;

    @Override
    public String get() {
        log.info("Calling Kalshi: cancel bet orderId={}, marketTicker={}", orderId, marketTicker);
        try {
            String result = ToolServices.toJson(ToolServices.bettingService.cancelBet(orderId, marketTicker));
            log.info("Kalshi response (cancel bet, orderId={}): {}", orderId, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (cancel bet, orderId={})", orderId, e);
            return "Failed to cancel bet: " + e.getMessage();
        }
    }
}
