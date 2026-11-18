package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Use this for ANY request asking for a WHOLE sport's plays (e.g. 'give me NBA "
        + "plays', 'what's good in the NHL tonight', 'any soccer plays today', 'NFL player props') — "
        + "do NOT try to research a whole sport yourself in one turn with GetSportBoardTool. Confirmed "
        + "in production, repeatedly: one turn asked to cover a sport's game lines AND every individual "
        + "prop type (NBA alone has 6 distinct prop types — points/rebounds/assists/steals/blocks/"
        + "threes) reliably only writes up ONE of them (NBA Points specifically, every time) and "
        + "silently never gets to the rest, even though the full data for every type is already sitting "
        + "in GetSportBoardTool's response. This tool instead runs the same per-individual-market-type "
        + "research cycle as the daily scheduled report, scoped to just this one sport — one "
        + "independent, fully-resourced pass per market/stat type instead of one pass trying to cover "
        + "all of them, then combines the results into one report. It takes a bit longer than a normal "
        + "chat reply — that's expected and correct for genuinely covering every prop type, not a "
        + "problem to work around. Returns the complete, already-formatted report text; present it to "
        + "the user essentially as-is rather than summarizing, re-writing, or re-researching any part "
        + "of it yourself. Still use GetSportBoardTool/GetPlayerRecentStatsTool/etc. directly yourself "
        + "for anything narrower than a whole sport — a specific team ('what about the Lakers tonight'), "
        + "a specific player's prop, or a specific single game. For a request spanning every sport, not "
        + "just one, use RunFullSlateResearchTool instead.")
public class RunSportResearchTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(RunSportResearchTool.class);

    @JsonPropertyDescription("One of: NFL, NCAAF, NBA, NCAAMB, NHL, or SOCCER.")
    public String sport;

    @Override
    public String get() {
        log.info("Running full sport research cycle for {} (on-demand, triggered from chat)", sport);
        try {
            String result = ToolServices.valuePlayScheduler.getObject().runResearchForSport(sport);
            log.info("Sport research cycle for {} finished: {} chars", sport, result.length());
            return result;
        } catch (Exception e) {
            log.error("Sport research cycle failed for {}", sport, e);
            return "Error running research for " + sport + ": " + e.getMessage();
        }
    }
}
