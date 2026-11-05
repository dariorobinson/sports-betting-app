package com.kalshi.betting.web.dto;

import java.util.List;

/** Every game line, player prop, and game prop series for one sport, today only — see
 *  {@code SportMarketRegistry} for what's included per sport and {@code SportsCatalogService#getSportBoard}
 *  for how it's assembled. */
public record SportBoard(String sport, List<MarketGroup> marketGroups) {

    /** One series' worth of today's games, labeled with what kind of market it is (e.g. "Moneyline",
     *  "Spread", "Passing Yards (player prop)", "Both Teams to Score (game prop)") so the model
     *  doesn't have to guess what a raw series ticker means. */
    public record MarketGroup(String marketType, String seriesTicker, List<GameSummary> games) {
    }
}
