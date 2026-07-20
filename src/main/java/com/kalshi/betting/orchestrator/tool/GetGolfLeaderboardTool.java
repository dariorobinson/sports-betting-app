package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get an individual golfer's current position in the live/most recent tournament "
        + "leaderboard from ESPN — leaderboard position and score relative to par. Use this to gauge "
        + "form and level of opposition before recommending a bet on a golf market.")
public class GetGolfLeaderboardTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetGolfLeaderboardTool.class);

    @JsonPropertyDescription("Golf tour/league slug, e.g. 'pga'")
    public String league;

    @JsonPropertyDescription("Player name — partial names work, e.g. 'Scheffler' matches 'Scottie Scheffler'")
    public String playerName;

    @Override
    public String get() {
        log.info("Calling ESPN: get golf leaderboard position league={}, playerName={}", league, playerName);
        try {
            String result = ToolServices.toJson(ToolServices.sportsAnalyticsService.getGolfLeaderboardPosition(
                    league, playerName));
            log.info("ESPN response (golf leaderboard, playerName={}): {}", playerName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (golf leaderboard, league={}, playerName={})", league, playerName, e);
            return "Failed to get golf leaderboard position: " + e.getMessage();
        }
    }
}
