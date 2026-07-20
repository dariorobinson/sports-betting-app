package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.kalshi.betting.orchestrator.ToolServices;

import java.util.function.Supplier;

@JsonClassDescription("List Kalshi's sports series (e.g. NFL, NBA, soccer leagues, tennis, golf) "
        + "available to browse for games and markets.")
public class ListSportsTool implements Supplier<String> {

    @Override
    public String get() {
        try {
            return ToolServices.toJson(ToolServices.sportsCatalogService.listSports());
        } catch (Exception e) {
            return "Failed to list sports: " + e.getMessage();
        }
    }
}
