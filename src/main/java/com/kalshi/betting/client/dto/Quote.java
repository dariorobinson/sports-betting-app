package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A market maker's response to an RFQ — yes/no are the price the quoter is bidding to BUY that
 *  side, so from the RFQ creator's perspective the price to BUY yes is (1 - noBidDollars) and vice
 *  versa (Kalshi's yes+no prices always sum to $1). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Quote(
        String id,
        String rfqId,
        String marketTicker,
        String contractsFp,
        String yesBidDollars,
        String noBidDollars,
        String status
) {
}
