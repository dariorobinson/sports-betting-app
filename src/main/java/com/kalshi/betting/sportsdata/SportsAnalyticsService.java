package com.kalshi.betting.sportsdata;

import com.kalshi.betting.sportsdata.espn.EspnApiClient;
import com.kalshi.betting.sportsdata.espn.EspnCoreApiClient;
import com.kalshi.betting.sportsdata.espn.dto.EspnAthlete;
import com.kalshi.betting.sportsdata.espn.dto.EspnCompetitionDetail;
import com.kalshi.betting.sportsdata.espn.dto.EspnEventLogResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnRankingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScheduleResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnScoreboardResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnStandingsResponse;
import com.kalshi.betting.sportsdata.espn.dto.EspnTeam;
import com.kalshi.betting.sportsdata.espn.dto.EspnTeamsResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final EspnApiClient espnApiClient;
    private final EspnCoreApiClient espnCoreApiClient;

    public SportsAnalyticsService(EspnApiClient espnApiClient, EspnCoreApiClient espnCoreApiClient) {
        this.espnApiClient = espnApiClient;
        this.espnCoreApiClient = espnCoreApiClient;
    }

    public TeamStanding getTeamStanding(String sport, String league, String teamName) {
        EspnStandingsResponse standings = espnApiClient.getStandings(sport, league);
        EspnStandingsResponse.Entry match = standings.children().stream()
                .flatMap(c -> c.standings().entries().stream())
                .filter(e -> matches(e.team(), teamName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No team matching \"" + teamName + "\" found in " + sport + "/" + league));

        Map<String, String> stats = new LinkedHashMap<>();
        for (EspnStandingsResponse.Stat stat : match.stats()) {
            stats.put(stat.name(), stat.displayValue());
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

    private Optional<EspnTeam> findTeam(String sport, String league, String teamName) {
        EspnTeamsResponse response = espnApiClient.listTeams(sport, league);
        return response.sports().stream()
                .flatMap(s -> s.leagues().stream())
                .flatMap(l -> l.teams().stream())
                .map(EspnTeamsResponse.TeamWrapper::team)
                .filter(t -> matches(t, teamName))
                .findFirst();
    }

    public PlayerRanking getPlayerRanking(String sport, String tour, String playerName) {
        EspnRankingsResponse.Rank match = findRanked(sport, tour, playerName);
        return new PlayerRanking(match.athlete().bestDisplayName(), match.current(), match.previous(),
                match.points(), match.trend());
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

    /** Case-insensitive: exact abbreviation match, or the query appearing in any of the team's name fields. */
    private static boolean matches(EspnTeam team, String query) {
        if (team == null || query == null || query.isBlank()) {
            return false;
        }
        String q = query.trim().toLowerCase();
        if (q.equalsIgnoreCase(team.abbreviation())) {
            return true;
        }
        return containsIgnoreCase(team.displayName(), q)
                || containsIgnoreCase(team.shortDisplayName(), q)
                || containsIgnoreCase(team.name(), q)
                || containsIgnoreCase(team.location(), q);
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
    }
}
