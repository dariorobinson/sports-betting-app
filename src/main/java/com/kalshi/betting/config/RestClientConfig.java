package com.kalshi.betting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Builds off Spring Boot's auto-configured {@link RestClient.Builder} rather than the static
     * {@code RestClient.builder()} factory — that's the one wired with the Boot-managed Jackson
     * {@code ObjectMapper} (snake_case naming strategy, etc.); the static factory builds its own
     * default converters and would silently ignore our Jackson customization.
     */
    @Bean
    public RestClient kalshiRestClient(RestClient.Builder builder, KalshiProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
