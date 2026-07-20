package com.kalshi.betting.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a Kalshi dollar price (which equals the implied probability, e.g. "0.5500" = 55%) into
 * American odds notation (e.g. "-122" or "+122"). Kalshi is a peer-to-peer exchange, not a
 * bookmaker, so the price already is the fair implied probability — no vig/margin to strip out
 * before converting, unlike a traditional sportsbook's line.
 */
public final class AmericanOdds {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private AmericanOdds() {
    }

    /** Returns e.g. "-122" or "+122", or null if priceDollars is null/out of the valid (0,1) range. */
    public static String fromDollarPrice(String priceDollars) {
        if (priceDollars == null || priceDollars.isBlank()) {
            return null;
        }
        BigDecimal p;
        try {
            p = new BigDecimal(priceDollars);
        } catch (NumberFormatException e) {
            return null;
        }
        if (p.signum() <= 0 || p.compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }

        if (p.compareTo(HALF) >= 0) {
            BigDecimal odds = p.multiply(ONE_HUNDRED)
                    .divide(BigDecimal.ONE.subtract(p), 0, RoundingMode.HALF_UP);
            return "-" + odds.toBigInteger();
        } else {
            BigDecimal odds = BigDecimal.ONE.subtract(p).multiply(ONE_HUNDRED)
                    .divide(p, 0, RoundingMode.HALF_UP);
            return "+" + odds.toBigInteger();
        }
    }
}
