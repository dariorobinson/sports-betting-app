package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One settled market from {@code GET /portfolio/settlements} — the authoritative record of a
 * resolved bet's outcome. Realized P&L for the settlement is {@code revenueDollars} (what you were
 * paid out) minus what you paid ({@code yesTotalCostDollars + noTotalCostDollars}); the sign tells
 * you win vs. loss regardless of which side you were on. {@code settledTime} is kept as a raw String
 * and parsed defensively in {@code PnlService} (Kalshi may send ISO-8601 or an epoch).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Settlement(
        String ticker,
        String marketResult,
        String revenueDollars,
        String yesTotalCostDollars,
        String noTotalCostDollars,
        String settledTime,
        Long settledTs
) {
    /** Whichever settle-time representation Kalshi populated: the ISO string if present, else the
     *  epoch-seconds fallback as a string (so the parser handles both without guessing the field). */
    public String settleTimeRaw() {
        if (settledTime != null && !settledTime.isBlank()) {
            return settledTime;
        }
        return settledTs != null ? String.valueOf(settledTs) : null;
    }
}
