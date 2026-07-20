package com.kalshi.betting.sportsdata.espn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Deliberately uses the static {@code RestClient.builder()} factory rather than Spring's
 * auto-configured {@code RestClient.Builder} bean — that one carries this app's globally
 * snake_case Jackson naming strategy (needed for Kalshi's API), which would break parsing of
 * ESPN's natively camelCase JSON. The static factory builds its own default (unmodified) Jackson
 * config, which matches ESPN's field names directly with no special handling needed.
 */
@Configuration
public class EspnClientConfig {

    @Bean
    public RestClient espnRestClient() {
        return RestClient.builder()
                .baseUrl("https://site.api.espn.com")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
