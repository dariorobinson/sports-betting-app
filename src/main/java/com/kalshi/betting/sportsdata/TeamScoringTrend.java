package com.kalshi.betting.sportsdata;

/** Compact scoring-trend summary for bulk use across many teams at once (e.g. an entire day's
 *  slate) — average points for/against over the last few played games, not the full game-by-game
 *  list (see {@link TeamRecentForm} for that, when you want the detail for just one specific team).
 *  Either average is null if no played game with a parseable score was found. */
public record TeamScoringTrend(String team, String avgPointsFor, String avgPointsAgainst, int gamesConsidered) {
}
