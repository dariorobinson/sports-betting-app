package com.kalshi.betting.sportsdata.espn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One current injury entry for an athlete — {@code status} is ESPN's own vocabulary (seen live:
 *  "Questionable", "Doubtful", "Out", "Injured Reserve", "Day-To-Day"), {@code date} is when ESPN
 *  last updated it. No further detail (body part, expected return) is available from this endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnInjury(String status, String date) {
}
