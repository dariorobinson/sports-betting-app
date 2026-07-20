package com.kalshi.betting.sportsdata;

import com.kalshi.betting.sportsdata.espn.EspnApiClient;
import com.kalshi.betting.sportsdata.espn.dto.EspnAthlete;
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
 * Team-level sports analytics (standings/record and head-to-head history) sourced from ESPN's
 * public API. Team names are matched fuzzily (case-insensitive, e.g. "Lakers" matches "Los
 * Angeles Lakers") since callers (the model) won't always know ESPN's exact display name.
 */
@Service
public class SportsAnalyticsService {

    private final EspnApiClient espnApiClient;

    public SportsAnalyticsService(EspnApiClient espnApiClient) {
        this.espnApiClient = espnApiClient;
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
        EspnRankingsResponse rankings = espnApiClient.getRankings(sport, tour);
        EspnRankingsResponse.Rank match = rankings.rankings().stream()
                .flatMap(r -> r.ranks().stream())
                .filter(r -> matches(r.athlete(), playerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No player matching \"" + playerName + "\" found in " + sport + "/" + tour + " rankings"));

        return new PlayerRanking(match.athlete().bestDisplayName(), match.current(), match.previous(),
                match.points(), match.trend());
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
