package com.kalshi.betting.sportsdata;

/** scoreToPar is relative to par, e.g. "-10"; position 1 = current leader. */
public record GolfLeaderboardPosition(String player, String tournament, Integer position, String scoreToPar) {
}
