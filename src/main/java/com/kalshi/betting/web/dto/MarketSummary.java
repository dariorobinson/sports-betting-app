package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.Market;
import com.kalshi.betting.util.AmericanOdds;

import java.time.OffsetDateTime;

/**
 * A single tradeable outcome within a game. The "ask" prices are what you'd pay right now
 * to buy that side (yes_ask to buy YES, no_ask to buy NO); they're the indicative prices
 * to show a bettor before they submit an order. American-odds fields are derived from the
 * dollar prices (which are themselves the implied probability) — see {@link AmericanOdds}.
 */
public record MarketSummary(
        String ticker,
        String yesLabel,
        String noLabel,
        String status,
        String yesAskDollars,
        String noAskDollars,
        String yesAskAmericanOdds,
        String noAskAmericanOdds,
        String lastPriceDollars,
        OffsetDateTime closeTime
) {
    public static MarketSummary from(Market m) {
        return new MarketSummary(
                m.ticker(), m.yesSubTitle(), m.noSubTitle(), m.status(),
                m.yesAskDollars(), m.noAskDollars(),
                AmericanOdds.fromDollarPrice(m.yesAskDollars()), AmericanOdds.fromDollarPrice(m.noAskDollars()),
                m.lastPriceDollars(), m.closeTime());
    }
}
