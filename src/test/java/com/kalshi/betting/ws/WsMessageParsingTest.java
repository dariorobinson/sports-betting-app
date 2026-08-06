package com.kalshi.betting.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kalshi.betting.ws.dto.MarketPositionMsg;
import com.kalshi.betting.ws.dto.QuoteExecutedMsg;
import com.kalshi.betting.ws.dto.WsEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixtures below are the exact JSON examples published in Kalshi's own WebSocket API docs
 * (docs.kalshi.com/websockets/communications.md and .../market-positions.md), used as a stand-in
 * for real captured traffic. Per the implementation plan, Stage A (a manual live probe — see
 * {@code ManualKalshiWebSocketProbe}) should be run against production/demo credentials and its
 * captured raw frames substituted here once available, since the docs are the one thing this whole
 * feature couldn't fully confirm ahead of time (e.g. whether executed_ts is really a string).
 */
class WsMessageParsingTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private static final String QUOTE_EXECUTED_JSON = """
            {
              "type": "quote_executed",
              "sid": 15,
              "msg": {
                "quote_id": "quote_456",
                "rfq_id": "rfq_123",
                "quote_creator_id": "a1b2c3d4e5f6",
                "rfq_creator_id": "f6e5d4c3b2a1",
                "order_id": "order_789",
                "client_order_id": "my_client_order_123",
                "market_ticker": "FED-23DEC-T3.00",
                "executed_ts": "2024-12-01T10:05:00Z"
              }
            }
            """;

    private static final String MARKET_POSITION_CLOSED_JSON = """
            {
              "type": "market_position",
              "sid": 14,
              "msg": {
                "user_id": "user123",
                "market_ticker": "FED-23DEC-T3.00",
                "position_fp": "0.00",
                "position_cost_dollars": "0.0000",
                "realized_pnl_dollars": "10.0000",
                "fees_paid_dollars": "1.0000",
                "position_fee_cost_dollars": "0.5000",
                "volume_fp": "15.00"
              }
            }
            """;

    private static final String MARKET_POSITION_OPEN_JSON = """
            {
              "type": "market_position",
              "sid": 14,
              "msg": {
                "user_id": "user123",
                "market_ticker": "FED-23DEC-T3.00",
                "position_fp": "100.00",
                "position_cost_dollars": "50.0000",
                "realized_pnl_dollars": "10.0000",
                "fees_paid_dollars": "1.0000",
                "position_fee_cost_dollars": "0.5000",
                "volume_fp": "15.00"
              }
            }
            """;

    @Test
    void parsesQuoteExecutedEnvelope() throws Exception {
        WsEnvelope envelope = mapper.readValue(QUOTE_EXECUTED_JSON, WsEnvelope.class);
        assertEquals("quote_executed", envelope.type());
        assertEquals(15, envelope.sid());

        QuoteExecutedMsg msg = mapper.treeToValue(envelope.msg(), QuoteExecutedMsg.class);
        assertEquals("quote_456", msg.quoteId());
        assertEquals("rfq_123", msg.rfqId());
        assertEquals("FED-23DEC-T3.00", msg.marketTicker());
        assertEquals("order_789", msg.orderId());
        assertEquals("2024-12-01T10:05:00Z", msg.executedTs());
    }

    @Test
    void parsesClosedMarketPositionEnvelope() throws Exception {
        WsEnvelope envelope = mapper.readValue(MARKET_POSITION_CLOSED_JSON, WsEnvelope.class);
        assertEquals("market_position", envelope.type());

        MarketPositionMsg msg = mapper.treeToValue(envelope.msg(), MarketPositionMsg.class);
        assertEquals("FED-23DEC-T3.00", msg.marketTicker());
        assertEquals("0.00", msg.positionFp());
    }

    @Test
    void parsesOpenMarketPositionEnvelope() throws Exception {
        WsEnvelope envelope = mapper.readValue(MARKET_POSITION_OPEN_JSON, WsEnvelope.class);
        MarketPositionMsg msg = mapper.treeToValue(envelope.msg(), MarketPositionMsg.class);
        assertEquals("100.00", msg.positionFp());
        assertEquals("50.0000", msg.positionCostDollars());
    }
}
