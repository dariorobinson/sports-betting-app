package com.kalshi.betting.sportsdata;

import java.util.List;

public record HeadToHeadResult(String team, String opponent, List<Matchup> matchups) {

    public record Matchup(String date, String teamScore, String opponentScore, String result) {
    }
}
