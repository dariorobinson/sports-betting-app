package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("List your own orders, optionally filtered by status or market ticker.")
public class ListMyOrdersTool implements Supplier<String> {

    @JsonPropertyDescription("Optional: filter by status - resting, canceled, or executed")
    public String status;

    @JsonPropertyDescription("Optional: filter by market ticker")
    public String ticker;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.bettingService.listMyOrders(status, ticker));
        } catch (Exception e) {
            return "Failed to list orders: " + e.getMessage();
        }
    }
}
