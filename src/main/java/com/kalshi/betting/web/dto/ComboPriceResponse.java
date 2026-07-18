package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.CreateMarketInMultivariateEventCollectionResponse;
import com.kalshi.betting.client.dto.Market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The real, Kalshi-priced combo market for one specific set of legs. The "implied payout multiple"
 * is 1/price — what a winning $1 contract returns per dollar staked, e.g. a 15¢ ask implies a
 * ~6.67x return if it hits. This is Kalshi's actual combo price, not a naive product of leg prices.
 */
public record ComboPriceResponse(
        String comboEventTicker,
        String comboMarketTicker,
        String yesAskDollars,
        String noAskDollars,
        String impliedYesPayoutMultiple,
        String impliedNoPayoutMultiple
) {
    public static ComboPriceResponse from(CreateMarketInMultivariateEventCollectionResponse response) {
        Market market = response.market();
        String yesAsk = market == null ? null : market.yesAskDollars();
        String noAsk = market == null ? null : market.noAskDollars();
        return new ComboPriceResponse(
                response.eventTicker(),
                response.marketTicker(),
                yesAsk,
                noAsk,
                payoutMultiple(yesAsk),
                payoutMultiple(noAsk)
        );
    }

    private static String payoutMultiple(String priceDollars) {
        if (priceDollars == null) {
            return null;
        }
        BigDecimal price = new BigDecimal(priceDollars);
        if (price.signum() <= 0) {
            return null;
        }
        return BigDecimal.ONE.divide(price, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
