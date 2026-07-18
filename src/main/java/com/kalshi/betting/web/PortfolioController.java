package com.kalshi.betting.web;

import com.kalshi.betting.client.dto.GetBalanceResponse;
import com.kalshi.betting.client.dto.GetPositionsResponse;
import com.kalshi.betting.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/balance")
    public GetBalanceResponse getBalance() {
        return portfolioService.getBalance();
    }

    @GetMapping("/positions")
    public GetPositionsResponse getPositions() {
        return portfolioService.getPositions();
    }
}
