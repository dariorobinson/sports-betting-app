package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("Get the legs (games/props) available to pick from within a combo "
        + "collection, with live prices. Collections can have hundreds of legs across many "
        + "series — if there are too many to resolve at once, this returns a count of legs per "
        + "series instead; pass seriesTicker to drill into one of those series and get actual "
        + "resolved games/markets/prices.")
public class GetComboLegsTool implements Supplier<String> {

    @JsonPropertyDescription("Combo collection ticker, from ListSportsCombosTool")
    public String collectionTicker;

    @JsonPropertyDescription("Optional: series ticker to filter legs down to, e.g. KXNBASUMMERGAME")
    public String seriesTicker;

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.comboService.getComboLegs(collectionTicker, seriesTicker));
        } catch (Exception e) {
            return "Failed to get combo legs: " + e.getMessage();
        }
    }
}
