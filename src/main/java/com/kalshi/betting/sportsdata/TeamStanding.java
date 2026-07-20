package com.kalshi.betting.sportsdata;

import java.util.Map;

/** All ESPN standings stats for one team, keyed by stat name (e.g. "wins", "streak", "pointDifferential"). */
public record TeamStanding(String team, Map<String, String> stats) {
}
