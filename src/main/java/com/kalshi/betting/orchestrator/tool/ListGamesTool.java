package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("List open games/events for a sports series ticker (from ListSportsTool), "
        + "each with its yes/no markets and live prices.")
public class ListGamesTool implements Supplier<String> {

    @JsonPropertyDescription("Series ticker, e.g. KXNBASUMMERGAME")
    public String seriesTicker;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.sportsCatalogService.listGames(seriesTicker));
        } catch (Exception e) {
            return "Failed to list games: " + e.getMessage();
        }
    }
}
