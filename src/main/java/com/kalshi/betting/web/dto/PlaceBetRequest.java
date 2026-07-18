package com.kalshi.betting.web.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * User-facing bet placement request. {@code outcome}/{@code action} are expressed the way a
 * bettor thinks about it (buy YES, sell NO, etc.); {@link com.kalshi.betting.service.BettingService}
 * translates this into Kalshi's book-side (bid/ask) order representation.
 */
public record PlaceBetRequest(

        @NotBlank
        String ticker,

        /** "YES" or "NO" — which outcome you're taking a position on. */
        @NotBlank
        @Pattern(regexp = "(?i)yes|no", message = "outcome must be YES or NO")
        String outcome,

        /** "BUY" to open/increase a position, "SELL" to close/reduce one. Defaults to BUY. */
        @Pattern(regexp = "(?i)buy|sell", message = "action must be BUY or SELL")
        String action,

        /** Price of the chosen outcome in dollars, e.g. 0.55 for 55 cents. */
        @NotNull
        @DecimalMin(value = "0.01", message = "price must be at least $0.01")
        @DecimalMax(value = "0.99", message = "price must be at most $0.99")
        BigDecimal price,

        /** Number of contracts. */
        @NotNull
        @Min(1)
        Integer count,

        /**
         * "good_till_canceled" (default), "immediate_or_cancel", or "fill_or_kill".
         */
        String timeInForce,

        Boolean postOnly
) {
    public String resolvedAction() {
        return action == null || action.isBlank() ? "BUY" : action.toUpperCase();
    }

    public String resolvedTimeInForce() {
        return timeInForce == null || timeInForce.isBlank() ? "good_till_canceled" : timeInForce;
    }
}
