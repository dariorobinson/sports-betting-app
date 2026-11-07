package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("THE starting point for 'find me plays in <sport>' requests. One call returns "
        + "EVERY game line (Moneyline, Spread, Point Total, Team Total), player prop, and game prop "
        + "series for that sport, for TODAY's games only — already same-day filtered, no need to "
        + "call ListGamesTool per market type yourself. Evaluate every market group returned, not "
        + "just Moneyline — the whole point of this tool is full coverage in one shot instead of "
        + "accidentally skipping Spread/Total/Team Total/props. ALSO returns teamScoringTrends: a "
        + "compact avg-points-for/against summary for EVERY team playing today in this sport's "
        + "Moneyline games (not just high-profile ones) — use that for a first-pass read on every "
        + "game on a busy slate, and only call GetTeamRecentFormTool for the full game-by-game detail "
        + "on specific games you're seriously close to recommending. ALWAYS call this fresh for a new "
        + "'find me plays' request, even if you already called it earlier in this conversation — never "
        + "reuse an old result as if it's today's, since game data and the actual current date change "
        + "between messages.")
public class GetSportBoardTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetSportBoardTool.class);

    @JsonPropertyDescription("One of: NFL, NCAAF, NBA, NCAAMB, EPL, LALIGA, BUNDESLIGA, SERIEA, "
            + "LIGUE1, MLS, or SOCCER (all 6 soccer leagues combined in one call).")
    public String sport;

    @Override
    public String get() {
        log.info("Calling Kalshi: sport board for sport={}", sport);
        try {
            String result = ToolServices.toJson(ToolServices.sportsCatalogService.getSportBoard(sport));
            log.info("Kalshi response (sport board, sport={}): {}", sport, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (sport board, sport={})", sport, e);
            return "Failed to get sport board: " + e.getMessage();
        }
    }
}
