package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.sportsdata.TeamRecentForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get a team's most recent played games (any opponent) from ESPN, newest "
        + "first, with each game's final score and result. Use this to judge a scoring/form trend "
        + "(e.g. \"scored 30+ in most of their last games\") for a Spread/Total/Team Total play, "
        + "which a season-aggregate stat alone can't show.")
public class GetTeamRecentFormTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetTeamRecentFormTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football', 'baseball', 'hockey', 'soccer'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'nfl', "
            + "'college-football', 'mlb', 'nhl'. For soccer this is a competition code like "
            + "'eng.1' (Premier League) or 'usa.1' (MLS).")
    public String league;

    @JsonPropertyDescription("Team name — partial names work, e.g. 'Lakers' matches 'Los Angeles Lakers'")
    public String teamName;

    @JsonPropertyDescription("How many of the team's most recent played games to return. Defaults to 10.")
    public Integer lastN;

    @Override
    public String get() {
        log.info("Calling ESPN: team recent form sport={}, league={}, teamName={}, lastN={}",
                sport, league, teamName, lastN);
        try {
            TeamRecentForm form = ToolServices.sportsAnalyticsService.getTeamRecentForm(
                    sport, league, teamName, lastN == null ? 0 : lastN);
            String json = ToolServices.toJson(form);
            log.info("ESPN response (team recent form, teamName={}): {}", teamName, json);
            return json;
        } catch (Exception e) {
            log.error("ESPN call failed (team recent form, sport={}, league={}, teamName={})",
                    sport, league, teamName, e);
            return "Error: " + e.getMessage();
        }
    }
}
