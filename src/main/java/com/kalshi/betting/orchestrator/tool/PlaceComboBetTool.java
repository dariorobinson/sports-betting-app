package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.web.dto.LegSelection;
import jakarta.validation.ConstraintViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@JsonClassDescription("Place a REAL combo bet for approximately targetDollars. This immediately "
        + "executes with real money — there is no confirmation step before this tool runs. Only call "
        + "this when the user (or an autonomous scheduled task) has explicitly asked for a combo bet "
        + "to actually be placed, not just priced — use PriceComboTool first to check a combo, then "
        + "only call this tool once you've decided to actually bet on it. targetDollars must be an "
        + "exact figure that was actually given to you (by the user, or in the autonomous prompt) — "
        + "never invent or compute this dollar amount yourself (e.g. don't calculate a percentage of "
        + "balance). Buys YES on the combo's own market (the legs already encode the outcome you "
        + "want, so YES means 'my whole combination hits'). The result's `status` field is `executed` "
        + "(a real bet was placed — check contracts/priceDollars/totalCostDollars for what actually "
        + "happened), `declined` (a quote came back priced/sized well outside targetDollars, so it was "
        + "deliberately skipped rather than risk overspending), or `not_filled` (no market maker "
        + "quoted this combo at all — not an error, just means nobody was available to trade it right "
        + "now).")
public class PlaceComboBetTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(PlaceComboBetTool.class);

    @JsonPropertyDescription("Combo collection ticker, from ListSportsCombosTool")
    public String collectionTicker;

    @JsonPropertyDescription("The specific legs to combine: each needs eventTicker, marketTicker "
            + "(both from GetComboLegsTool), and side (YES or NO)")
    public List<LegSelection> legs;

    @JsonPropertyDescription("The exact dollar amount to bet — must be a figure that was actually "
            + "given to you, not something you computed or estimated yourself")
    public BigDecimal targetDollars;

    @Override
    public String get() {
        log.info("Calling Kalshi: place combo bet collectionTicker={}, legs={}, targetDollars={}",
                collectionTicker, legs, targetDollars);
        try {
            for (LegSelection leg : legs) {
                Set<ConstraintViolation<LegSelection>> violations = ToolServices.validator.validate(leg);
                if (!violations.isEmpty()) {
                    String message = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining("; "));
                    log.warn("Combo leg validation failed: {}", message);
                    return "Invalid leg selection: " + message;
                }
            }
            if (targetDollars == null || targetDollars.signum() <= 0) {
                return "targetDollars must be a positive dollar amount.";
            }
            String result = ToolServices.toJson(
                    ToolServices.comboService.placeComboBet(collectionTicker, legs, targetDollars));
            log.info("Kalshi response (place combo bet, collectionTicker={}): {}", collectionTicker, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (place combo bet, collectionTicker={}, legs={}, targetDollars={})",
                    collectionTicker, legs, targetDollars, e);
            return "Failed to place combo bet: " + e.getMessage();
        }
    }
}
