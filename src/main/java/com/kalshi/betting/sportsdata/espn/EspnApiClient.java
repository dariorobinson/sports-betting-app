package com.kalshi.betting.sportsdata.espn;

import com.kalshi.betting.sportsdata.espn.dto.EspnRankingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScheduleResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScoreboardResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnStandingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnTeamsResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over ESPN's public (undocumented, "hidden") site API — free, no key/auth required.
 * Uses its own {@code RestClient} (see {@link EspnClientConfig}) with a plain default Jackson
 * config, deliberately NOT the app's globally snake_case-configured one: ESPN's JSON is camelCase
 * natively (e.g. "displayName"), unlike Kalshi's snake_case API.
 */
@Component
public class EspnApiClient {

    private final RestClient espnRestClient;

    public EspnApiClient(RestClient espnRestClient) {
        this.espnRestClient = espnRestClient;
    }

    /** e.g. sport="basketball", league="nba". Full team list for the league (id, names, abbreviation). */
    public EspnTeamsResponse listTeams(String sport, String league) {
        return espnRestClient.get()
                .uri("/apis/site/v2/sports/{sport}/{league}/teams?limit=200", sport, league)
                .retrieve()
                .body(EspnTeamsResponse.class);
    }

    /** Current standings across all conferences/divisions, with per-team record/stat breakdowns. */
    public EspnStandingsResponse getStandings(String sport, String league) {
        return espnRestClient.get()
                .uri("/apis/v2/sports/{sport}/{league}/standings", sport, league)
                .retrieve()
                .body(EspnStandingsResponse.class);
    }

    /**
     * A team's full schedule (played + upcoming). Pass {@code season} (a year, e.g. "2025") to
     * look at a specific past season instead of the current/default one — ESPN's season year is
     * the year the season *ends* in (e.g. the 2024-25 NBA season is season=2025).
     */
    public EspnScheduleResponse getTeamSchedule(String sport, String league, String teamId, String season) {
        String uri = "/apis/site/v2/sports/{sport}/{league}/teams/{teamId}/schedule"
                + (season != null && !season.isBlank() ? "?season=" + season : "");
        return espnRestClient.get()
                .uri(uri, sport, league, teamId)
                .retrieve()
                .body(EspnScheduleResponse.class);
    }

    /** World rankings for individual-athlete sports, e.g. sport="tennis", tour="atp"/"wta". */
    public EspnRankingsResponse getRankings(String sport, String tour) {
        return espnRestClient.get()
                .uri("/apis/site/v2/sports/{sport}/{tour}/rankings", sport, tour)
                .retrieve()
                .body(EspnRankingsResponse.class);
    }

    /** Live/current event scoreboard — for golf, this doubles as the tournament leaderboard. */
    public EspnScoreboardResponse getScoreboard(String sport, String league) {
        return espnRestClient.get()
                .uri("/apis/site/v2/sports/{sport}/{league}/scoreboard", sport, league)
                .retrieve()
                .body(EspnScoreboardResponse.class);
    }
}
