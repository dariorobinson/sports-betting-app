package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** World rankings (tennis ATP/WTA, etc.) — a single ranking list of individual athletes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnRankingsResponse(List<RankingList> rankings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RankingList(String name, List<Rank> ranks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rank(Integer current, Integer previous, Double points, String trend, EspnAthlete athlete) {
    }
}
