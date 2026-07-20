package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Event/tournament scoreboard — used for golf leaderboards (individual athlete competitors, not teams). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnScoreboardResponse(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String id, String name, String shortName, List<Competition> competitions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competition(List<Competitor> competitors) {
    }

    /** order = leaderboard position (1 = leader); score is relative-to-par, e.g. "-10". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competitor(Integer order, EspnAthlete athlete, String score) {
    }
}
