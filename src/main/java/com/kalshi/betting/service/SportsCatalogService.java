package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.EventData;
import com.kalshi.betting.web.dto.GameSummary;
import com.kalshi.betting.web.dto.OrderbookView;
import com.kalshi.betting.web.dto.SportBoard;
import com.kalshi.betting.web.dto.SportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Browsing surface over Kalshi's sports series/events/markets/orderbook — all read-only, no auth required by Kalshi. */
@Service
public class SportsCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SportsCatalogService.class);

    /** Kalshi's series category value for sports markets. */
    private static final String SPORTS_CATEGORY = "Sports";
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final List<String> MONTH_ABBREVS = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    private final KalshiApiClient client;

    public SportsCatalogService(KalshiApiClient client) {
        this.client = client;
    }

    public List<SportSummary> listSports() {
        return client.listSeries(SPORTS_CATEGORY).series().stream()
                .map(s -> new SportSummary(s.ticker(), s.title(), s.frequency(), s.tags()))
                .toList();
    }

    /**
     * Open games (events) for a given sport series, e.g. "KXNFLGAME", "KXNBAGAME" — restricted to
     * events played TODAY (America/Chicago). Kalshi's "open" status spans a multi-week window (a
     * busy series like KXNCAAFGAME can have 200+ open events stretching two weeks out), and this app
     * is explicitly a same-day research tool, not a multi-day one — returning every open event
     * regardless of date was both wrong (surfaced future-day games) and, for high-volume series,
     * large enough to occasionally blow past the model's prompt-size limit entirely. Events whose
     * ticker doesn't parse to a date (futures/award/tournament-style series, not per-game) are left
     * in rather than dropped, since "today" isn't a meaningful filter for those.
     */
    public List<GameSummary> listGames(String seriesTicker) {
        List<EventData> events = client.listEvents(seriesTicker, "open", true).events();
        LocalDate today = LocalDate.now(ZONE);
        return events.stream()
                .filter(e -> {
                    LocalDate date = gameDate(e.eventTicker());
                    return date == null || today.equals(date);
                })
                .map(GameSummary::from)
                .toList();
    }

    /** The date a game is played, parsed from the ticker suffix after the series prefix (e.g.
     *  "KXNBAGAME-26NOV05ATLWAS" -> 2026-11-05). Null if the suffix isn't a datable game ticker
     *  (e.g. a futures/award series with no per-game date). */
    private static LocalDate gameDate(String eventTicker) {
        if (eventTicker == null) {
            return null;
        }
        int dash = eventTicker.indexOf('-');
        String key = dash < 0 ? eventTicker : eventTicker.substring(dash + 1);
        if (key.length() < 7) {
            return null;
        }
        try {
            int year = 2000 + Integer.parseInt(key.substring(0, 2));
            int month = MONTH_ABBREVS.indexOf(key.substring(2, 5).toUpperCase(Locale.ROOT)) + 1;
            if (month == 0) {
                return null;
            }
            int day = Integer.parseInt(key.substring(5, 7));
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Every game line (Moneyline/Spread/Point Total/Team Total), player prop, and game prop series
     * for one sport, today only — see {@link SportMarketRegistry} for exactly what's included per
     * sport key (e.g. "NFL", "NCAAF", "NBA", "NCAAMB", "EPL", "SOCCER" for all 6 soccer leagues
     * combined — see {@link SportMarketRegistry#knownKeys()} for the full list). Fetches every
     * registered series in parallel (each one already same-day-filtered via {@link #listGames}) so
     * this is ONE tool call for the model instead of discovering and calling each series
     * individually — a single series' fetch failing doesn't fail the whole board, it's just omitted
     * with a warning logged.
     */
    public SportBoard getSportBoard(String sportKey) {
        List<SportMarketRegistry.SeriesEntry> registry = SportMarketRegistry.lookup(sportKey);
        if (registry == null) {
            throw new IllegalArgumentException("Unknown sport \"" + sportKey + "\" — known keys: "
                    + SportMarketRegistry.knownKeys());
        }
        List<SportBoard.MarketGroup> groups = registry.parallelStream()
                .map(entry -> {
                    try {
                        return new SportBoard.MarketGroup(
                                entry.marketType(), entry.seriesTicker(), listGames(entry.seriesTicker()));
                    } catch (RuntimeException e) {
                        log.warn("Sport board: fetching {} ({}) failed: {}",
                                entry.seriesTicker(), entry.marketType(), e.getMessage());
                        return new SportBoard.MarketGroup(entry.marketType(), entry.seriesTicker(), List.of());
                    }
                })
                .toList();
        return new SportBoard(sportKey.trim().toUpperCase(Locale.ROOT), groups);
    }

    public GameSummary getGame(String eventTicker) {
        var response = client.getEvent(eventTicker, true);
        EventData event = response.event();
        // Some responses only populate the deprecated top-level `markets` field rather than nesting them.
        if ((event.markets() == null || event.markets().isEmpty()) && response.markets() != null) {
            event = new EventData(event.eventTicker(), event.seriesTicker(), event.subTitle(), event.title(),
                    event.mutuallyExclusive(), event.strikeDate(), response.markets());
        }
        return GameSummary.from(event);
    }

    public OrderbookView getOrderbook(String marketTicker, int depth) {
        return OrderbookView.from(client.getOrderbook(marketTicker, depth).orderbookFp());
    }
}
