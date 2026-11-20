package com.kalshi.betting.orchestrator;

import com.kalshi.betting.orchestrator.tool.RecordPlayTool;

/**
 * One recommended play, captured directly from a {@link RecordPlayTool} call rather than parsed out
 * of free-text prose. Existing so the scheduler can dedupe, floor-filter, band-sort, and format the
 * final report in plain deterministic Java — no second/third Anthropic call needed just to clean up
 * free-text prose after the fact, which is what made this whole pipeline slow and expensive to run.
 *
 * @param playerName empty string (never null) for a team-level market (Moneyline/Spread/Point
 *                    Total/Team Total/Both Teams to Score) — only set for an individual player prop,
 *                    and used as the "one play per player, any market type" dedup key.
 */
public record RecordedPlay(String league, String displaySubject, String marketType, String line,
                            String americanOdds, int impliedProbabilityPercent, int estimatedProbabilityPercent,
                            String reasoning, String playerName) {

    public int gap() {
        return Math.abs(estimatedProbabilityPercent - impliedProbabilityPercent);
    }

    public boolean isPlayerProp() {
        return playerName != null && !playerName.isBlank();
    }

    /** Builds a line for the required report format: "{subject} — {market}: {line} — {odds} ({implied}%
     *  implied, your estimate: {estimate}%)" followed by the reasoning sentence. */
    public String format() {
        return "%s — %s: %s — %s (%d%% implied, your estimate: %d%%)\n%s"
                .formatted(displaySubject, marketType, line, americanOdds,
                        impliedProbabilityPercent, estimatedProbabilityPercent, reasoning);
    }

    /** Null-safe construction straight from a tool call's parsed fields; returns null (caller logs
     *  and skips) if any field required to render a play is missing — a non-strict tool schema can't
     *  guarantee the model actually populated everything. */
    static RecordedPlay from(RecordPlayTool t) {
        if (isBlank(t.league) || isBlank(t.displaySubject) || isBlank(t.marketType) || isBlank(t.line)
                || isBlank(t.americanOdds) || isBlank(t.reasoning)
                || t.impliedProbabilityPercent == null || t.estimatedProbabilityPercent == null) {
            return null;
        }
        return new RecordedPlay(t.league.trim(), t.displaySubject.trim(), t.marketType.trim(), t.line.trim(),
                t.americanOdds.trim(), t.impliedProbabilityPercent, t.estimatedProbabilityPercent,
                t.reasoning.trim(), t.playerName == null ? "" : t.playerName.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
