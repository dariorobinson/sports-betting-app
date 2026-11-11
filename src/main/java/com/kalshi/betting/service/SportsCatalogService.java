package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.EventData;
import com.kalshi.betting.sportsdata.PlayerRecentStats;
import com.kalshi.betting.sportsdata.SportsAnalyticsService;
import com.kalshi.betting.sportsdata.TeamScoringTrend;
import com.kalshi.betting.web.dto.CompactMarket;
import com.kalshi.betting.web.dto.GameSummary;
import com.kalshi.betting.web.dto.OrderbookView;
import com.kalshi.betting.web.dto.SportBoard;
import com.kalshi.betting.web.dto.SportSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Browsing surface over Kalshi's sports series/events/markets/orderbook — all read-only, no auth required by Kalshi. */
@Service
public class SportsCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SportsCatalogService.class);

    /** Kalshi's series category value for sports markets. */
    private static final String SPORTS_CATEGORY = "Sports";
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final List<String> MONTH_ABBREVS = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    /** How many recent games {@link #getSportBoard}'s bulk scoring-trend lookup averages over per
     *  team — smaller than {@code SportsAnalyticsService.DEFAULT_RECENT_GAMES} since this gets
     *  multiplied across every team in a sport's whole slate (up to 180+ on a busy CFB Saturday). */
    private static final int BOARD_TREND_LOOKBACK_GAMES = 5;
    /** How many recent games {@link #getSportBoard}'s bulk player-prop stats fetch pulls per player —
     *  the full default (not the lighter team-trend sample), since "X of his last 10 games" hit-rate
     *  framing needs the full window. */
    private static final int BOARD_PLAYER_LOOKBACK_GAMES = 10;
    /** Safety ceiling on how many distinct players {@link #getSportBoard} will bulk-fetch stats for
     *  in one call — not expected to bind in practice, just a defensive bound against a pathological
     *  slate size. */
    private static final int BOARD_MAX_PLAYER_LOOKUPS = 150;

    private final KalshiApiClient client;
    private final SportsAnalyticsService sportsAnalyticsService;

    public SportsCatalogService(KalshiApiClient client, SportsAnalyticsService sportsAnalyticsService) {
        this.client = client;
        this.sportsAnalyticsService = sportsAnalyticsService;
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
        List<FetchedGroup> fetched = registry.parallelStream().map(this::fetchMarketGroup).toList();

        List<SportBoard.MarketGroup> groups = fetched.stream().map(FetchedGroup::group).toList();
        List<TeamLookup> teamLookups = fetched.stream().flatMap(f -> f.teamLookups().stream()).toList();
        List<PlayerLookup> playerLookups = fetched.stream().flatMap(f -> f.playerLookups().stream()).toList();
        List<TeamScoringTrend> trends = fetchTeamScoringTrends(teamLookups);
        List<PlayerRecentStats> playerStats = fetchPlayerRecentStatsBulk(teamLookups, playerLookups);

        return new SportBoard(sportKey.trim().toUpperCase(Locale.ROOT), groups, trends, playerStats);
    }

    private record TeamLookup(String espnSport, String espnLeague, String teamName) {
    }

    private record PlayerLookup(String espnSport, String espnLeague, String playerName) {
    }

    private record FetchedGroup(SportBoard.MarketGroup group, List<TeamLookup> teamLookups,
                                 List<PlayerLookup> playerLookups) {
    }

    /**
     * Fetches one series' games (already same-day-filtered via {@link #listGames}), trims each
     * market down to its {@link CompactMarket} view (dropping American odds and the always-redundant
     * "no" side — see that record's javadoc; only inactive/settled markets are dropped entirely, no
     * game is ever dropped), and collects whatever bulk-lookup hints this series can offer in the
     * same pass (no second HTTP call per series): team names playing today if this is a Moneyline
     * series (ticker ends in "GAME"), for {@link #fetchTeamScoringTrends}; player names if this is a
     * player-prop series, for {@link #fetchPlayerRecentStatsBulk} — Kalshi labels a player-prop
     * market "{Player Name}: {threshold}+" consistently (confirmed live for NFL and NBA), so the name
     * is just everything before the first ": ".
     */
    private FetchedGroup fetchMarketGroup(SportMarketRegistry.SeriesEntry entry) {
        List<GameSummary> rawGames;
        try {
            rawGames = listGames(entry.seriesTicker());
        } catch (RuntimeException e) {
            log.warn("Sport board: fetching {} ({}) failed: {}",
                    entry.seriesTicker(), entry.marketType(), e.getMessage());
            return new FetchedGroup(
                    new SportBoard.MarketGroup(entry.marketType(), entry.seriesTicker(), List.of()), List.of(), List.of());
        }

        List<TeamLookup> teamLookups = new ArrayList<>();
        if (entry.seriesTicker().endsWith("GAME") && entry.espnSport() != null) {
            for (GameSummary game : rawGames) {
                for (String team : splitTeams(game.title())) {
                    teamLookups.add(new TeamLookup(entry.espnSport(), entry.espnLeague(), team));
                }
            }
        }

        List<SportBoard.CompactGame> compactGames = rawGames.stream()
                .map(g -> new SportBoard.CompactGame(g.eventTicker(), g.title(),
                        g.markets().stream()
                                .filter(m -> "active".equalsIgnoreCase(m.status()))
                                .map(CompactMarket::from)
                                .toList()))
                .toList();

        List<PlayerLookup> playerLookups = new ArrayList<>();
        if (entry.marketType().contains("(player prop)") && entry.espnSport() != null) {
            for (SportBoard.CompactGame game : compactGames) {
                for (CompactMarket m : game.markets()) {
                    String playerName = extractPlayerName(m.label());
                    if (playerName != null) {
                        playerLookups.add(new PlayerLookup(entry.espnSport(), entry.espnLeague(), playerName));
                    }
                }
            }
        }

        return new FetchedGroup(
                new SportBoard.MarketGroup(entry.marketType(), entry.seriesTicker(), compactGames),
                teamLookups, playerLookups);
    }

    /** Kalshi labels most player-prop markets "{Player Name}: {threshold}+" (e.g. "Karl-Anthony
     *  Towns: 25+", "Trevor Lawrence: 275+") — everything before the first ": " is the player's
     *  name. Some (confirmed: NHL's "First Goalscorer" market) instead label it as JUST the bare
     *  player name with no threshold suffix at all — in that case the whole label IS the name. */
    private static String extractPlayerName(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int colon = label.indexOf(": ");
        return (colon >= 0 ? label.substring(0, colon) : label).trim();
    }

    /**
     * Bulk-fetches a compact recent-scoring-trend summary for every distinct team appearing in this
     * sport's Moneyline game(s) today — so the model gets the stats signal it needs for an ENTIRE
     * slate up front, in this same tool call, instead of calling GetTeamRecentFormTool once per team.
     * That doesn't scale: a single College Football Saturday can have 90+ games / 180+ teams, far more
     * than the agentic loop's iteration budget could otherwise cover one team at a time — which
     * previously led to the model only deeply researching a handful of "high-profile" games and
     * giving up on the rest of the slate. Team names are parsed from each game's "{Team A} vs
     * {Team B}" title, which Kalshi provides consistently at the event level across every sport this
     * app covers (confirmed live across all 5 target sports).
     */
    private List<TeamScoringTrend> fetchTeamScoringTrends(List<TeamLookup> lookups) {
        return lookups.stream().distinct().toList().parallelStream()
                .map(lk -> {
                    try {
                        return sportsAnalyticsService.getTeamScoringTrend(
                                lk.espnSport(), lk.espnLeague(), lk.teamName(), BOARD_TREND_LOOKBACK_GAMES);
                    } catch (RuntimeException e) {
                        log.warn("Sport board: scoring trend for {} ({}/{}) failed: {}",
                                lk.teamName(), lk.espnSport(), lk.espnLeague(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Bulk-fetches recent game-log stats for every distinct player named in a player-prop market
     * label today — so the model's prop research actually covers the whole slate instead of whichever
     * few players it happens to already recognize (confirmed in production: asked to find player prop
     * plays, it checked 2-3 recognizable stars and stopped, even though dozens more had active props).
     * Grouped by (sport, league) since a combined key like "SOCCER" spans 6 different ESPN league
     * codes, each needing its own roster pool to resolve against — see
     * {@link SportsAnalyticsService#getPlayerRecentStatsBulk}.
     */
    private List<PlayerRecentStats> fetchPlayerRecentStatsBulk(List<TeamLookup> teamLookups,
                                                                List<PlayerLookup> playerLookups) {
        if (playerLookups.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> teamNamesByLeague = new LinkedHashMap<>();
        for (TeamLookup t : teamLookups) {
            teamNamesByLeague.computeIfAbsent(t.espnSport() + "|" + t.espnLeague(), k -> new ArrayList<>())
                    .add(t.teamName());
        }
        Map<String, List<String>> playerNamesByLeague = new LinkedHashMap<>();
        for (PlayerLookup p : playerLookups) {
            List<String> names = playerNamesByLeague.computeIfAbsent(
                    p.espnSport() + "|" + p.espnLeague(), k -> new ArrayList<>());
            if (!names.contains(p.playerName()) && names.size() < BOARD_MAX_PLAYER_LOOKUPS) {
                names.add(p.playerName());
            }
        }

        List<PlayerRecentStats> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : playerNamesByLeague.entrySet()) {
            String[] sportLeague = entry.getKey().split("\\|", 2);
            List<String> teamNames = teamNamesByLeague.getOrDefault(entry.getKey(), List.of());
            if (teamNames.isEmpty()) {
                continue;
            }
            try {
                result.addAll(sportsAnalyticsService.getPlayerRecentStatsBulk(
                        sportLeague[0], sportLeague[1], teamNames, entry.getValue(), BOARD_PLAYER_LOOKBACK_GAMES));
            } catch (RuntimeException e) {
                log.warn("Sport board: bulk player stats for {} failed: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    /** Kalshi's event {@code title} is consistently "{Team A} vs {Team B}" across every sport this
     *  app covers — returns both names, or an empty list if the title doesn't match that shape. */
    private static List<String> splitTeams(String title) {
        if (title == null || !title.contains(" vs ")) {
            return List.of();
        }
        String[] parts = title.split(" vs ", 2);
        return List.of(parts[0].trim(), parts[1].trim());
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
