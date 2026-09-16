package com.kalshi.betting.web.dto;

import java.util.List;

/** {@code buildPricedCandidateShortlist}'s full result: the priced candidates to inject into the
 *  prompt, plus {@link ShortlistDiagnostics} explaining exactly how it got there (or didn't). */
public record ShortlistResult(List<PricedComboCandidate> candidates, ShortlistDiagnostics diagnostics) {
}
