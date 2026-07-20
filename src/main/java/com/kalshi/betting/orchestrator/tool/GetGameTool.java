package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get a single game/event by its event ticker, including all its markets "
        + "and current yes/no ask prices.")
public class GetGameTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetGameTool.class);

    @JsonPropertyDescription("Event ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL")
    public String eventTicker;

    @Override
    public String get() {
        log.info("Calling Kalshi: get game for eventTicker={}", eventTicker);
        try {
            String result = ToolServices.toJson(ToolServices.sportsCatalogService.getGame(eventTicker));
            log.info("Kalshi response (get game, eventTicker={}): {}", eventTicker, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (get game, eventTicker={})", eventTicker, e);
            return "Failed to get game: " + e.getMessage();
        }
    }
}
