package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.MarketPosition;

/**
 * Kalshi's {@code MarketPosition} only exposes the market ticker, not the event it belongs to —
 * {@code eventTicker} is resolved separately (one {@code GET /markets/{ticker}} call per position)
 * so callers can tell whether a candidate game/event is already held without string-parsing ticker
 * conventions themselves.
 */
public record MarketPositionView(
        String ticker,
        String eventTicker,
        String totalTradedDollars,
        String positionFp,
        String marketExposureDollars,
        String realizedPnlDollars,
        String feesPaidDollars
) {
    public static MarketPositionView from(MarketPosition position, String eventTicker) {
        return new MarketPositionView(
                position.ticker(),
                eventTicker,
                position.totalTradedDollars(),
                position.positionFp(),
                position.marketExposureDollars(),
                position.realizedPnlDollars(),
                position.feesPaidDollars());
    }
}
