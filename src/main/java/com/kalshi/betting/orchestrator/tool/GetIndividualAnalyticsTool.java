package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get individual-athlete analytics from ESPN — for tennis, the player's current "
        + "world ranking; for golf, the player's current tournament leaderboard position. Use this "
        + "before recommending a bet on a single-player match.")
public class GetIndividualAnalyticsTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetIndividualAnalyticsTool.class);

    @JsonPropertyDescription("Sport: 'tennis' or 'golf'")
    public String sport;

    @JsonPropertyDescription("For tennis only: tour slug, 'atp' or 'wta'")
    public String tour;

    @JsonPropertyDescription("For golf only: tour/league slug, e.g. 'pga'")
    public String league;

    @JsonPropertyDescription("Player name — partial names work, e.g. 'Djokovic' or 'Scheffler'")
    public String playerName;

    @Override
    public String get() {
        boolean golf = sport != null && sport.equalsIgnoreCase("golf");
        log.info("Calling ESPN: individual analytics sport={}, tour={}, league={}, playerName={}",
                sport, tour, league, playerName);
        try {
            String result = golf
                    ? ToolServices.toJson(ToolServices.sportsAnalyticsService.getGolfLeaderboardPosition(
                            (league == null || league.isBlank()) ? "pga" : league, playerName))
                    : ToolServices.toJson(ToolServices.sportsAnalyticsService.getPlayerRanking(
                            sport, tour, playerName));
            log.info("ESPN response (individual analytics, playerName={}): {}", playerName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (individual analytics, sport={}, tour={}, league={}, playerName={})",
                    sport, tour, league, playerName, e);
            return "Failed to get individual analytics: " + e.getMessage();
        }
    }
}
