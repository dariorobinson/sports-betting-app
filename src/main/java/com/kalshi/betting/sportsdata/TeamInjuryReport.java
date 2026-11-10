package com.kalshi.betting.sportsdata;

import java.util.List;

/** Every rostered player currently listed with a non-empty injury status — not an exhaustive guarantee
 *  of full health for anyone NOT listed here (ESPN's injury reporting, especially for college sports,
 *  is self-reported by each program and inconsistent), just what ESPN currently has on record. */
public record TeamInjuryReport(String team, List<PlayerInjury> injuries) {

    public record PlayerInjury(String player, String status, String asOf) {
    }
}
