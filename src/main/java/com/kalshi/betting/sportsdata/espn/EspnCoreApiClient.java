package com.kalshi.betting.sportsdata.espn;

import com.kalshi.betting.sportsdata.espn.dto.EspnCompetitionDetail;
import com.kalshi.betting.sportsdata.espn.dto.EspnEventLogResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Thin wrapper over ESPN's "core" API (sports.core.api.espn.com) — used for an individual
 * athlete's full match history, which the simpler "site" API doesn't expose. This API returns
 * {@code $ref} pointers rather than embedded data, so callers resolve each match individually via
 * {@link #getCompetition}.
 */
@Component
public class EspnCoreApiClient {

    private final RestClient espnCoreRestClient;

    public EspnCoreApiClient(RestClient espnCoreRestClient) {
        this.espnCoreRestClient = espnCoreRestClient;
    }

    /** First page of the athlete's full match history for the given season (most recent/current
     *  season if omitted), newest first. e.g. sport="tennis", tour="atp"/"wta". */
    public EspnEventLogResponse getAthleteEventLog(String sport, String tour, String athleteId) {
        return espnCoreRestClient.get()
                .uri("/v2/sports/{sport}/leagues/{tour}/athletes/{athleteId}/eventlog", sport, tour, athleteId)
                .retrieve()
                .body(EspnEventLogResponse.class);
    }

    /** Resolves a $ref pointer (a full URL) from an {@link EspnEventLogResponse} item to its
     *  full match detail — competitors, winner, human-readable score. */
    public EspnCompetitionDetail getCompetition(String ref) {
        URI uri = URI.create(ref);
        String pathAndQuery = uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
        return espnCoreRestClient.get()
                .uri(pathAndQuery)
                .retrieve()
                .body(EspnCompetitionDetail.class);
    }
}
