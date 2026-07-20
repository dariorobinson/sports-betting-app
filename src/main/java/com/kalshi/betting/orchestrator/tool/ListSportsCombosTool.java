package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("List open Kalshi combo collections that include at least one sports leg. "
        + "Kalshi files these as 'Exotics' regardless of what they combine, so this checks the "
        + "actual legs, not the collection's category.")
public class ListSportsCombosTool implements Supplier<String> {

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.comboService.listSportsCombos());
        } catch (Exception e) {
            return "Failed to list sports combos: " + e.getMessage();
        }
    }
}
