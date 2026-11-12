package com.kalshi.betting.web.dto;

import com.kalshi.betting.sportsdata.GameWeather;
import com.kalshi.betting.sportsdata.PlayerRecentStats;
import com.kalshi.betting.sportsdata.TeamScoringTrend;

import java.util.List;

/** Every game line, player prop, and game prop series for one sport, today only — see
 *  {@code SportMarketRegistry} for what's included per sport and {@code SportsCatalogService#getSportBoard}
 *  for how it's assembled. Uses {@link CompactGame}/{@link CompactMarket} rather than the full
 *  {@code GameSummary}/{@code MarketSummary} shape — a single busy slate (e.g. ~390 College Football
 *  games across 9 series on a Saturday, ~5,400 individual markets) is large enough with the full field
 *  set to exceed the model's prompt-size limit entirely; this trims every field not needed for a
 *  bulk first-pass scan without dropping a single market/line. {@code gameWeather} is only populated
 *  for outdoor sports (NFL, NCAAF, soccer) — empty for always-indoor ones (NBA, NCAAMB, NHL). */
public record SportBoard(String sport, List<MarketGroup> marketGroups, List<TeamScoringTrend> teamScoringTrends,
                          List<PlayerRecentStats> playerRecentStats, List<GameWeather> gameWeather) {

    /** One series' worth of today's games, labeled with what kind of market it is (e.g. "Moneyline",
     *  "Spread", "Passing Yards (player prop)", "Both Teams to Score (game prop)") so the model
     *  doesn't have to guess what a raw series ticker means. {@code espnSport}/{@code espnLeague} are
     *  the EXACT values to pass to GetInjuryReportTool/GetTeamRecentFormTool/GetTeamAnalyticsTool/
     *  GetPlayerRecentStatsTool for any team/player in this group's games — confirmed in production
     *  that guessing them instead is unreliable for soccer specifically, where a country's domestic
     *  club league and its national team use different ESPN league codes despite sharing a name (e.g.
     *  Spain's club league is "esp.1", but the Spain national team's games live under "uefa.nations" /
     *  "fifa.friendly" / etc., never "esp.1"). */
    public record MarketGroup(String marketType, String seriesTicker, String espnSport, String espnLeague,
                               List<CompactGame> games) {
    }

    /** One game, compact: just the title (e.g. "Alabama vs LSU", also used to resolve team names for
     *  {@link #teamScoringTrends}) and its active, tradeable markets. Omits subTitle/strikeDate/
     *  seriesTicker (redundant with the group's own seriesTicker) — call GetGameTool with
     *  {@code eventTicker} for the full GameSummary if ever needed. */
    public record CompactGame(String eventTicker, String title, List<CompactMarket> markets) {
    }
}
