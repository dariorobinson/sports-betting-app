package com.kalshi.betting.mcp;

import com.kalshi.betting.service.SportsCatalogService;
import com.kalshi.betting.web.dto.GameSummary;
import com.kalshi.betting.web.dto.OrderbookView;
import com.kalshi.betting.web.dto.SportSummary;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** MCP tools for browsing Kalshi's public sports catalog — no Kalshi credentials required. */
@Component
public class SportsMcpTools {

    private final SportsCatalogService catalogService;

    public SportsMcpTools(SportsCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @McpTool(name = "list_sports", description = "List Kalshi's sports series (e.g. NFL, NBA, "
            + "soccer leagues, tennis, golf) available to browse for games and markets.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public List<SportSummary> listSports() {
        return catalogService.listSports();
    }

    @McpTool(name = "list_games", description = "List open games/events for a sports series "
            + "ticker (from list_sports), each with its yes/no markets and live prices.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public List<GameSummary> listGames(
            @McpToolParam(required = true, description = "Series ticker, e.g. KXNBASUMMERGAME")
            String seriesTicker) {
        return catalogService.listGames(seriesTicker);
    }

    @McpTool(name = "get_game", description = "Get a single game/event by its event ticker, "
            + "including all its markets and current yes/no ask prices.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public GameSummary getGame(
            @McpToolParam(required = true, description = "Event ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL")
            String eventTicker) {
        return catalogService.getGame(eventTicker);
    }

    @McpTool(name = "get_market_orderbook", description = "Get the live order book (yes/no bid "
            + "price levels and sizes) for a single market ticker.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = true))
    public OrderbookView getMarketOrderbook(
            @McpToolParam(required = true, description = "Market ticker, e.g. KXNBASUMMERGAME-26JUL18BOSORL-BOS")
            String marketTicker,
            @McpToolParam(required = false, description = "Depth of levels to return; 0 or omitted means all levels")
            Integer depth) {
        return catalogService.getOrderbook(marketTicker, depth == null ? 0 : depth);
    }
}
