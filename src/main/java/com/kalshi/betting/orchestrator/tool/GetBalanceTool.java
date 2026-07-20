package com.kalshi.betting.orchestrator.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kalshi.betting.orchestrator.ToolServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

@JsonClassDescription("Get available Kalshi account balance and total portfolio value.")
public class GetBalanceTool implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(GetBalanceTool.class);

    // The Anthropic SDK's schema derivation requires at least one named property per tool, even
    // for parameter-less ones — this field isn't used by get(), just satisfies that constraint.
    @JsonPropertyDescription("Not used — this tool takes no input.")
    public String unused;

    @Override
    public String get() {
        log.info("Calling Kalshi: get balance");
        try {
            String result = ToolServices.toJson(ToolServices.portfolioService.getBalance());
            log.info("Kalshi response (get balance): {}", result);
            return result;
        } catch (Exception e) {
            log.error("Kalshi call failed (get balance)", e);
            return "Failed to get balance: " + e.getMessage();
        }
    }
}
