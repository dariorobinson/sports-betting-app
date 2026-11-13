package com.kalshi.betting.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Builds off Spring Boot's auto-configured {@link RestClient.Builder} rather than the static
     * {@code RestClient.builder()} factory — that's the one wired with the Boot-managed Jackson
     * {@code ObjectMapper} (snake_case naming strategy, etc.); the static factory builds its own
     * default converters and would silently ignore our Jackson customization.
     * <p>
     * Explicitly builds the underlying Apache HttpClient5 with automatic retries disabled
     * ({@code disableAutomaticRetries()}). Without this, Spring Boot auto-detects HttpClient5 on
     * the classpath (pulled in transitively by the Anthropic SDK) and uses it WITH its own default
     * retry behavior, which retries a failed request at the transport layer — below
     * {@link com.kalshi.betting.client.KalshiApiClient}, invisible to and unthrottled by
     * {@link com.kalshi.betting.client.KalshiRateLimiter}. Confirmed in production: that hidden,
     * unthrottled retry let requests slip past our own rate limiting and still triggered Kalshi 429s.
     * Disabling it here makes {@code KalshiApiClient.get()}'s own retry-on-429 (which DOES go
     * through the rate limiter) the only retry path for Kalshi calls.
     */
    @Bean
    public RestClient kalshiRestClient(RestClient.Builder builder, KalshiProperties properties) {
        var requestFactory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom().disableAutomaticRetries().build());
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
