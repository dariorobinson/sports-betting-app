package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.web.dto.LegSelection;
import jakarta.validation.ConstraintViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@JsonClassDescription("Get Kalshi's REAL price for one specific set of combo legs. Combo markets have "
        + "no resting order book, so this creates the market listing and submits a request-for-quote "
        + "(RFQ) to a market maker, waiting a few seconds for a response. The result's `quoted` field "
        + "is true if a market maker responded (yesAskDollars/noAskDollars are then real prices) or "
        + "false if nobody quoted it in time (not an error — just means try again later or pick a "
        + "different combination). Does NOT place an order or risk money, but does count against "
        + "Kalshi's 5,000/week combo-market-creation limit. The implied payout multiple (1/price) is "
        + "what a winning $1 contract returns per dollar staked.")
public class PriceComboTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(PriceComboTool.class);

    @JsonPropertyDescription("Combo collection ticker, from ListSportsCombosTool")
    public String collectionTicker;

    @JsonPropertyDescription("The specific legs to combine: each needs eventTicker, marketTicker "
            + "(both from GetComboLegsTool), and side (YES or NO)")
    public List<LegSelection> legs;

    @Override
    public String get() {
        log.info("Calling Kalshi: price combo collectionTicker={}, legs={}", collectionTicker, legs);
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
            String result = ToolServices.toJson(ToolServices.comboService.priceCombo(collectionTicker, legs));
            log.info("Kalshi response (price combo, collectionTicker={}): {}", collectionTicker, result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (price combo, collectionTicker={}, legs={})", collectionTicker, legs, e);
            return "Failed to price combo: " + e.getMessage();
        }
    }
}
