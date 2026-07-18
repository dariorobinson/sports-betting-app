package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Each level is [price_dollars_string, contract_count_fp_string], best to worst.
 * Only bids are shown for each side (a yes bid at X is equivalent to a no ask at 1-X).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderbookCountFp(
        List<List<String>> yesDollars,
        List<List<String>> noDollars
) {
    public OrderbookCountFp {
        if (yesDollars == null) yesDollars = List.of();
        if (noDollars == null) noDollars = List.of();
    }
}
