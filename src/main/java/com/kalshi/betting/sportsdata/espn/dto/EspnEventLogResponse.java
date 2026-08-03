package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** A page of an athlete's full match history — each item is a $ref pointer to the full
 *  competition detail (ESPN's "core" API returns references, not embedded data, here). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnEventLogResponse(EventsBlock events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventsBlock(int pageIndex, int pageCount, List<Item> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(CompetitionRef competition, Boolean played) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompetitionRef(@JsonProperty("$ref") String ref) {
    }
}
