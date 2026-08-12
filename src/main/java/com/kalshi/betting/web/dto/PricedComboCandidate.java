package com.kalshi.betting.web.dto;

import java.util.List;

/**
 * A combo candidate that ComboService has already RFQ-priced deterministically (no model), ready to
 * inject into the autonomous combo-betting scheduler's prompt. The combined probability / payout /
 * ask all come from a real Kalshi quote, so the model can select and place from this shortlist
 * without doing the expensive survey-and-price round-trips itself.
 */
public record PricedComboCandidate(
        String collectionTicker,
        List<CandidateLeg> legs,
        String combinedImpliedProbability,
        String payoutMultiple,
        String yesAskDollars
) {
}
