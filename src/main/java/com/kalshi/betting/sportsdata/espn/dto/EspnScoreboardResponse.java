package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Event/tournament scoreboard — used for golf leaderboards (individual athlete competitors) AND,
 *  for outdoor team sports (football, soccer), per-game weather (see {@link Event#weather()}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnScoreboardResponse(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String id, String name, String shortName, List<Competition> competitions, Weather weather) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competition(List<Competitor> competitors) {
    }

    /** order/score are for golf's leaderboard (order = position, 1 = leader; score is relative-to-par,
     *  e.g. "-10"); team is populated instead for a team-sport scoreboard. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competitor(Integer order, EspnAthlete athlete, String score, EspnTeam team) {
    }

    /** Forecast conditions for an outdoor game. Entirely null/absent for a dome or retractable-roof
     *  stadium — confirmed live (e.g. Lions home games at Ford Field) — not a data gap. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Weather(String displayValue, Integer temperature, Integer highTemperature) {
    }
}
