package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Full detail for one played match, resolved from an {@link EspnEventLogResponse} item's ref.
 *  {@code notes[0].text} is ESPN's human-readable set score, e.g. "(1) Jannik Sinner (ITA) bt
 *  Miomir Kecmanovic (SER) 4-6 6-3 6-7 (6-8) 6-2 6-3" — cheaper than resolving each competitor's
 *  own linescores ref just to reconstruct the same thing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnCompetitionDetail(String id, String date, List<Note> notes, List<Competitor> competitors) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Note(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Competitor(String id, String name, Boolean winner) {
    }
}
