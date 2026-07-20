package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnScheduleResponse(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String id, String date, String name, String shortName, List<Competition> competitions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competition(List<Competitor> competitors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competitor(EspnTeam team, Boolean winner, Score score) {
    }

    /** Null for games that haven't been played yet. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Score(Double value, String displayValue) {
    }
}
