package com.kalshi.betting.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kalshi.betting.service.BettingService;
import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.service.PortfolioService;
import com.kalshi.betting.service.SportsCatalogService;
import com.kalshi.betting.sportsdata.SportsAnalyticsService;
import jakarta.validation.Validator;

/**
 * Static holder giving orchestrator tool classes access to the app's services. Tool classes are
 * instantiated directly by the Anthropic SDK's tool runner (via a no-arg constructor + Jackson
 * deserialization of their public fields from the model's tool call), not by Spring — so
 * constructor injection isn't available to them. Populated once at startup by
 * {@link OrchestratorService}'s constructor.
 * <p>
 * Uses its own plain (classic, "Jackson 2.x") {@link ObjectMapper} instance rather than a
 * Spring-managed bean — Boot 4's auto-configured ObjectMapper is the newer Jackson 3.x
 * (tools.jackson) flavor, a different class entirely, and isn't needed here since this is just
 * for serializing tool results to text for the model to read.
 */
public class ToolServices {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static SportsCatalogService sportsCatalogService;
    public static BettingService bettingService;
    public static PortfolioService portfolioService;
    public static ComboService comboService;
    public static SportsAnalyticsService sportsAnalyticsService;
    public static Validator validator;

    private ToolServices() {
    }

    /** Serializes a tool's result to JSON text for the model to read; never throws. */
    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "Error serializing result: " + e.getMessage();
        }
    }
}
