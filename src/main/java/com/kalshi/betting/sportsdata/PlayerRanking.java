package com.kalshi.betting.sportsdata;

/** A player's current world ranking. The rank (and the gap between two players' ranks) is the signal
 *  the model actually uses; previous rank / points / trend were dropped to keep the analytics payload
 *  small since it's resent on every agentic-loop iteration. */
public record PlayerRanking(String player, Integer rank) {
}
