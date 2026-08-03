package com.kalshi.betting.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A Kalshi market's ask price already IS its implied probability — a $0.65 ask implies a ~65%
 *  chance of resolving that way. This just formats that price as a percentage string, pre-computed
 *  so the model reports the exact value instead of doing its own (error-prone) arithmetic. */
public final class ImpliedProbability {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private ImpliedProbability() {
    }

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
        if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        return p.multiply(ONE_HUNDRED).setScale(0, RoundingMode.HALF_UP) + "%";
    }
}
