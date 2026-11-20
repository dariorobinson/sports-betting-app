package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.function.Supplier;

@JsonClassDescription("Call this ONCE for EACH play you're recommending — this is how plays get "
        + "reported now, not by writing them out as text. Call it zero times if nothing clears the "
        + "bar for this task. Do not ALSO describe the play in your final text response; your text "
        + "response for this task is used only for the GAMES_CHECKED line described in your "
        + "instructions, nothing else.")
public class RecordPlayTool implements Supplier<String> {

    @JsonPropertyDescription("Short league/competition label, e.g. \"NBA\", \"NCAAF\", \"UEFA Nations League\".")
    public String league;

    @JsonPropertyDescription("The exact text to show before the dash, e.g. \"(Dallas) vs Golden State\" "
            + "for a team game-line play (parens around the side you're recommending), or just the "
            + "player's name, e.g. \"Jalen Brunson\", for a player prop.")
    public String displaySubject;

    @JsonPropertyDescription("Market type, e.g. \"Moneyline\", \"Spread\", \"Point Total\", \"Team "
            + "Total\", \"Points (player prop)\", \"Both Teams to Score\".")
    public String marketType;

    @JsonPropertyDescription("The specific line/side being recommended, e.g. \"Dallas to win\", \"Over "
            + "49.5\", \"25+ points\", \"Both Teams to Score: Yes\".")
    public String line;

    @JsonPropertyDescription("The pre-computed American odds for the recommended side exactly as given "
            + "by the tool results, e.g. \"-178\" or \"+223\" — never compute this yourself.")
    public String americanOdds;

    @JsonPropertyDescription("Kalshi's own implied probability for the recommended side, as a whole "
            + "number 0-100 (e.g. 64 for 64%) — use the pre-computed *ImpliedProbability field "
            + "verbatim, never compute it yourself.")
    public Integer impliedProbabilityPercent;

    @JsonPropertyDescription("Your own stats-based probability estimate for this play, as a whole "
            + "number 0-100.")
    public Integer estimatedProbabilityPercent;

    @JsonPropertyDescription("One short sentence of stats-driven reasoning for the gap between your "
            + "estimate and Kalshi's implied probability.")
    public String reasoning;

    @JsonPropertyDescription("The player's name if this is an individual player prop (e.g. \"Jalen "
            + "Brunson\") — leave this as an empty string for a team-level market (Moneyline/Spread/"
            + "Point Total/Team Total/Both Teams to Score). Used to make sure the same player is never "
            + "recommended twice across different prop types.")
    public String playerName;

    @Override
    public String get() {
        return "Recorded.";
    }
}
