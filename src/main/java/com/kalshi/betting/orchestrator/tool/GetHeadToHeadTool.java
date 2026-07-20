package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get past matchups between two teams (from ESPN's schedule data) — whether "
        + "they've played before, and the results. Defaults to the current season's schedule; "
        + "pass season to check a specific past year if nothing turns up (or you want more history).")
public class GetHeadToHeadTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetHeadToHeadTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football', 'baseball', 'hockey', 'soccer'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'wnba', 'nfl', "
            + "'college-football', 'mlb', 'nhl'. For soccer this is a competition code like "
            + "'eng.1' (Premier League) or 'usa.1' (MLS) — if unsure, try the most likely one.")
    public String league;

    @JsonPropertyDescription("The team whose schedule to search — partial names work, e.g. 'Lakers'")
    public String teamName;

    @JsonPropertyDescription("The opponent to look for in that schedule — partial names work")
    public String opponentName;

    @JsonPropertyDescription("Optional: a specific past season/year (ESPN convention: the year the "
            + "season ends in, e.g. 2025 for the 2024-25 season). Omit for the current season.")
    public String season;

    @Override
    public String get() {
        log.info("Calling ESPN: head-to-head sport={}, league={}, teamName={}, opponentName={}, season={}",
                sport, league, teamName, opponentName, season);
        try {
            String result = ToolServices.toJson(ToolServices.sportsAnalyticsService.getHeadToHead(
                    sport, league, teamName, opponentName, season));
            log.info("ESPN response (head-to-head, teamName={}, opponentName={}): {}", teamName, opponentName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (head-to-head, sport={}, league={}, teamName={}, opponentName={})",
                    sport, league, teamName, opponentName, e);
            return "Failed to get head-to-head history: " + e.getMessage();
        }
    }
}
