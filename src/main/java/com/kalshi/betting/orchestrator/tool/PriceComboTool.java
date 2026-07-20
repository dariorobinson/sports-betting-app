package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.web.dto.LegSelection;
import jakarta.validation.ConstraintViolation;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@JsonClassDescription("Materialize Kalshi's REAL price for one specific set of combo legs. This "
        + "creates/looks up an actual market listing — it does NOT place an order or risk money, "
        + "but does count against Kalshi's 5,000/week combo-market-creation limit. The result's "
        + "implied payout multiple (1/price) is what a winning $1 contract returns per dollar staked.")
public class PriceComboTool implements Supplier<String> {

    @JsonPropertyDescription("Combo collection ticker, from ListSportsCombosTool")
    public String collectionTicker;

    @JsonPropertyDescription("The specific legs to combine: each needs eventTicker, marketTicker "
            + "(both from GetComboLegsTool), and side (YES or NO)")
    public List<LegSelection> legs;

    @Override
    public String get() {
        try {
            for (LegSelection leg : legs) {
                Set<ConstraintViolation<LegSelection>> violations = ToolServices.validator.validate(leg);
                if (!violations.isEmpty()) {
                    return "Invalid leg selection: " + violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .collect(Collectors.joining("; "));
                }
            }
            return ToolServices.toJson(ToolServices.comboService.priceCombo(collectionTicker, legs));
        } catch (Exception e) {
            return "Failed to price combo: " + e.getMessage();
        }
    }
}
