package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("List Kalshi's sports series (e.g. NFL, NBA, soccer leagues, tennis, golf) "
        + "available to browse for games and markets.")
public class ListSportsTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ListSportsTool.class);

    // The Anthropic SDK's schema derivation requires at least one named property per tool, even
    // for parameter-less ones — this field isn't used by get(), just satisfies that constraint.
    @JsonPropertyDescription("Not used — this tool takes no input.")
    public String unused;

    @Override
    public String get() {
        log.info("Calling Kalshi: list sports");
        try {
            String result = ToolServices.toJson(ToolServices.sportsCatalogService.listSports());
            log.info("Kalshi response (list sports): {}", result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (list sports)", e);
            return "Failed to list sports: " + e.getMessage();
        }
    }
}
