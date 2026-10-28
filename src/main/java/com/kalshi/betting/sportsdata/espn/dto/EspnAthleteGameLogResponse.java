package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * An athlete's recent game log. {@code labels} is the ordered list of stat column abbreviations
 * (e.g. "MIN", "REB", "AST", "PTS" for NBA) — each game's {@code stats} array in
 * {@link SeasonTypeCategory#events()} lines up positionally with {@code labels}, NOT by name, so a
 * caller must zip them together rather than assume a fixed stat order (ESPN's hidden API gives no
 * guarantee the column order is stable across sports or over time).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnAthleteGameLogResponse(
        List<String> labels,
        Map<String, GameMeta> events,
        List<SeasonType> seasonTypes
) {
    /** Per-game metadata (date, opponent, result) keyed by event id in the parent response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameMeta(String id, String gameDate, String atVs, EspnTeam opponent,
                            String homeTeamScore, String awayTeamScore, String gameResult) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonType(String displayName, List<SeasonTypeCategory> categories) {
    }

    /** One chunk of games (ESPN buckets these by month) within a season type. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonTypeCategory(String displayName, List<GameStatLine> events) {
    }

    /** One game's stat line — {@code stats[i]} corresponds to {@code labels[i]} on the parent response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameStatLine(String eventId, List<String> stats) {
    }
}
