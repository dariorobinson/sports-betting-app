package com.kalshi.betting.sportsdata;

import java.util.List;

/** A team's most recent played games (any opponent), newest first — for reasoning about scoring
 *  trends (e.g. "scored 30+ in 7 of their last 10 games") on totals/spreads rather than just a
 *  season-aggregate average. */
public record TeamRecentForm(String team, List<Game> recentGames) {

    public record Game(String date, String opponent, String teamScore, String opponentScore, String result) {
    }
}
