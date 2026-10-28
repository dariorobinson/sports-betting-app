package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** A team's full roster — ESPN returns this as a flat list of athlete objects (not grouped by
 *  position), each compatible with {@link EspnAthlete}'s existing fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnRosterResponse(List<EspnAthlete> athletes) {
}
