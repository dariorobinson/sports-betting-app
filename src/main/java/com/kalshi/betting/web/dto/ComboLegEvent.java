package com.kalshi.betting.web.dto;

/** One leg available to pick from within a combo collection, with its actual current markets/prices. */
public record ComboLegEvent(
        String eventTicker,
        Boolean isYesOnly,
        Integer sizeMin,
        Integer sizeMax,
        GameSummary game
) {
}
