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
 * <p>
 * Each entry also carries its ESPN sport/league slug, so {@link SportsCatalogService#getSportBoard} can
 * bulk-fetch a recent-scoring-trend summary for every team in the Moneyline group's games (identified
 * by the series ticker ending in "GAME") without the model having to ask for each team one at a time —
 * essential on a busy slate (a single CFB Saturday can have 90+ games / 180+ teams, far more than the
 * agentic loop's iteration budget could otherwise cover).
 */
final class SportMarketRegistry {

    /** One series within a sport's board: a human-readable label (not a raw ticker), the ticker
     *  itself, and the ESPN sport/league slug needed to look up team stats for its games. */
    record SeriesEntry(String marketType, String seriesTicker, String espnSport, String espnLeague) {
    }

    /** (registry key, ESPN sport slug, ESPN league slug) for every sport/league this app covers. */
    private static final List<String[]> SPORT_ESPN_SLUGS = List.of(
            new String[]{"NFL", "football", "nfl"},
            new String[]{"NCAAF", "football", "college-football"},
            new String[]{"NBA", "basketball", "nba"},
            new String[]{"NCAAMB", "basketball", "mens-college-basketball"},
            new String[]{"NHL", "hockey", "nhl"},
            new String[]{"EPL", "soccer", "eng.1"},
            new String[]{"LALIGA", "soccer", "esp.1"},
            new String[]{"BUNDESLIGA", "soccer", "ger.1"},
            new String[]{"SERIEA", "soccer", "ita.1"},
            new String[]{"LIGUE1", "soccer", "fra.1"},
            new String[]{"MLS", "soccer", "usa.1"});

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
        List<SeriesEntry> allSoccer = new ArrayList<>();

        for (String[] slug : SPORT_ESPN_SLUGS) {
            String key = slug[0];
            String espnSport = slug[1];
            String espnLeague = slug[2];
            boolean isSoccer = "soccer".equals(espnSport);
            String prefix = isSoccer ? key + " " : "";
            List<SeriesEntry> entries = new ArrayList<>(List.of(
                    new SeriesEntry(prefix + "Moneyline", "KX" + key + "GAME", espnSport, espnLeague),
                    new SeriesEntry(prefix + "Spread", "KX" + key + "SPREAD", espnSport, espnLeague),
                    new SeriesEntry(prefix + "Point Total", "KX" + key + "TOTAL", espnSport, espnLeague),
                    new SeriesEntry(prefix + "Team Total", "KX" + key + "TEAMTOTAL", espnSport, espnLeague)));

            switch (key) {
                case "NFL" -> entries.addAll(List.of(
                        new SeriesEntry("Passing Yards (player prop)", "KXNFLPASSYDS", espnSport, espnLeague),
                        new SeriesEntry("Rushing Yards (player prop)", "KXNFLRSHYDS", espnSport, espnLeague),
                        new SeriesEntry("Receptions (player prop)", "KXNFLREC", espnSport, espnLeague),
                        new SeriesEntry("Receiving Yards (player prop)", "KXNFLRECYDS", espnSport, espnLeague),
                        new SeriesEntry("Passing Touchdowns (player prop)", "KXNFLPASSTDS", espnSport, espnLeague)));
                case "NCAAF" -> entries.addAll(List.of(
                        new SeriesEntry("Team Touchdowns (game prop)", "KXNCAAFTEAMTD", espnSport, espnLeague),
                        new SeriesEntry("Team Rushing Yards (game prop)", "KXNCAAFTEAMRSHYDS", espnSport, espnLeague),
                        new SeriesEntry("Team Receiving Yards (game prop)", "KXNCAAFTEAMRECYDS", espnSport, espnLeague),
                        new SeriesEntry("Team Sacks (game prop)", "KXNCAAFTEAMSACK", espnSport, espnLeague),
                        new SeriesEntry("Team Interceptions (game prop)", "KXNCAAFTEAMINT", espnSport, espnLeague)));
                case "NBA" -> entries.addAll(List.of(
                        new SeriesEntry("Points (player prop)", "KXNBAPTS", espnSport, espnLeague),
                        new SeriesEntry("Rebounds (player prop)", "KXNBAREB", espnSport, espnLeague),
                        new SeriesEntry("Assists (player prop)", "KXNBAAST", espnSport, espnLeague),
                        new SeriesEntry("Steals (player prop)", "KXNBASTL", espnSport, espnLeague),
                        new SeriesEntry("Blocks (player prop)", "KXNBABLK", espnSport, espnLeague),
                        new SeriesEntry("Threes Made (player prop)", "KXNBA3PT", espnSport, espnLeague)));
                case "NCAAMB" -> {
                    // game lines only — no individual player props exist for this sport on Kalshi
                }
                case "NHL" -> entries.addAll(List.of(
                        new SeriesEntry("Goals (player prop)", "KXNHLGOAL", espnSport, espnLeague),
                        new SeriesEntry("Points (player prop)", "KXNHLPTS", espnSport, espnLeague),
                        new SeriesEntry("Assists (player prop)", "KXNHLAST", espnSport, espnLeague),
                        new SeriesEntry("Goaltender Saves (player prop)", "KXNHLSAVE", espnSport, espnLeague),
                        new SeriesEntry("First Goalscorer (player prop)", "KXNHLFIRSTGOAL", espnSport, espnLeague),
                        new SeriesEntry("Goal in First 10 Minutes (game prop)", "KXNHLF10G", espnSport, espnLeague)));
                default -> {
                    if (isSoccer) {
                        entries.add(new SeriesEntry(key + " Anytime Goalscorer (player prop)",
                                "KX" + key + "GOAL", espnSport, espnLeague));
                        entries.add(new SeriesEntry(key + " Both Teams to Score (game prop)",
                                "KX" + key + "BTTS", espnSport, espnLeague));
                    }
                }
            }

            m.put(key, List.copyOf(entries));
            if (isSoccer) {
                allSoccer.addAll(entries);
            }
        }
        m.put("SOCCER", List.copyOf(allSoccer));

        return Map.copyOf(m);
    }
}
