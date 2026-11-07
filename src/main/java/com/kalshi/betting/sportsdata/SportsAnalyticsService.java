package com.kalshi.betting.sportsdata;

import com.kalshi.betting.sportsdata.espn.EspnApiClient;
import com.kalshi.betting.sportsdata.espn.EspnCoreApiClient;
import com.kalshi.betting.sportsdata.espn.EspnWebApiClient;
import com.kalshi.betting.sportsdata.espn.dto.EspnAthlete;
import com.kalshi.betting.sportsdata.espn.dto.EspnAthleteGameLogResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnCompetitionDetail;
import com.kalshi.betting.sportsdata.espn.dto.EspnEventLogResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnRankingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnRosterResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScheduleResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScoreboardResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnStandingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnTeam;
import com.kalshi.betting.sportsdata.espn.dto.EspnTeamsResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Team- and individual-sport analytics (standings/rankings and head-to-head history) sourced from
 * ESPN's public API. Team/player names are matched fuzzily (case-insensitive, e.g. "Lakers"
 * matches "Los Angeles Lakers") since callers (the model) won't always know ESPN's exact display
 * name.
 */
@Service
public class SportsAnalyticsService {

    /** How many of the athlete's most recent played matches to check for a head-to-head — bounds
     *  the number of ESPN calls (one per match, fetched concurrently) to a reasonable amount. */
    private static final int HEAD_TO_HEAD_MATCH_LOOKBACK = 25;

    /** ESPN standings return ~30-40 stats per team; the model only reasons about a handful when
     *  weighing a bet. Keep just these (matched case-insensitively on the ESPN stat `name`) so the
     *  standings payload — resent on every agentic-loop iteration — stays small. Covers the record,
     *  form, scoring, and rank signals across baseball/basketball/football/hockey/soccer. */
    private static final Set<String> RELEVANT_STANDINGS_STATS = Set.of(
            "wins", "losses", "ties", "otlosses", "draws", "gamesplayed",
            "winpercent", "leaguewinpercent", "gamesbehind", "streak",
            "pointsfor", "pointsagainst", "pointdifferential", "differential", "points",
            "playoffseed", "rank", "divisionrecord", "home", "road", "lasttengames");

    /** How many of a team's/player's most recent games {@link #getTeamRecentForm}/
     *  {@link #getPlayerRecentStats} look back through by default. */
    private static final int DEFAULT_RECENT_GAMES = 10;

    private final EspnApiClient espnApiClient;
    private final EspnCoreApiClient espnCoreApiClient;
    private final EspnWebApiClient espnWebApiClient;

    public SportsAnalyticsService(EspnApiClient espnApiClient, EspnCoreApiClient espnCoreApiClient,
                                   EspnWebApiClient espnWebApiClient) {
        this.espnApiClient = espnApiClient;
        this.espnCoreApiClient = espnCoreApiClient;
        this.espnWebApiClient = espnWebApiClient;
    }

    public TeamStanding getTeamStanding(String sport, String league, String teamName) {
        EspnStandingsResponse standings = espnApiClient.getStandings(sport, league);
        List<EspnStandingsResponse.Entry> entries = standings.children().stream()
                .flatMap(c -> c.standings().entries().stream())
                .toList();
        EspnStandingsResponse.Entry match = bestMatch(entries, EspnStandingsResponse.Entry::team, teamName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No team matching \"" + teamName + "\" found in " + sport + "/" + league));

        Map<String, String> stats = new LinkedHashMap<>();
        for (EspnStandingsResponse.Stat stat : match.stats()) {
            if (stat.name() != null && RELEVANT_STANDINGS_STATS.contains(stat.name().toLowerCase())) {
                stats.put(stat.name(), stat.displayValue());
            }
        }
        return new TeamStanding(match.team().displayName(), stats);
    }

    public HeadToHeadResult getHeadToHead(String sport, String league, String teamName, String opponentName,
                                           String season) {
        EspnTeam team = findTeam(sport, league, teamName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No team matching \"" + teamName + "\" found in " + sport + "/" + league));

        EspnScheduleResponse schedule = espnApiClient.getTeamSchedule(sport, league, team.id(), season);

        List<HeadToHeadResult.Matchup> matchups = schedule.events().stream()
                .filter(event -> !event.competitions().isEmpty())
                .map(event -> toMatchupIfAgainstOpponent(event, team.id(), opponentName))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        return new HeadToHeadResult(team.displayName(), opponentName, matchups);
    }

    private static Optional<HeadToHeadResult.Matchup> toMatchupIfAgainstOpponent(
            EspnScheduleResponse.Event event, String teamId, String opponentName) {
        List<EspnScheduleResponse.Competitor> competitors = event.competitions().get(0).competitors();
        if (competitors.size() != 2) {
            return Optional.empty();
        }
        EspnScheduleResponse.Competitor self = null;
        EspnScheduleResponse.Competitor opp = null;
        for (EspnScheduleResponse.Competitor c : competitors) {
            if (teamId.equals(c.team().id())) {
                self = c;
            } else {
                opp = c;
            }
        }
        if (self == null || opp == null || !matches(opp.team(), opponentName)) {
            return Optional.empty();
        }
        String selfScore = self.score() == null ? null : self.score().displayValue();
        String oppScore = opp.score() == null ? null : opp.score().displayValue();
        String result = self.winner() == null ? "not played yet" : (self.winner() ? "won" : "lost");
        return Optional.of(new HeadToHeadResult.Matchup(event.date(), selfScore, oppScore, result));
    }

    /** A team's last {@code lastN} PLAYED games (any opponent), newest first — scoring/result trend
     *  for totals/spreads reasoning, not just a season-aggregate stat. */
    public TeamRecentForm getTeamRecentForm(String sport, String league, String teamName, int lastN) {
        EspnTeam team = findTeam(sport, league, teamName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No team matching \"" + teamName + "\" found in " + sport + "/" + league));

        EspnScheduleResponse schedule = espnApiClient.getTeamSchedule(sport, league, team.id(), null);
        List<TeamRecentForm.Game> games = schedule.events().stream()
                .filter(event -> !event.competitions().isEmpty())
                .map(event -> toRecentGame(event, team.id()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(TeamRecentForm.Game::date).reversed())
                .limit(lastN > 0 ? lastN : DEFAULT_RECENT_GAMES)
                .toList();

        return new TeamRecentForm(team.displayName(), games);
    }

    /** Compact average-points-for/against summary over the last {@code lastN} played games — a much
     *  smaller payload than {@link #getTeamRecentForm}'s full per-game list, meant for bulk use across
     *  dozens/hundreds of teams at once (see {@code SportsCatalogService#getSportBoard}, which bulk-
     *  fetches this for every team in a sport's Moneyline games so a busy slate's worth of teams can
     *  be covered in one tool call instead of one model-driven call per team). Use
     *  {@link #getTeamRecentForm} instead when you want the full game-by-game detail for one team. */
    public TeamScoringTrend getTeamScoringTrend(String sport, String league, String teamName, int lastN) {
        TeamRecentForm form = getTeamRecentForm(sport, league, teamName, lastN);
        List<Double> pointsFor = new ArrayList<>();
        List<Double> pointsAgainst = new ArrayList<>();
        for (TeamRecentForm.Game g : form.recentGames()) {
            Double pf = parseScore(g.teamScore());
            Double pa = parseScore(g.opponentScore());
            if (pf != null) {
                pointsFor.add(pf);
            }
            if (pa != null) {
                pointsAgainst.add(pa);
            }
        }
        return new TeamScoringTrend(form.team(), average(pointsFor), average(pointsAgainst), pointsFor.size());
    }

    private static Double parseScore(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String average(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format(Locale.ROOT, "%.1f", avg);
    }

    private static Optional<TeamRecentForm.Game> toRecentGame(EspnScheduleResponse.Event event, String teamId) {
        List<EspnScheduleResponse.Competitor> competitors = event.competitions().get(0).competitors();
        if (competitors.size() != 2) {
            return Optional.empty();
        }
        EspnScheduleResponse.Competitor self = null;
        EspnScheduleResponse.Competitor opp = null;
        for (EspnScheduleResponse.Competitor c : competitors) {
            if (teamId.equals(c.team().id())) {
                self = c;
            } else {
                opp = c;
            }
        }
        if (self == null || opp == null || self.score() == null || opp.score() == null) {
            return Optional.empty(); // not played yet, or malformed
        }
        String result = self.winner() == null ? "tie/unknown" : (self.winner() ? "won" : "lost");
        return Optional.of(new TeamRecentForm.Game(event.date(), opp.team().displayName(),
                self.score().displayValue(), opp.score().displayValue(), result));
    }

    /** An NBA (or other team-sport) player's last {@code lastN} PLAYED games' box-score lines,
     *  newest first — e.g. sport="basketball", league="nba". Resolves the player to a team (via
     *  standings) and then an athlete id (via that team's roster), since ESPN has no direct
     *  "search athlete by name" endpoint. */
    public PlayerRecentStats getPlayerRecentStats(String sport, String league, String teamName,
                                                    String playerName, int lastN) {
        EspnTeam team = findTeam(sport, league, teamName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No team matching \"" + teamName + "\" found in " + sport + "/" + league));
        EspnRosterResponse roster = espnApiClient.getRoster(sport, league, team.id());
        EspnAthlete athlete = (roster.athletes() == null ? List.<EspnAthlete>of() : roster.athletes()).stream()
                .filter(a -> matches(a, playerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No player matching \"" + playerName + "\" found on " + team.displayName() + "'s roster"));

        EspnAthleteGameLogResponse log = espnWebApiClient.getAthleteGameLog(sport, league, athlete.id());
        List<String> labels = log.labels() == null ? List.of() : log.labels();
        Map<String, EspnAthleteGameLogResponse.GameMeta> eventsById =
                log.events() == null ? Map.of() : log.events();

        List<PlayerRecentStats.Game> games = (log.seasonTypes() == null ? List.<EspnAthleteGameLogResponse.SeasonType>of()
                : log.seasonTypes()).stream()
                .flatMap(st -> st.categories() == null ? List.<EspnAthleteGameLogResponse.SeasonTypeCategory>of().stream()
                        : st.categories().stream())
                .flatMap(cat -> cat.events() == null ? List.<EspnAthleteGameLogResponse.GameStatLine>of().stream()
                        : cat.events().stream())
                .map(line -> toPlayerGame(line, labels, eventsById))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(PlayerRecentStats.Game::date).reversed())
                .limit(lastN > 0 ? lastN : DEFAULT_RECENT_GAMES)
                .toList();

        return new PlayerRecentStats(athlete.bestDisplayName(), games);
    }

    private static Optional<PlayerRecentStats.Game> toPlayerGame(
            EspnAthleteGameLogResponse.GameStatLine line, List<String> labels,
            Map<String, EspnAthleteGameLogResponse.GameMeta> eventsById) {
        EspnAthleteGameLogResponse.GameMeta meta = eventsById.get(line.eventId());
        if (meta == null || line.stats() == null) {
            return Optional.empty();
        }
        Map<String, String> stats = new LinkedHashMap<>();
        for (int i = 0; i < labels.size() && i < line.stats().size(); i++) {
            stats.put(labels.get(i), line.stats().get(i));
        }
        String opponent = meta.opponent() == null ? "?" : meta.opponent().displayName();
        return Optional.of(new PlayerRecentStats.Game(meta.gameDate(), opponent, meta.gameResult(), stats));
    }

    private Optional<EspnTeam> findTeam(String sport, String league, String teamName) {
        EspnTeamsResponse response = espnApiClient.listTeams(sport, league);
        List<EspnTeam> candidates = response.sports().stream()
                .flatMap(s -> s.leagues().stream())
                .flatMap(l -> l.teams().stream())
                .map(EspnTeamsResponse.TeamWrapper::team)
                .toList();
        return bestMatch(candidates, Function.identity(), teamName);
    }

    /** Picks the best-matching item among {@code items} whose extracted team satisfies
     *  {@link #matches(EspnTeam, String)} — an exact (punctuation/case-insensitive) match on
     *  name/location/abbreviation wins outright over a looser word-overlap match; among remaining
     *  ties, prefers the shortest `location` name (the closest fit to a short/ambiguous query). This
     *  matters because several real team names collide on a single common word — e.g. "Alabama"
     *  word-matches "Alabama Crimson Tide", "Alabama State Hornets", AND "Alabama A&M Bulldogs", and
     *  silently picking whichever ESPN happens to list first previously risked resolving to the
     *  wrong team without any error. */
    private static <T> Optional<T> bestMatch(List<T> items, Function<T, EspnTeam> teamOf, String query) {
        String q = normalizeTeamQuery(query);
        return items.stream()
                .filter(item -> matches(teamOf.apply(item), query))
                .min(Comparator.<T>comparingInt(item -> isExactTeamMatch(teamOf.apply(item), q) ? 0 : 1)
                        .thenComparingInt(item -> normalize(teamOf.apply(item).location()).length()));
    }

    public PlayerRanking getPlayerRanking(String sport, String tour, String playerName) {
        EspnRankingsResponse.Rank match = findRanked(sport, tour, playerName);
        return new PlayerRanking(match.athlete().bestDisplayName(), match.current());
    }

    /**
     * Whether/how often two individual-sport players (currently tennis only) have played each
     * other, and the result of each match. Unlike team sports (one schedule call), this walks the
     * player's {@link #HEAD_TO_HEAD_MATCH_LOOKBACK} most recent played matches — ESPN's "core" API
     * only returns a $ref per match, so each one is resolved individually (concurrently, to keep
     * latency reasonable) and filtered down to ones against the given opponent.
     */
    public IndividualHeadToHeadResult getIndividualHeadToHead(String sport, String tour, String playerName,
                                                                String opponentName) {
        EspnRankingsResponse.Rank match = findRanked(sport, tour, playerName);
        String athleteId = match.athlete().id();

        EspnEventLogResponse log = espnCoreApiClient.getAthleteEventLog(sport, tour, athleteId);
        List<EspnEventLogResponse.Item> items = log.events().items() == null ? List.of() : log.events().items();

        List<IndividualHeadToHeadResult.Matchup> matchups = items.stream()
                .filter(item -> Boolean.TRUE.equals(item.played()))
                .limit(HEAD_TO_HEAD_MATCH_LOOKBACK)
                .parallel()
                .map(item -> espnCoreApiClient.getCompetition(item.competition().ref()))
                .map(comp -> toMatchupIfAgainstOpponent(comp, athleteId, opponentName))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        return new IndividualHeadToHeadResult(match.athlete().bestDisplayName(), opponentName, matchups);
    }

    private static Optional<IndividualHeadToHeadResult.Matchup> toMatchupIfAgainstOpponent(
            EspnCompetitionDetail competition, String athleteId, String opponentName) {
        List<EspnCompetitionDetail.Competitor> competitors = competition.competitors();
        if (competitors == null || competitors.size() != 2) {
            return Optional.empty();
        }
        EspnCompetitionDetail.Competitor self = null;
        EspnCompetitionDetail.Competitor opp = null;
        for (EspnCompetitionDetail.Competitor c : competitors) {
            if (athleteId.equals(c.id())) {
                self = c;
            } else {
                opp = c;
            }
        }
        if (self == null || opp == null || !containsIgnoreCase(opp.name(), opponentName.trim().toLowerCase())) {
            return Optional.empty();
        }
        String score = competition.notes() == null || competition.notes().isEmpty()
                ? null : competition.notes().get(0).text();
        String result = self.winner() == null ? "unknown" : (self.winner() ? "won" : "lost");
        return Optional.of(new IndividualHeadToHeadResult.Matchup(competition.date(), result, score));
    }

    private EspnRankingsResponse.Rank findRanked(String sport, String tour, String playerName) {
        EspnRankingsResponse rankings = espnApiClient.getRankings(sport, tour);
        return rankings.rankings().stream()
                .flatMap(r -> r.ranks().stream())
                .filter(r -> matches(r.athlete(), playerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No player matching \"" + playerName + "\" found in " + sport + "/" + tour + " rankings"));
    }

    public GolfLeaderboardPosition getGolfLeaderboardPosition(String league, String playerName) {
        EspnScoreboardResponse scoreboard = espnApiClient.getScoreboard("golf", league);
        for (EspnScoreboardResponse.Event event : scoreboard.events()) {
            for (EspnScoreboardResponse.Competition competition : event.competitions()) {
                for (EspnScoreboardResponse.Competitor competitor : competition.competitors()) {
                    if (matches(competitor.athlete(), playerName)) {
                        return new GolfLeaderboardPosition(competitor.athlete().bestDisplayName(), event.name(),
                                competitor.order(), competitor.score());
                    }
                }
            }
        }
        throw new IllegalArgumentException(
                "No player matching \"" + playerName + "\" found in current golf/" + league + " leaderboard");
    }

    /** Case-insensitive: the query appearing in any of the athlete's name fields. */
    private static boolean matches(EspnAthlete athlete, String query) {
        if (athlete == null || query == null || query.isBlank()) {
            return false;
        }
        String q = query.trim().toLowerCase();
        return containsIgnoreCase(athlete.displayName(), q)
                || containsIgnoreCase(athlete.fullName(), q)
                || containsIgnoreCase(athlete.shortName(), q)
                || containsIgnoreCase(athlete.firstName(), q)
                || containsIgnoreCase(athlete.lastName(), q)
                || containsIgnoreCase(athlete.firstName() + " " + athlete.lastName(), q);
    }

    /** Exact abbreviation match, or EVERY word of the (normalized) query appearing somewhere across
     *  the team's name fields — looser than a single whole-phrase substring match, which missed
     *  natural abbreviations like "Ohio St." against ESPN's "Ohio State Buckeyes" (the literal
     *  trailing period, and "St." vs "State", meant the old whole-string check never matched at all)
     *  or "Miss State" against "Mississippi State Bulldogs". Still requires every meaningful query
     *  word to be accounted for, not just any one of them, to avoid over-matching on a single common
     *  word alone. */
    private static boolean matches(EspnTeam team, String query) {
        if (team == null || query == null || query.isBlank()) {
            return false;
        }
        String q = normalizeTeamQuery(query);
        if (q.equalsIgnoreCase(normalize(team.abbreviation()))) {
            return true;
        }
        String haystack = normalize(team.displayName()) + " " + normalize(team.shortDisplayName())
                + " " + normalize(team.name()) + " " + normalize(team.location());
        for (String word : q.split(" ")) {
            if (!word.isBlank() && !haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** True if the normalized query is a full (not just word-overlap) match on one of the team's own
     *  name fields or its abbreviation — used to prefer "Alabama" == "Alabama" (location) over a
     *  same-word-but-longer rival like "Alabama State". */
    private static boolean isExactTeamMatch(EspnTeam team, String normalizedQuery) {
        if (team == null) {
            return false;
        }
        return normalizedQuery.equals(normalize(team.displayName()))
                || normalizedQuery.equals(normalize(team.location()))
                || normalizedQuery.equals(normalize(team.shortDisplayName()))
                || normalizedQuery.equalsIgnoreCase(team.abbreviation());
    }

    /** Lowercases, strips periods/commas (so "St." lines up with "State"'s "st" prefix, and "Ohio
     *  St." can be found inside "Ohio State Buckeyes"), and collapses whitespace. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[.,]", "").trim().replaceAll("\\s+", " ");
    }

    /** Known ESPN branding quirks where a team's commonly-used/official name shares too few words
     *  with what ESPN itself calls it for the word-overlap match above to find it at all — a
     *  genuinely different abbreviation, not just a punctuation/spacing difference (those are already
     *  handled by {@link #normalize}). Confirmed by checking ESPN's live team list directly after
     *  real production failures (e.g. "Tennessee-Martin", never resolving to ESPN's "UT Martin").
     *  Keyed by normalized query. Some teams Kalshi lists (e.g. "UT Rio Grande Valley") genuinely
     *  don't exist anywhere in ESPN's college-football data at all — no alias fixes that; it's
     *  expected to keep failing, gracefully, for that specific program. */
    private static final Map<String, String> TEAM_NAME_ALIASES = Map.ofEntries(
            Map.entry("tennessee-martin", "ut martin"),
            Map.entry("grambling state", "grambling"),
            Map.entry("grambling st", "grambling"),
            Map.entry("louisiana-monroe", "ul monroe"),
            Map.entry("louisiana monroe", "ul monroe"),
            Map.entry("southern university", "southern"),
            Map.entry("appalachian state", "app state"),
            Map.entry("appalachian st", "app state"),
            Map.entry("southeastern louisiana", "se louisiana"));

    /** {@link #normalize}, with a known alias substituted if the normalized query exactly matches
     *  one — see {@link #TEAM_NAME_ALIASES}. */
    private static String normalizeTeamQuery(String query) {
        String q = normalize(query);
        return TEAM_NAME_ALIASES.getOrDefault(q, q);
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
    }
}
