package com.kalshi.betting.mcp;

import com.kalshi.betting.client.dto.CancelOrderV2Response;
import com.kalshi.betting.client.dto.GetOrdersResponse;
import com.kalshi.betting.service.BettingService;
import com.kalshi.betting.web.dto.BetPlacedResponse;
import com.kalshi.betting.web.dto.PlaceBetRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for placing/managing bets. These move real money — bean validation on
 * {@link PlaceBetRequest} doesn't fire automatically here (MCP tool invocation is a plain
 * reflective method call, not a Spring MVC request), so it's run explicitly before delegating.
 */
@Component
public class BettingMcpTools {

    private final BettingService bettingService;
    private final Validator validator;

    public BettingMcpTools(BettingService bettingService, Validator validator) {
        this.bettingService = bettingService;
        this.validator = validator;
    }

    @McpTool(name = "place_bet", description = "Place a bet on a Kalshi sports market. Places a "
            + "REAL order with real money on the configured Kalshi environment. price is in "
            + "dollars for the given outcome (e.g. 0.55 for 55 cents); YES and NO prices for the "
            + "same market always sum to $1.00.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = true))
    public BetPlacedResponse placeBet(
            @McpToolParam(required = true, description = "Market ticker to bet on")
            String ticker,
            @McpToolParam(required = true, description = "Which outcome to bet on: YES or NO")
            String outcome,
            @McpToolParam(required = false, description = "BUY (default, open/increase a position) or SELL (close/reduce one)")
            String action,
            @McpToolParam(required = true, description = "Price of the chosen outcome in dollars, between 0.01 and 0.99")
            BigDecimal price,
            @McpToolParam(required = true, description = "Number of contracts")
            Integer count,
            @McpToolParam(required = false, description = "good_till_canceled (default), immediate_or_cancel, or fill_or_kill")
            String timeInForce
    ) {
        PlaceBetRequest request = new PlaceBetRequest(ticker, outcome, action, price, count, timeInForce, null);
        validate(request);
        return bettingService.placeBet(request);
    }

    @McpTool(name = "cancel_bet", description = "Cancel a resting (not yet filled) order by its order ID.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = true))
    public CancelOrderV2Response cancelBet(
            @McpToolParam(required = true, description = "Order ID to cancel")
            String orderId,
            @McpToolParam(required = false, description = "Market ticker the order belongs to")
            String marketTicker
    ) {
        return bettingService.cancelBet(orderId, marketTicker);
    }

    @McpTool(name = "list_my_orders", description = "List your own orders, optionally filtered by status or market ticker.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public GetOrdersResponse listMyOrders(
            @McpToolParam(required = false, description = "Filter by status: resting, canceled, or executed")
            String status,
            @McpToolParam(required = false, description = "Filter by market ticker")
            String ticker
    ) {
        return bettingService.listMyOrders(status, ticker);
    }

    private void validate(PlaceBetRequest request) {
        Set<ConstraintViolation<PlaceBetRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("Invalid bet request: " + message);
        }
    }
}
