package com.kalshi.betting.ws.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of a "quote_executed" event on the "communications" channel — pushed the instant an
 *  RFQ quote we accepted/confirmed actually fills. executedTs is kept as a raw String (rather than
 *  a parsed timestamp type) until a real message confirms whether Kalshi sends it as an ISO string
 *  or epoch millis. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteExecutedMsg(String quoteId, String rfqId, String marketTicker, String orderId,
                                 String executedTs) {
}
