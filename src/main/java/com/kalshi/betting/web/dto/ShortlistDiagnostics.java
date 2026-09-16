package com.kalshi.betting.web.dto;

/**
 * Phase-by-phase counts from one {@code buildPricedCandidateShortlist} run, so a "no qualifying
 * combos" outcome is diagnosable directly from the Discord report — without pulling EC2 logs — by
 * showing exactly which phase produced zero: no favorites found today, favorites found but no
 * combinations reached the payout band, combinations found but none survived being made
 * game-disjoint, or candidates were priced but the real market rejected all of them.
 */
public record ShortlistDiagnostics(
        int collectionsSurveyed,
        int excludedCommittedGames,
        int favoritesFound,
        int candidateLegSetsBeforeDedup,
        int distinctCandidatesAfterDedup,
        int selectedForPricing,
        int pricingAttemptsMade,
        int qualifiedAfterPricing
) {
    public String summarize() {
        return "surveyed " + collectionsSurveyed + " collection(s), excluded " + excludedCommittedGames
                + " committed game(s), found " + favoritesFound + " favorite(s) today -> "
                + candidateLegSetsBeforeDedup + " candidate combo(s) (" + distinctCandidatesAfterDedup
                + " distinct) -> " + selectedForPricing + " selected, " + pricingAttemptsMade
                + " actually RFQ-priced, " + qualifiedAfterPricing + " qualified";
    }
}
