package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.GetBalanceResponse;
import com.kalshi.betting.client.dto.GetPositionsResponse;
import org.springframework.stereotype.Service;

@Service
public class PortfolioService {

    private final KalshiApiClient client;

    public PortfolioService(KalshiApiClient client) {
        this.client = client;
    }

    public GetBalanceResponse getBalance() {
        return client.getBalance();
    }

    public GetPositionsResponse getPositions() {
        return client.getPositions();
    }
}
