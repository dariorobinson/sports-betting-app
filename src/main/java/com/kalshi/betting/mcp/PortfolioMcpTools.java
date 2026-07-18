package com.kalshi.betting.mcp;

import com.kalshi.betting.client.dto.GetBalanceResponse;
import com.kalshi.betting.client.dto.GetPositionsResponse;
import com.kalshi.betting.service.PortfolioService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
public class PortfolioMcpTools {

    private final PortfolioService portfolioService;

    public PortfolioMcpTools(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @McpTool(name = "get_balance", description = "Get available Kalshi account balance and total portfolio value.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public GetBalanceResponse getBalance() {
        return portfolioService.getBalance();
    }

    @McpTool(name = "get_positions", description = "Get current open market and event positions, "
            + "including exposure and realized P&L.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public GetPositionsResponse getPositions() {
        return portfolioService.getPositions();
    }
}
