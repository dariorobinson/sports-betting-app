package com.kalshi.betting.mcp;

import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.web.dto.ComboCollectionSummary;
import com.kalshi.betting.web.dto.ComboLegsResponse;
import com.kalshi.betting.web.dto.ComboPriceResponse;
import com.kalshi.betting.web.dto.LegSelection;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Kalshi's combo ("multivariate event collection") markets. Estimating win
 * probability and deciding which combinations are worth pricing is judgment work for whatever
 * agent is calling these tools — see the sports-stats-analyst agent — not something computed here.
 */
@Component
public class ComboMcpTools {

    private final ComboService comboService;
    private final Validator validator;

    public ComboMcpTools(ComboService comboService, Validator validator) {
        this.comboService = comboService;
        this.validator = validator;
    }

    @McpTool(name = "list_sports_combos", description = "List open Kalshi combo collections "
            + "that include at least one sports leg. Kalshi files these as 'Exotics' regardless "
            + "of what they combine, so this checks the actual legs, not the collection's category.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public List<ComboCollectionSummary> listSportsCombos() {
        return comboService.listSportsCombos();
    }

    @McpTool(name = "get_combo_legs", description = "Get the legs (games/props) available to "
            + "pick from within a combo collection, with live prices. Collections can have "
            + "hundreds of legs across many series — if there are too many to resolve at once, "
            + "this returns a count of legs per series instead; pass seriesTicker to drill into "
            + "one of those series and get actual resolved games/markets/prices.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public ComboLegsResponse getComboLegs(
            @McpToolParam(required = true, description = "Combo collection ticker, from list_sports_combos")
            String collectionTicker,
            @McpToolParam(required = false, description = "Series ticker to filter legs down to, e.g. KXNBASUMMERGAME")
            String seriesTicker
    ) {
        return comboService.getComboLegs(collectionTicker, seriesTicker);
    }

    @McpTool(name = "price_combo", description = "Materialize Kalshi's REAL price for one "
            + "specific set of combo legs. This creates/looks up an actual market listing — it "
            + "does NOT place an order or risk money, but does count against Kalshi's 5,000/week "
            + "combo-market-creation limit. The result's implied payout multiple (1/price) is "
            + "what a winning $1 contract returns per dollar staked.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public ComboPriceResponse priceCombo(
            @McpToolParam(required = true, description = "Combo collection ticker, from list_sports_combos")
            String collectionTicker,
            @McpToolParam(required = true, description = "The specific legs to combine: each needs "
                    + "eventTicker, marketTicker (both from get_combo_legs), and side (YES or NO)")
            List<LegSelection> legs
    ) {
        validateLegs(legs);
        return comboService.priceCombo(collectionTicker, legs);
    }

    private void validateLegs(List<LegSelection> legs) {
        for (LegSelection leg : legs) {
            Set<ConstraintViolation<LegSelection>> violations = validator.validate(leg);
            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
                throw new IllegalArgumentException("Invalid leg selection: " + message);
            }
        }
    }
}
