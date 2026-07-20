package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.sportsdata.HeadToHeadResult;
import com.kalshi.betting.sportsdata.TeamStanding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@JsonClassDescription("Get team-sport analytics from ESPN in one call: a team's current record/standing "
        + "(wins, losses, streak, rank, etc.) and, if opponentName is given, head-to-head history "
        + "against that opponent. Use this before recommending a team-sport bet.")
public class GetTeamAnalyticsTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetTeamAnalyticsTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football', 'baseball', 'hockey', 'soccer'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'wnba', 'nfl', "
            + "'college-football', 'mlb', 'nhl'. For soccer this is a competition code like "
            + "'eng.1' (Premier League) or 'usa.1' (MLS).")
    public String league;

    @JsonPropertyDescription("Team name — partial names work, e.g. 'Lakers' matches 'Los Angeles Lakers'")
    public String teamName;

    @JsonPropertyDescription("Optional: the opponent's team name, to also fetch head-to-head history. "
            + "Leave blank to only get the standing.")
    public String opponentName;

    @JsonPropertyDescription("Optional: a specific past season for head-to-head (year the season ends "
            + "in, e.g. 2025 for 2024-25). Defaults to the current season.")
    public String season;

    @Override
    public String get() {
        log.info("Calling ESPN: team analytics sport={}, league={}, teamName={}, opponentName={}",
                sport, league, teamName, opponentName);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            TeamStanding standing = ToolServices.sportsAnalyticsService.getTeamStanding(sport, league, teamName);
            result.put("standing", standing);
        } catch (Exception e) {
            log.error("ESPN call failed (team standing, sport={}, league={}, teamName={})",
                    sport, league, teamName, e);
            result.put("standingError", e.getMessage());
        }
        if (opponentName != null && !opponentName.isBlank()) {
            try {
                HeadToHeadResult h2h = ToolServices.sportsAnalyticsService.getHeadToHead(
                        sport, league, teamName, opponentName, season);
                result.put("headToHead", h2h);
            } catch (Exception e) {
                log.error("ESPN call failed (head to head, sport={}, league={}, teamName={}, opponentName={})",
                        sport, league, teamName, opponentName, e);
                result.put("headToHeadError", e.getMessage());
            }
        }
        String json = ToolServices.toJson(result);
        log.info("ESPN response (team analytics, teamName={}): {}", teamName, json);
        return json;
    }
}
