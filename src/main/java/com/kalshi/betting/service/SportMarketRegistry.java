package com.kalshi.betting.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-curated map of sport -> every Kalshi series worth checking for a same-day value play: the 4
 * game lines (Moneyline/Spread/Point Total/Team Total), plus player props and game props where Kalshi
 * actually offers them for that sport — every ticker below was verified live against Kalshi's public
 * series list before being added here. This exists so a single {@link SportsCatalogService#getSportBoard}
 * call can hand the model every relevant market for a sport in one shot, instead of the model having to
 * individually discover and call each series (previously a real failure mode: requests for "plays in
 * X" routinely only checked Moneyline and skipped Spread/Total/Team Total/props entirely).
 * <p>
 * NOT every sport has every category — college football and NCAA Men's Basketball have no individual
 * PLAYER props on Kalshi (only team-level ones for CFB), which is a real platform fact, not a gap in
 * this registry.
 */
final class SportMarketRegistry {

    /** One series within a sport's board: a human-readable label (not a raw ticker) and the ticker itself. */
    record SeriesEntry(String marketType, String seriesTicker) {
    }

    private static final List<String> SOCCER_LEAGUE_KEYS =
            List.of("EPL", "LALIGA", "BUNDESLIGA", "SERIEA", "LIGUE1", "MLS");

    private static final Map<String, List<SeriesEntry>> BY_SPORT = buildRegistry();

    private SportMarketRegistry() {
    }

    /** The board for one sport key, or null if unknown — see {@link #knownKeys()} for valid keys. */
    static List<SeriesEntry> lookup(String sportKey) {
        return sportKey == null ? null : BY_SPORT.get(sportKey.trim().toUpperCase(java.util.Locale.ROOT));
    }

    static List<String> knownKeys() {
        return List.copyOf(BY_SPORT.keySet());
    }

    private static Map<String, List<SeriesEntry>> buildRegistry() {
        Map<String, List<SeriesEntry>> m = new LinkedHashMap<>();

        m.put("NFL", List.of(
                new SeriesEntry("Moneyline", "KXNFLGAME"),
                new SeriesEntry("Spread", "KXNFLSPREAD"),
                new SeriesEntry("Point Total", "KXNFLTOTAL"),
                new SeriesEntry("Team Total", "KXNFLTEAMTOTAL"),
                new SeriesEntry("Passing Yards (player prop)", "KXNFLPASSYDS"),
                new SeriesEntry("Rushing Yards (player prop)", "KXNFLRSHYDS"),
                new SeriesEntry("Receptions (player prop)", "KXNFLREC"),
                new SeriesEntry("Receiving Yards (player prop)", "KXNFLRECYDS"),
                new SeriesEntry("Passing Touchdowns (player prop)", "KXNFLPASSTDS")));

        m.put("NCAAF", List.of(
                new SeriesEntry("Moneyline", "KXNCAAFGAME"),
                new SeriesEntry("Spread", "KXNCAAFSPREAD"),
                new SeriesEntry("Point Total", "KXNCAAFTOTAL"),
                new SeriesEntry("Team Total", "KXNCAAFTEAMTOTAL"),
                new SeriesEntry("Team Touchdowns (game prop)", "KXNCAAFTEAMTD"),
                new SeriesEntry("Team Rushing Yards (game prop)", "KXNCAAFTEAMRSHYDS"),
                new SeriesEntry("Team Receiving Yards (game prop)", "KXNCAAFTEAMRECYDS"),
                new SeriesEntry("Team Sacks (game prop)", "KXNCAAFTEAMSACK"),
                new SeriesEntry("Team Interceptions (game prop)", "KXNCAAFTEAMINT")));

        m.put("NBA", List.of(
                new SeriesEntry("Moneyline", "KXNBAGAME"),
                new SeriesEntry("Spread", "KXNBASPREAD"),
                new SeriesEntry("Point Total", "KXNBATOTAL"),
                new SeriesEntry("Team Total", "KXNBATEAMTOTAL"),
                new SeriesEntry("Points (player prop)", "KXNBAPTS"),
                new SeriesEntry("Rebounds (player prop)", "KXNBAREB"),
                new SeriesEntry("Assists (player prop)", "KXNBAAST"),
                new SeriesEntry("Steals (player prop)", "KXNBASTL"),
                new SeriesEntry("Blocks (player prop)", "KXNBABLK"),
                new SeriesEntry("Threes Made (player prop)", "KXNBA3PT")));

        m.put("NCAAMB", List.of(
                new SeriesEntry("Moneyline", "KXNCAAMBGAME"),
                new SeriesEntry("Spread", "KXNCAAMBSPREAD"),
                new SeriesEntry("Point Total", "KXNCAAMBTOTAL"),
                new SeriesEntry("Team Total", "KXNCAAMBTEAMTOTAL")));

        List<SeriesEntry> allSoccer = new ArrayList<>();
        for (String league : SOCCER_LEAGUE_KEYS) {
            List<SeriesEntry> entries = List.of(
                    new SeriesEntry(league + " Moneyline", "KX" + league + "GAME"),
                    new SeriesEntry(league + " Spread", "KX" + league + "SPREAD"),
                    new SeriesEntry(league + " Point Total", "KX" + league + "TOTAL"),
                    new SeriesEntry(league + " Team Total", "KX" + league + "TEAMTOTAL"),
                    new SeriesEntry(league + " Anytime Goalscorer (player prop)", "KX" + league + "GOAL"),
                    new SeriesEntry(league + " Both Teams to Score (game prop)", "KX" + league + "BTTS"));
            m.put(league, entries);
            allSoccer.addAll(entries);
        }
        m.put("SOCCER", List.copyOf(allSoccer));

        return Map.copyOf(m);
    }
}
