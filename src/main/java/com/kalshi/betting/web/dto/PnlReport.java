package com.kalshi.betting.web.dto;

import java.util.List;

/**
 * Realized P&L / win-rate report built from Kalshi settlements — purely deterministic (no model).
 * Headline rollups are today, week-to-date, and month-to-date (America/Chicago); {@code dailyBreakdown}
 * is the trailing per-day series (newest first) for spotting trends. {@code note} carries any caveat
 * (e.g. the lookback window, or that no settlements were found yet).
 */
public record PnlReport(
        String generatedAt,
        PnlBucket today,
        PnlBucket weekToDate,
        PnlBucket monthToDate,
        List<PnlBucket> dailyBreakdown,
        String note
) {
}
