package com.kalshi.betting.web;

import com.kalshi.betting.client.dto.CancelOrderV2Response;
import com.kalshi.betting.client.dto.GetOrdersResponse;
import com.kalshi.betting.service.BettingService;
import com.kalshi.betting.web.dto.BetPlacedResponse;
import com.kalshi.betting.web.dto.PlaceBetRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bets")
public class BettingController {

    private final BettingService bettingService;

    public BettingController(BettingService bettingService) {
        this.bettingService = bettingService;
    }

    @PostMapping
    public BetPlacedResponse placeBet(@Valid @RequestBody PlaceBetRequest request) {
        return bettingService.placeBet(request);
    }

    @GetMapping
    public GetOrdersResponse listMyOrders(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String ticker) {
        return bettingService.listMyOrders(status, ticker);
    }

    @DeleteMapping("/{orderId}")
    public CancelOrderV2Response cancelBet(@PathVariable String orderId,
                                            @RequestParam(required = false) String marketTicker) {
        return bettingService.cancelBet(orderId, marketTicker);
    }
}
