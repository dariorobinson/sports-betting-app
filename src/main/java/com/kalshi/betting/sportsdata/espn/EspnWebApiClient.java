package com.kalshi.betting.sportsdata.espn;

import com.kalshi.betting.sportsdata.espn.dto.EspnAthleteGameLogResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over ESPN's "web" API (site.web.api.espn.com) — used for an athlete's recent
 * game-by-game box-score log, which neither the "site" nor "core" API exposes.
 */
@Component
public class EspnWebApiClient {

    private final RestClient espnWebRestClient;

    public EspnWebApiClient(RestClient espnWebRestClient) {
        this.espnWebRestClient = espnWebRestClient;
    }

    /** Recent games (current season, newest first within each month bucket) with a per-game stat
     *  line — e.g. sport="basketball", league="nba". */
    public EspnAthleteGameLogResponse getAthleteGameLog(String sport, String league, String athleteId) {
        return espnWebRestClient.get()
                .uri("/apis/common/v3/sports/{sport}/{league}/athletes/{athleteId}/gamelog",
                        sport, league, athleteId)
                .retrieve()
                .body(EspnAthleteGameLogResponse.class);
    }
}
