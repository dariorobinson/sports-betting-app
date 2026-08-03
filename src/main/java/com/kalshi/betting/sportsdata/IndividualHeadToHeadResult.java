package com.kalshi.betting.sportsdata;

import java.util.List;

public record IndividualHeadToHeadResult(String player, String opponent, List<Matchup> matchups) {

    /** score is the full set-by-set line, e.g. "4-6 6-3 6-7 (6-8) 6-2 6-3". */
    public record Matchup(String date, String result, String score) {
    }
}
