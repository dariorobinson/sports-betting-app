package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnTeam(
        String id,
        String location,
        String name,
        String abbreviation,
        String displayName,
        String shortDisplayName
) {
}
