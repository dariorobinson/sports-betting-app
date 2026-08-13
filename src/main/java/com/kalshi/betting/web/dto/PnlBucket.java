package com.kalshi.betting.web.dto;

/**
 * P&L + win-rate summary over one time window (a day, a week, a month, or a rolling total). All
 * money is a settled realized figure (revenue − cost) summed over the settlements in the window.
 * A "bet" here is one settled market that had a cost; a win is a settlement with positive realized
 * P&L, a loss one with negative. {@code winRate} and {@code returnPct} are pre-formatted strings.
 */
public record PnlBucket(
        String label,
        int bets,
        int wins,
        int losses,
        String winRate,
        String netPnlDollars,
        String wonDollars,
        String lostDollars,
        String stakedDollars,
        String returnPct
) {
}
