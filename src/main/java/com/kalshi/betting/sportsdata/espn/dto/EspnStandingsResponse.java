package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Top-level standings response — "children" are conferences/divisions, each with its own entries. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnStandingsResponse(List<Conference> children) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conference(String name, Standings standings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Standings(List<Entry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(EspnTeam team, List<Stat> stats) {
    }

    /** e.g. name="wins", displayValue="42" — displayValue is always the human-readable form, used as-is. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(String name, String displayValue) {
    }
}
