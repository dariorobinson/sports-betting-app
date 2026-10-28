package com.kalshi.betting.sportsdata;

import java.util.List;
import java.util.Map;

/** A player's most recent game box-score lines, newest first — for player-prop reasoning (e.g.
 *  "scored 25+ points in 8 of his last 10 games"). {@code stats} is keyed by ESPN's own stat label
 *  (e.g. "PTS", "REB", "AST") rather than fixed fields, since the stat set varies by sport. */
public record PlayerRecentStats(String player, List<Game> recentGames) {

    public record Game(String date, String opponent, String result, Map<String, String> stats) {
    }
}
