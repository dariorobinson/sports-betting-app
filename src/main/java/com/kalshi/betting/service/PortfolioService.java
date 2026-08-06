package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.GetBalanceResponse;
import com.kalshi.betting.client.dto.MarketPosition;
import com.kalshi.betting.web.dto.MarketPositionView;
import com.kalshi.betting.web.dto.PositionsView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final KalshiApiClient client;
    private final ActiveComboLegTracker activeComboLegTracker;

    public PortfolioService(KalshiApiClient client, ActiveComboLegTracker activeComboLegTracker) {
        this.client = client;
        this.activeComboLegTracker = activeComboLegTracker;
    }

    public GetBalanceResponse getBalance() {
        return client.getBalance();
    }

    public PositionsView getPositions() {
        var response = client.getPositions();
        Set<String> openTickers = response.marketPositions().stream()
                .map(MarketPosition::ticker)
                .collect(Collectors.toSet());
        Map<String, List<String>> activeComboLegs = activeComboLegTracker.activeLegsByMarket(openTickers);

        var marketPositions = response.marketPositions().stream()
                .map(position -> withEventTicker(position, activeComboLegs.get(position.ticker())))
                .toList();
        return new PositionsView(marketPositions, response.eventPositions());
    }

    /** Kalshi's market-position payload has no event_ticker field, only the market ticker — so it's
     *  resolved with a lookup per position. Best-effort: a failed lookup shouldn't break the whole
     *  positions call, it just leaves that one position's eventTicker null. */
    private MarketPositionView withEventTicker(MarketPosition position, List<String> underlyingLegEventTickers) {
        String eventTicker = null;
        try {
            eventTicker = client.getMarket(position.ticker()).market().eventTicker();
        } catch (Exception e) {
            log.warn("Could not resolve event ticker for market position {}: {}",
                    position.ticker(), e.getMessage());
        }
        return MarketPositionView.from(position, eventTicker, underlyingLegEventTickers);
    }
}
