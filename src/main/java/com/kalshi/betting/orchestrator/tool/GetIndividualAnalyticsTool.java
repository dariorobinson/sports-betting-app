package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.sportsdata.IndividualHeadToHeadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@JsonClassDescription("Get individual-athlete analytics from ESPN — for tennis, the player's current "
        + "world ranking and, if opponentName is given, their head-to-head history against that "
        + "opponent (checks their last 25 played matches for meetings); for golf, the player's "
        + "current tournament leaderboard position (no head-to-head — not applicable to golf). Use "
        + "this before recommending a bet on a single-player match.")
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

    @JsonPropertyDescription("Optional, tennis only: the opponent's name, to also check head-to-head "
            + "history between the two players. Leave blank for golf or when you don't need it.")
    public String opponentName;

    @Override
    public String get() {
        boolean golf = sport != null && sport.equalsIgnoreCase("golf");
        log.info("Calling ESPN: individual analytics sport={}, tour={}, league={}, playerName={}, opponentName={}",
                sport, tour, league, playerName, opponentName);
        try {
            String result;
            if (golf) {
                result = ToolServices.toJson(ToolServices.sportsAnalyticsService.getGolfLeaderboardPosition(
                        (league == null || league.isBlank()) ? "pga" : league, playerName));
            } else {
                Map<String, Object> combined = new LinkedHashMap<>();
                combined.put("ranking", ToolServices.sportsAnalyticsService.getPlayerRanking(sport, tour, playerName));
                if (opponentName != null && !opponentName.isBlank()) {
                    try {
                        IndividualHeadToHeadResult h2h = ToolServices.sportsAnalyticsService.getIndividualHeadToHead(
                                sport, tour, playerName, opponentName);
                        combined.put("headToHead", h2h);
                    } catch (Exception e) {
                        log.error("ESPN call failed (individual head-to-head, playerName={}, opponentName={})",
                                playerName, opponentName, e);
                        combined.put("headToHeadError", e.getMessage());
                    }
                }
                result = ToolServices.toJson(combined);
            }
            log.info("ESPN response (individual analytics, playerName={}): {}", playerName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (individual analytics, sport={}, tour={}, league={}, playerName={})",
                    sport, tour, league, playerName, e);
            return "Failed to get individual analytics: " + e.getMessage();
        }
    }
}
