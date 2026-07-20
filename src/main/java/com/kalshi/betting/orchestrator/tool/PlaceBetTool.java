package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.web.dto.PlaceBetRequest;
import jakarta.validation.ConstraintViolation;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@JsonClassDescription("Place a bet on a Kalshi sports market. Places a REAL order with real "
        + "money on the configured Kalshi environment — there is no confirmation step, this "
        + "executes immediately. price is in dollars for the given outcome (e.g. 0.55 for 55 "
        + "cents); YES and NO prices for the same market always sum to $1.00.")
public class PlaceBetTool implements Supplier<String> {

    @JsonPropertyDescription("Market ticker to bet on")
    public String ticker;

    @JsonPropertyDescription("Which outcome to bet on: YES or NO")
    public String outcome;

    @JsonPropertyDescription("Optional: BUY (default, open/increase a position) or SELL (close/reduce one)")
    public String action;

    @JsonPropertyDescription("Price of the chosen outcome in dollars, between 0.01 and 0.99")
    public BigDecimal price;

    @JsonPropertyDescription("Number of contracts")
    public Integer count;

    @JsonPropertyDescription("Optional: good_till_canceled (default), immediate_or_cancel, or fill_or_kill")
    public String timeInForce;

    @Override
    public String get() {
        try {
            PlaceBetRequest request = new PlaceBetRequest(ticker, outcome, action, price, count, timeInForce, null);
            Set<ConstraintViolation<PlaceBetRequest>> violations = ToolServices.validator.validate(request);
            if (!violations.isEmpty()) {
                return "Invalid bet request: " + violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
            }
            return ToolServices.toJson(ToolServices.bettingService.placeBet(request));
        } catch (Exception e) {
            return "Failed to place bet: " + e.getMessage();
        }
    }
}
