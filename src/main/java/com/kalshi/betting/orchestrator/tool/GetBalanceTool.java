package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get available Kalshi account balance and total portfolio value.")
public class GetBalanceTool implements Supplier<String> {

    // The Anthropic SDK's schema derivation requires at least one named property per tool, even
    // for parameter-less ones — this field isn't used by get(), just satisfies that constraint.
    @JsonPropertyDescription("Not used — this tool takes no input.")
    public String unused;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.portfolioService.getBalance());
        } catch (Exception e) {
            return "Failed to get balance: " + e.getMessage();
        }
    }
}
