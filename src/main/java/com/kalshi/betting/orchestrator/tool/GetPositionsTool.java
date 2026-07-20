package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get current open market and event positions, including exposure and realized P&L.")
public class GetPositionsTool implements Supplier<String> {

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.portfolioService.getPositions());
        } catch (Exception e) {
            return "Failed to get positions: " + e.getMessage();
        }
    }
}
