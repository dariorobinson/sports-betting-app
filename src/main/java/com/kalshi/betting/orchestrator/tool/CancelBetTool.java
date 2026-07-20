package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Cancel a resting (not yet filled) order by its order ID.")
public class CancelBetTool implements Supplier<String> {

    @JsonPropertyDescription("Order ID to cancel")
    public String orderId;

    @JsonPropertyDescription("Optional: market ticker the order belongs to")
    public String marketTicker;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.bettingService.cancelBet(orderId, marketTicker));
        } catch (Exception e) {
            return "Failed to cancel bet: " + e.getMessage();
        }
    }
}
