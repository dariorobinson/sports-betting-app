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
 */
public record MarketPositionView(
        String ticker,
        String eventTicker,
        String totalTradedDollars,
        String positionFp,
        String marketExposureDollars,
        String realizedPnlDollars,
        String feesPaidDollars,
        List<String> underlyingLegEventTickers
) {
    public static MarketPositionView from(MarketPosition position, String eventTicker,
                                            List<String> underlyingLegEventTickers) {
        return new MarketPositionView(
                position.ticker(),
                eventTicker,
                position.totalTradedDollars(),
                position.positionFp(),
                position.marketExposureDollars(),
                position.realizedPnlDollars(),
                position.feesPaidDollars(),
                underlyingLegEventTickers);
    }
}
