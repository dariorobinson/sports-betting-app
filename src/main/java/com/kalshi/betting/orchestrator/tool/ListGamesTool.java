package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("List open games/events for a sports series ticker (from ListSportsTool), "
        + "each with its yes/no markets and live prices.")
public class ListGamesTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ListGamesTool.class);

    @JsonPropertyDescription("Series ticker, e.g. KXNBASUMMERGAME")
    public String seriesTicker;

    @Override
    public String get() {
        log.info("Calling Kalshi: list games for seriesTicker={}", seriesTicker);
        try {
            String result = ToolServices.toJson(ToolServices.sportsCatalogService.listGames(seriesTicker));
            log.info("Kalshi response (list games, seriesTicker={}): {}", seriesTicker, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (list games, seriesTicker={})", seriesTicker, e);
            return "Failed to list games: " + e.getMessage();
        }
    }
}
