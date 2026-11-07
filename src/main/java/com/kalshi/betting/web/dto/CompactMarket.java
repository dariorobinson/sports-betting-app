package com.kalshi.betting.web.dto;

/**
 * A market's essential info for bulk sport-board scanning — which line, and its implied probability
 * to buy YES, only. Omits American odds and the "no" side entirely: Kalshi labels "no" identically to
 * "yes" for every market type (confirmed live — a threshold market's {@code no_sub_title} is the exact
 * same text as its {@code yes_sub_title}, and a moneyline market's is the same team name on both
 * sides), so it carries no information here, and pre-computed odds balloon payload size with no
 * benefit when scanning hundreds of games for a first pass. Call GetGameTool for the full
 * {@link MarketSummary} (odds, no-side) on the specific few markets about to be recommended — this is
 * a direct fix for a real crash: a single busy College Football Saturday's board (~5,400 markets
 * across all its series) exceeded the model's 1,000,000-token prompt limit using the full field set.
 */
public record CompactMarket(String ticker, String label, String impliedProbability) {
    public static CompactMarket from(MarketSummary m) {
        return new CompactMarket(m.ticker(), m.yesLabel(), m.yesAskImpliedProbability());
    }
}
