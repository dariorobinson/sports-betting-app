package com.kalshi.betting.web.dto;

import com.kalshi.betting.client.dto.Quote;
import com.kalshi.betting.util.AmericanOdds;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The real, Kalshi-priced combo market for one specific set of legs. Combo markets have no resting
 * order book — a real price requires a market maker to respond to a request-for-quote (RFQ), which
 * isn't instant and isn't guaranteed (see {@code ComboService.priceCombo}). If a quote came back,
 * {@code quoted} is true and the ask prices are real; if not, {@code quoted} is false and the ask
 * fields are null — this does NOT mean the combo is worthless, just that no market maker responded
 * in time.
 * <p>
 * The "implied payout multiple" is 1/price — what a winning $1 contract returns per dollar staked.
 */
public record ComboPriceResponse(
        String comboEventTicker,
        String comboMarketTicker,
        boolean quoted,
        String yesAskDollars,
        String noAskDollars,
        String yesAskAmericanOdds,
        String noAskAmericanOdds,
        String impliedYesPayoutMultiple,
        String impliedNoPayoutMultiple,
        String note
) {
    public static ComboPriceResponse unquoted(String eventTicker, String marketTicker) {
        return new ComboPriceResponse(eventTicker, marketTicker, false, null, null, null, null, null, null,
                "No market maker responded with a quote in time — this doesn't mean the combo is bad, "
                        + "just that nobody quoted it yet. Try again later, or a different leg combination.");
    }

    /** yes/no on a Quote are the price the quoter is BIDDING to buy that side, so our ask to BUY
     *  yes is (1 - noBidDollars) and our ask to buy no is (1 - yesBidDollars) — Kalshi's yes+no
     *  prices always sum to $1. */
    public static ComboPriceResponse quoted(String eventTicker, String marketTicker, Quote quote) {
        String yesAsk = complement(quote.noBidDollars());
        String noAsk = complement(quote.yesBidDollars());
        return new ComboPriceResponse(
                eventTicker,
                marketTicker,
                true,
                yesAsk,
                noAsk,
                AmericanOdds.fromDollarPrice(yesAsk),
                AmericanOdds.fromDollarPrice(noAsk),
                payoutMultiple(yesAsk),
                payoutMultiple(noAsk),
                null);
    }

    private static String complement(String priceDollars) {
        if (priceDollars == null) {
            return null;
        }
        return BigDecimal.ONE.subtract(new BigDecimal(priceDollars)).setScale(4, RoundingMode.HALF_UP).toPlainString();
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
