package com.kalshi.betting.orchestrator.tool;

import com.kalshi.betting.orchestrator.ToolServices;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Use this ONLY when the user asks for ALL/EVERY possible play across every "
        + "sport today, with no specific sport mentioned (e.g. 'give me all possible plays for today', "
        + "'what's good today', 'send me everything'). A request scoped to ONE whole sport ('NBA "
        + "plays', 'NFL player props') should use RunSportResearchTool instead — same underlying "
        + "problem (one turn can't reliably cover every market/stat type), just scoped to one sport "
        + "rather than all of them. Only a request narrower than a whole sport — a specific team "
        + "('what about the Lakers tonight'), a specific player's prop, or a specific single game — "
        + "should still be researched directly yourself with GetSportBoardTool etc. Trying to "
        + "research every sport and every market category yourself in one pass is NOT reliable — "
        + "confirmed in production, a single turn asked for 'everything' only actually researched "
        + "one category (NBA player props) and gave every other sport/category a token pass before "
        + "concluding 'no plays'. This tool instead runs the exact same battle-tested cycle as the "
        + "daily scheduled report: one independent, fully-resourced research pass per sport AND "
        + "market category (30 on a normal day — one per individual market/stat type, not just per "
        + "sport/category, automatically more if a sport's slate is unusually "
        + "busy that day), then combines everything into one report. It takes a while (longer than a "
        + "normal chat reply) — that's expected and correct for genuinely exhaustive multi-sport "
        + "research, not a problem to work around. Returns the complete, already-formatted report "
        + "text; present it to the user essentially as-is rather than summarizing, re-writing, or "
        + "re-researching any part of it yourself.")
public class RunFullSlateResearchTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(RunFullSlateResearchTool.class);

    @Override
    public String get() {
        log.info("Running full multi-sport research cycle (on-demand, triggered from chat)");
        try {
            String result = ToolServices.valuePlayScheduler.getObject().runResearch();
            log.info("Full multi-sport research cycle finished: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.error("Full multi-sport research cycle failed", e);
            return "Error running full research cycle: " + e.getMessage();
        }
    }
}
