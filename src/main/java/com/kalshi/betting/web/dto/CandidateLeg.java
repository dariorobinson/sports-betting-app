package com.kalshi.betting.web.dto;

/**
 * One leg of a pre-priced combo candidate (see {@link PricedComboCandidate}). Carries both what the
 * model needs to reason ({@code label} = the team/side name, {@code legImpliedProbability}) and what
 * it needs to place the bet ({@code eventTicker}/{@code marketTicker}/{@code side}, i.e. the same
 * fields as {@link LegSelection}).
 */
public record CandidateLeg(
        String eventTicker,
        String marketTicker,
        String side,
        String label,
        String legImpliedProbability
) {
}
