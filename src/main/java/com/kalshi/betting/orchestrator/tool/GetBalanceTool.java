package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get available Kalshi account balance and total portfolio value.")
public class GetBalanceTool implements Supplier<String> {

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.portfolioService.getBalance());
        } catch (Exception e) {
            return "Failed to get balance: " + e.getMessage();
        }
    }
}
