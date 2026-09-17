package com.kalshi.betting.web.dto;

import java.util.List;
import java.util.Set;

/** {@code buildPricedCandidateShortlist}'s full result: the priced candidates to inject into the
 *  prompt, {@link ShortlistDiagnostics} explaining exactly how it got there (or didn't), the
 *  event tickers a caller retrying within the same run should exclude on its next attempt — either
 *  a leg that was actually RFQ-priced but did NOT qualify, OR (when zero candidate leg-sets could
 *  even be FORMED from the favorites found) every favorite found that cycle, since none of them
 *  combined into anything and re-deriving the identical unusable pool wastes an attempt without ever
 *  trying something different — a short, human-readable reason per rejected candidate (not quoted at
 *  all, vs. quoted but worse than the pre-priced estimate), and every distinct favorite found today
 *  ("League (Team) vs Opponent (prob%)") even when no combo ever formed from them, so a "0 candidates"
 *  cycle still has something real and readable to show instead of a bare count. */
public record ShortlistResult(List<PricedComboCandidate> candidates, ShortlistDiagnostics diagnostics,
                              Set<String> rejectedEventTickers, List<String> rejectionDetails,
                              List<String> availableFavorites) {
}
