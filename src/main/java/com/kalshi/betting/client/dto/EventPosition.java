package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Kalshi returns more per-event bookkeeping (total cost, shares, realized P&L, fees), but this DTO
// flows straight into PositionsView and on to the model, which only needs to know an event is held
// and its exposure — the rest was pure token cost resent every agentic-loop iteration. @JsonIgnore-
// Properties means the omitted Kalshi fields are simply not deserialized.
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventPosition(
        String eventTicker,
        String eventExposureDollars
) {
}
