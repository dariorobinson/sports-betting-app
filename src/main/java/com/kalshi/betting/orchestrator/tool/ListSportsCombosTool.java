package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("List open Kalshi combo collections that include at least one sports leg. "
        + "Kalshi files these as 'Exotics' regardless of what they combine, so this checks the "
        + "actual legs, not the collection's category.")
public class ListSportsCombosTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ListSportsCombosTool.class);

    // The Anthropic SDK's schema derivation requires at least one named property per tool, even
    // for parameter-less ones — this field isn't used by get(), just satisfies that constraint.
    @JsonPropertyDescription("Not used — this tool takes no input.")
    public String unused;

    @Override
    public String get() {
        log.info("Calling Kalshi: list sports combos");
        try {
            String result = ToolServices.toJson(ToolServices.comboService.listSportsCombos());
            log.info("Kalshi response (list sports combos): {}", result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (list sports combos)", e);
            return "Failed to list sports combos: " + e.getMessage();
        }
    }
}
