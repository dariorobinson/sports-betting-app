package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get a single game/event by its event ticker, including all its markets "
        + "and current yes/no ask prices.")
public class GetGameTool implements Supplier<String> {

    @JsonPropertyDescription("Event ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL")
    public String eventTicker;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.sportsCatalogService.getGame(eventTicker));
        } catch (Exception e) {
            return "Failed to get game: " + e.getMessage();
        }
    }
}
