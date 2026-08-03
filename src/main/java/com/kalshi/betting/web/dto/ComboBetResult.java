package com.kalshi.betting.web.dto;

/**
 * The outcome of attempting to actually place a combo bet (see {@code ComboService.placeComboBet}).
 * {@code status} is one of:
 * <ul>
 *   <li>{@code executed} — a market maker's quote was accepted, confirmed, AND VERIFIED to have
 *       actually filled; contracts/priceDollars/totalCostDollars describe what was actually
 *       bought.</li>
 *   <li>{@code declined} — a quote came back but its size/cost exceeded the budget tolerance, so it
 *       was deliberately NOT accepted rather than risk overspending.</li>
 *   <li>{@code stalled_cancelled} — a quote was accepted and confirmed, but never actually filled
 *       within the verification window, so the resulting resting order was cancelled rather than
 *       left pending indefinitely. No position was left open.</li>
 *   <li>{@code not_filled} — no usable quote came back at all (not an error — no market maker was
 *       available to quote this combo, or the budget couldn't buy even 1 contract).</li>
 * </ul>
 */
public record ComboBetResult(
        String status,
        String comboEventTicker,
        String comboMarketTicker,
        String contracts,
        String priceDollars,
        String totalCostDollars,
        String note
) {
    public static ComboBetResult notFilled(String eventTicker, String marketTicker, String note) {
        return new ComboBetResult("not_filled", eventTicker, marketTicker, null, null, null, note);
    }

    public static ComboBetResult declined(String eventTicker, String marketTicker, String note) {
        return new ComboBetResult("declined", eventTicker, marketTicker, null, null, null, note);
    }

    public static ComboBetResult stalledCancelled(String eventTicker, String marketTicker, String note) {
        return new ComboBetResult("stalled_cancelled", eventTicker, marketTicker, null, null, null, note);
    }

    public static ComboBetResult executed(String eventTicker, String marketTicker, String contracts,
                                            String priceDollars, String totalCostDollars) {
        return new ComboBetResult("executed", eventTicker, marketTicker, contracts, priceDollars,
                totalCostDollars, null);
    }
}
