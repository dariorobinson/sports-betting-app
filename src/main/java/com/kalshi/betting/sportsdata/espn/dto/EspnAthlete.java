package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnAthlete(
        String id,
        String firstName,
        String lastName,
        String displayName,
        String fullName,
        String shortName
) {
    /** Golf's leaderboard uses fullName/shortName; tennis rankings use firstName/lastName/displayName — prefer whichever is present. */
    public String bestDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (fullName != null && !fullName.isBlank()) return fullName;
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
