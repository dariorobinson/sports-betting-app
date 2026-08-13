package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.GetSettlementsResponse;
import com.kalshi.betting.client.dto.Settlement;
import com.kalshi.betting.web.dto.PnlBucket;
import com.kalshi.betting.web.dto.PnlReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Realized P&L and win-rate tracker built from Kalshi's settlements history — completely
 * deterministic, no LLM/orchestrator involved (so it adds ZERO Anthropic cost). Realized P&L for a
 * settled market is {@code revenue − total_cost}; its sign is win vs. loss, side-agnostic (works for
 * combos and single bets alike). Reflects the whole account's settled activity (bot + any manual),
 * i.e. "how am I actually doing", bucketed by day / week / month in America/Chicago.
 */
@Service
public class PnlService {

    private static final Logger log = LoggerFactory.getLogger(PnlService.class);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    /** How far back to pull settlements — enough to cover month-to-date plus the daily trend series. */
    private static final int LOOKBACK_DAYS = 40;
    /** How many trailing days to include in the per-day breakdown. */
    private static final int DAILY_BREAKDOWN_DAYS = 14;
    /** Safety cap on settlement pages fetched (100 each) so a huge history can't loop unbounded. */
    private static final int MAX_PAGES = 50;

    private final KalshiApiClient client;

    public PnlService(KalshiApiClient client) {
        this.client = client;
    }

    /** One settled bet, normalized: when it settled (in CT), what it cost, and its realized P&L. */
    private record Settled(ZonedDateTime settledAt, BigDecimal cost, BigDecimal pnl) {
    }

    public PnlReport getReport() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        long minTs = now.minusDays(LOOKBACK_DAYS).toEpochSecond();

        List<Settled> settled = new ArrayList<>();
        int skipped = 0;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            GetSettlementsResponse resp = client.getSettlements(minTs, cursor);
            List<Settlement> batch = resp == null ? null : resp.settlements();
            if (batch != null) {
                for (Settlement s : batch) {
                    Settled parsed = normalize(s);
                    if (parsed == null) {
                        skipped++;
                    } else {
                        settled.add(parsed);
                    }
                }
            }
            cursor = resp == null ? null : resp.cursor();
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }

        LocalDate todayDate = now.toLocalDate();
        LocalDate weekStart = todayDate.minusDays((todayDate.getDayOfWeek().getValue() + 6) % 7); // Monday
        LocalDate monthStart = todayDate.withDayOfMonth(1);

        List<Settled> todayItems = new ArrayList<>();
        List<Settled> weekItems = new ArrayList<>();
        List<Settled> monthItems = new ArrayList<>();
        TreeMap<LocalDate, List<Settled>> byDay = new TreeMap<>();
        for (Settled s : settled) {
            LocalDate d = s.settledAt().toLocalDate();
            if (d.equals(todayDate)) {
                todayItems.add(s);
            }
            if (!d.isBefore(weekStart)) {
                weekItems.add(s);
            }
            if (!d.isBefore(monthStart)) {
                monthItems.add(s);
            }
            byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(s);
        }

        List<PnlBucket> daily = new ArrayList<>();
        LocalDate cutoff = todayDate.minusDays(DAILY_BREAKDOWN_DAYS - 1L);
        for (var entry : byDay.descendingMap().entrySet()) {
            if (entry.getKey().isBefore(cutoff)) {
                break;
            }
            daily.add(makeBucket(entry.getKey().toString(), entry.getValue()));
        }

        String note = settled.isEmpty()
                ? "No settled bets found in the last " + LOOKBACK_DAYS + " days yet — P&L shows once bets resolve."
                : "Realized settlements over the last " + LOOKBACK_DAYS + " days (America/Chicago)."
                        + (skipped > 0 ? " (" + skipped + " unparseable settlement(s) skipped.)" : "");

        PnlReport report = new PnlReport(
                now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")),
                makeBucket("Today (" + todayDate + ")", todayItems),
                makeBucket("Week to date (since " + weekStart + ")", weekItems),
                makeBucket("Month to date (since " + monthStart + ")", monthItems),
                daily,
                note);
        log.info("P&L report: today net={} ({}), week net={} ({}), month net={} ({})",
                report.today().netPnlDollars(), report.today().winRate(),
                report.weekToDate().netPnlDollars(), report.weekToDate().winRate(),
                report.monthToDate().netPnlDollars(), report.monthToDate().winRate());
        return report;
    }

    private PnlBucket makeBucket(String label, List<Settled> items) {
        int wins = 0;
        int losses = 0;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal won = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal staked = BigDecimal.ZERO;
        for (Settled s : items) {
            net = net.add(s.pnl());
            staked = staked.add(s.cost());
            if (s.pnl().signum() > 0) {
                wins++;
                won = won.add(s.pnl());
            } else if (s.pnl().signum() < 0) {
                losses++;
                lost = lost.add(s.pnl().abs());
            }
        }
        int decided = wins + losses;
        String winRate = decided == 0 ? "n/a" : Math.round(100.0 * wins / decided) + "%";
        String returnPct = staked.signum() == 0 ? "n/a"
                : signed(net.divide(staked, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)), 1) + "%";
        return new PnlBucket(label, items.size(), wins, losses, winRate,
                signed(net, 2), plain(won), plain(lost),
                staked.setScale(2, RoundingMode.HALF_UP).toPlainString(), returnPct);
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Normalizes a raw settlement; returns null if it has no cost (not a real bet) or an unparseable time. */
    private Settled normalize(Settlement s) {
        BigDecimal cost = money(s.yesTotalCostDollars()).add(money(s.noTotalCostDollars()));
        if (cost.signum() <= 0) {
            return null;
        }
        ZonedDateTime settledAt = parseTime(s.settleTimeRaw());
        if (settledAt == null) {
            return null;
        }
        BigDecimal pnl = money(s.revenueDollars()).subtract(cost);
        return new Settled(settledAt, cost, pnl);
    }

    private static BigDecimal money(String s) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Parses a settled time that may be ISO-8601 (e.g. "2026-08-12T02:40:00Z") or epoch seconds. */
    private static ZonedDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(ZONE);
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(raw.trim())).atZone(ZONE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String signed(BigDecimal value, int scale) {
        BigDecimal v = value.setScale(scale, RoundingMode.HALF_UP);
        return (v.signum() >= 0 ? "+" : "") + v.toPlainString();
    }
}
