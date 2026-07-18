package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.EventData;
import com.kalshi.betting.web.dto.GameSummary;
import com.kalshi.betting.web.dto.OrderbookView;
import com.kalshi.betting.web.dto.SportSummary;
import org.springframework.stereotype.Service;

import java.util.List;

/** Browsing surface over Kalshi's sports series/events/markets/orderbook — all read-only, no auth required by Kalshi. */
@Service
public class SportsCatalogService {

    /** Kalshi's series category value for sports markets. */
    private static final String SPORTS_CATEGORY = "Sports";

    private final KalshiApiClient client;

    public SportsCatalogService(KalshiApiClient client) {
        this.client = client;
    }

    public List<SportSummary> listSports() {
        return client.listSeries(SPORTS_CATEGORY).series().stream()
                .map(s -> new SportSummary(s.ticker(), s.title(), s.frequency(), s.tags()))
                .toList();
    }

    /** Open games (events) for a given sport series, e.g. "NFL", "KXNBA". */
    public List<GameSummary> listGames(String seriesTicker) {
        List<EventData> events = client.listEvents(seriesTicker, "open", true).events();
        return events.stream().map(GameSummary::from).toList();
    }

    public GameSummary getGame(String eventTicker) {
        var response = client.getEvent(eventTicker, true);
        EventData event = response.event();
        // Some responses only populate the deprecated top-level `markets` field rather than nesting them.
        if ((event.markets() == null || event.markets().isEmpty()) && response.markets() != null) {
            event = new EventData(event.eventTicker(), event.seriesTicker(), event.subTitle(), event.title(),
                    event.mutuallyExclusive(), event.strikeDate(), response.markets());
        }
        return GameSummary.from(event);
    }

    public OrderbookView getOrderbook(String marketTicker, int depth) {
        return OrderbookView.from(client.getOrderbook(marketTicker, depth).orderbookFp());
    }
}
