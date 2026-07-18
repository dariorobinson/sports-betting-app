package com.kalshi.betting.web;

import com.kalshi.betting.service.SportsCatalogService;
import com.kalshi.betting.web.dto.GameSummary;
import com.kalshi.betting.web.dto.OrderbookView;
import com.kalshi.betting.web.dto.SportSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sports")
public class SportsController {

    private final SportsCatalogService catalogService;

    public SportsController(SportsCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<SportSummary> listSports() {
        return catalogService.listSports();
    }

    @GetMapping("/{seriesTicker}/games")
    public List<GameSummary> listGames(@PathVariable String seriesTicker) {
        return catalogService.listGames(seriesTicker);
    }

    @GetMapping("/games/{eventTicker}")
    public GameSummary getGame(@PathVariable String eventTicker) {
        return catalogService.getGame(eventTicker);
    }

    @GetMapping("/markets/{ticker}/orderbook")
    public OrderbookView getOrderbook(@PathVariable String ticker,
                                       @RequestParam(defaultValue = "0") int depth) {
        return catalogService.getOrderbook(ticker, depth);
    }
}
