package com.kalshi.betting.service;

import com.kalshi.betting.client.KalshiApiClient;
import com.kalshi.betting.client.dto.CancelOrderV2Response;
import com.kalshi.betting.client.dto.CreateOrderV2Request;
import com.kalshi.betting.client.dto.GetOrdersResponse;
import com.kalshi.betting.web.dto.BetPlacedResponse;
import com.kalshi.betting.web.dto.PlaceBetRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Places and manages bets on Kalshi sports markets.
 * <p>
 * Kalshi's V2 order API only knows about the YES leg of a binary market: {@code side=bid} buys
 * YES, {@code side=ask} sells YES (which is how you take a NO position), and price is always a
 * YES-denominated price in dollars (see Kalshi's {@code BookSide} docs). This service translates
 * the bettor's natural framing — "buy NO at 60 cents" — into that representation:
 * <pre>
 *   BUY  YES @ p  -> bid @ p
 *   SELL YES @ p  -> ask @ p         (reduce_only: closes an existing YES position)
 *   BUY  NO  @ p  -> ask @ (1 - p)   (selling YES at 1-p is economically buying NO at p)
 *   SELL NO  @ p  -> bid @ (1 - p)   (reduce_only: closes an existing NO position)
 * </pre>
 */
@Service
public class BettingService {

    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.0000");
    private static final int PRICE_SCALE = 4;

    private final KalshiApiClient client;

    public BettingService(KalshiApiClient client) {
        this.client = client;
    }

    public BetPlacedResponse placeBet(PlaceBetRequest request) {
        boolean isYes = request.outcome().equalsIgnoreCase("yes");
        boolean isBuy = request.resolvedAction().equals("BUY");

        // bid == take a YES position, ask == take a NO position (see class doc)
        String bookSide = (isYes == isBuy) ? "bid" : "ask";
        boolean reduceOnly = !isBuy;

        BigDecimal enteredPrice = request.price().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal yesPrice = isYes ? enteredPrice : ONE_DOLLAR.subtract(enteredPrice);

        CreateOrderV2Request kalshiRequest = new CreateOrderV2Request(
                request.ticker(),
                UUID.randomUUID().toString(),
                bookSide,
                request.count() + ".00",
                yesPrice.toPlainString(),
                request.resolvedTimeInForce(),
                "taker_at_cross",
                Boolean.TRUE.equals(request.postOnly()),
                reduceOnly
        );

        return BetPlacedResponse.from(client.createOrder(kalshiRequest));
    }

    public CancelOrderV2Response cancelBet(String orderId, String marketTicker) {
        return client.cancelOrder(orderId, marketTicker);
    }

    public GetOrdersResponse listMyOrders(String status, String ticker) {
        return client.getOrders(status, ticker);
    }
}
