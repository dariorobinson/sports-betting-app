package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.MarketPosition;

import java.util.List;

/**
 * Kalshi's {@code MarketPosition} only exposes the market ticker, not the event it belongs to —
 * {@code eventTicker} is resolved separately (one {@code GET /markets/{ticker}} call per position)
 * so callers can tell whether a candidate game/event is already held without string-parsing ticker
 * conventions themselves.
 * <p>
 * {@code underlyingLegEventTickers} is populated only for combo positions this app itself placed
 * (Kalshi has no API to look up a combo market's composing legs after the fact — only the app that
 * built it knows) — non-null means "these events are tied up in this combo, don't reuse them as
 * legs in a new one."
 * <p>
 * Carries only what's needed to decide whether an event is already held (ticker/eventTicker,
 * current position size, exposure, and the combo legs) — the P&L/fees/traded-value bookkeeping
 * fields were dropped: the model never uses them to place a NEW bet, and this view is resent on
 * every agentic-loop iteration.
 */
public record MarketPositionView(
        String ticker,
        String eventTicker,
        String positionFp,
        String marketExposureDollars,
        List<String> underlyingLegEventTickers
) {
    public static MarketPositionView from(MarketPosition position, String eventTicker,
                                            List<String> underlyingLegEventTickers) {
        return new MarketPositionView(
                position.ticker(),
                eventTicker,
                position.positionFp(),
                position.marketExposureDollars(),
                underlyingLegEventTickers);
    }
}
