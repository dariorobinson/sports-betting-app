package com.kalshi.betting.web.dto;

import java.util.List;
import java.util.Set;

/** {@code buildPricedCandidateShortlist}'s full result: the priced candidates to inject into the
 *  prompt, {@link ShortlistDiagnostics} explaining exactly how it got there (or didn't), the
 *  event tickers of every leg that was actually RFQ-priced but did NOT qualify — a caller that
 *  retries within the same run should exclude these on the next attempt, or it will just re-derive
 *  and re-fail on the identical candidates (RFQ pricing doesn't meaningfully change second-to-second
 *  for a specific illiquid combo; only trying DIFFERENT candidates reliably helps) — a short,
 *  human-readable reason per rejected candidate (not quoted at all, vs. quoted but worse than the
 *  pre-priced estimate), and every distinct favorite found today ("League (Team) vs Opponent (prob%)")
 *  even when no combo ever formed from them, so a "0 candidates" cycle still has something real and
 *  readable to show instead of a bare count. */
public record ShortlistResult(List<PricedComboCandidate> candidates, ShortlistDiagnostics diagnostics,
                              Set<String> rejectedEventTickers, List<String> rejectionDetails,
                              List<String> availableFavorites) {
}
