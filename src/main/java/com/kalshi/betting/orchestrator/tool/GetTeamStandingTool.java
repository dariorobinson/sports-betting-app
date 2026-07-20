package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get a team's current record/standing from ESPN — wins, losses, win "
        + "percentage, point differential, streak, division/conference rank, home/road/last-10 "
        + "splits, etc. Use this to gauge the level of opposition before recommending a bet.")
public class GetTeamStandingTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetTeamStandingTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football', 'baseball', 'hockey', 'soccer'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'wnba', 'nfl', "
            + "'college-football', 'mlb', 'nhl'. For soccer this is a competition code like "
            + "'eng.1' (Premier League) or 'usa.1' (MLS) — if unsure, try the most likely one.")
    public String league;

    @JsonPropertyDescription("Team name — partial names work, e.g. 'Lakers' matches 'Los Angeles Lakers'")
    public String teamName;

    @Override
    public String get() {
        log.info("Calling ESPN: get team standing sport={}, league={}, teamName={}", sport, league, teamName);
        try {
            String result = ToolServices.toJson(ToolServices.sportsAnalyticsService.getTeamStanding(
                    sport, league, teamName));
            log.info("ESPN response (team standing, teamName={}): {}", teamName, result);
            return result;
        } catch (Exception e) {
            log.error("ESPN call failed (team standing, sport={}, league={}, teamName={})",
                    sport, league, teamName, e);
            return "Failed to get team standing: " + e.getMessage();
        }
    }
}
