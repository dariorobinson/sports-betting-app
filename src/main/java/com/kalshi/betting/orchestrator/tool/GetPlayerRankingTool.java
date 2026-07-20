package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get an individual athlete's current world ranking from ESPN — rank, "
        + "previous rank, points, trend. For individual-competitor sports like tennis (ATP/WTA). "
        + "Use this to gauge the level of opposition before recommending a bet on a single-player match.")
public class GetPlayerRankingTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetPlayerRankingTool.class);

    @JsonPropertyDescription("ESPN sport slug — currently only 'tennis' is supported")
    public String sport;

    @JsonPropertyDescription("Tour slug within that sport, e.g. 'atp' or 'wta'")
    public String tour;

    @JsonPropertyDescription("Player name — partial names work, e.g. 'Djokovic' matches 'Novak Djokovic'")
    public String playerName;

    @Override
    public String get() {
        log.info("Calling ESPN: get player ranking sport={}, tour={}, playerName={}", sport, tour, playerName);
        try {
            String result = ToolServices.toJson(ToolServices.sportsAnalyticsService.getPlayerRanking(
                    sport, tour, playerName));
            log.info("ESPN response (player ranking, playerName={}): {}", playerName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (player ranking, sport={}, tour={}, playerName={})",
                    sport, tour, playerName, e);
            return "Failed to get player ranking: " + e.getMessage();
        }
    }
}
