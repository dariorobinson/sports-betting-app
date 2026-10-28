package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.sportsdata.PlayerRecentStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get a team-sport player's most recent game box-score lines from ESPN (points, "
        + "rebounds, assists, etc., newest first) — use this before recommending any PLAYER PROP play "
        + "(e.g. NBA points/rebounds/assists props) to judge how often the player has actually hit a "
        + "given threshold recently. Needs the player's current team to look up their roster first.")
public class GetPlayerRecentStatsTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetPlayerRecentStatsTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'nfl'")
    public String league;

    @JsonPropertyDescription("The player's CURRENT team name — partial names work, e.g. 'Lakers'")
    public String teamName;

    @JsonPropertyDescription("Player name — partial names work, e.g. 'LeBron' matches 'LeBron James'")
    public String playerName;

    @JsonPropertyDescription("How many of the player's most recent played games to return. Defaults to 10.")
    public Integer lastN;

    @Override
    public String get() {
        log.info("Calling ESPN: player recent stats sport={}, league={}, teamName={}, playerName={}, lastN={}",
                sport, league, teamName, playerName, lastN);
        try {
            PlayerRecentStats stats = ToolServices.sportsAnalyticsService.getPlayerRecentStats(
                    sport, league, teamName, playerName, lastN == null ? 0 : lastN);
            String json = ToolServices.toJson(stats);
            log.info("ESPN response (player recent stats, playerName={}): {}", playerName, json);
            return json;
        } catch (Exception e) {
            log.error("ESPN call failed (player recent stats, sport={}, league={}, teamName={}, playerName={})",
                    sport, league, teamName, playerName, e);
            return "Error: " + e.getMessage();
        }
    }
}
