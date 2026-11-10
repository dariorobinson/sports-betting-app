package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.sportsdata.TeamInjuryReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get a team's current injury report from ESPN — every rostered player listed "
        + "with a non-empty status (Out, Doubtful, Questionable, Injured Reserve, Day-To-Day, etc.). "
        + "Call this for BOTH teams in any game you're seriously considering before recommending a "
        + "play — a key starter being out/doubtful can change a game's real probability well beyond "
        + "what Kalshi's price reflects, especially for Moneyline/Spread/Team Total and ANY player "
        + "prop involving that player. An empty result means nothing is currently LISTED, not a "
        + "guarantee every player is fully healthy — reporting consistency varies by league, and is "
        + "notably thinner for college sports than the pros.")
public class GetInjuryReportTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetInjuryReportTool.class);

    @JsonPropertyDescription("ESPN sport slug, e.g. 'basketball', 'football', 'baseball', 'hockey', 'soccer'")
    public String sport;

    @JsonPropertyDescription("ESPN league slug within that sport, e.g. 'nba', 'nfl', "
            + "'college-football', 'mens-college-basketball'. For soccer this is a competition code "
            + "like 'eng.1' (Premier League) or 'usa.1' (MLS).")
    public String league;

    @JsonPropertyDescription("Team name — partial names work, e.g. 'Lakers' matches 'Los Angeles Lakers'")
    public String teamName;

    @Override
    public String get() {
        log.info("Calling ESPN: injury report sport={}, league={}, teamName={}", sport, league, teamName);
        try {
            TeamInjuryReport report = ToolServices.sportsAnalyticsService.getInjuryReport(sport, league, teamName);
            String json = ToolServices.toJson(report);
            log.info("ESPN response (injury report, teamName={}): {}", teamName, json);
            return json;
        } catch (Exception e) {
            log.error("ESPN call failed (injury report, sport={}, league={}, teamName={})",
                    sport, league, teamName, e);
            return "Error: " + e.getMessage();
        }
    }
}
