package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnAthlete(
        String id,
        String firstName,
        String lastName,
        String displayName,
        String fullName,
        String shortName,
        /** Only populated on a roster-fetch response (tennis/golf athlete lookups leave this null) —
         *  empty/null means no current injury listed, NOT a guarantee of full health (some sports,
         *  notably college football, report this far less consistently than the pros). */
        List<EspnInjury> injuries
) {
    /** Golf's leaderboard uses fullName/shortName; tennis rankings use firstName/lastName/displayName — prefer whichever is present. */
    public String bestDisplayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (fullName != null && !fullName.isBlank()) return fullName;
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
