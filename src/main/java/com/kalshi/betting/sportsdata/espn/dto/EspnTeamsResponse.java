package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnTeamsResponse(List<Sport> sports) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sport(List<League> leagues) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record League(String abbreviation, String name, List<TeamWrapper> teams) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamWrapper(EspnTeam team) {
    }
}
