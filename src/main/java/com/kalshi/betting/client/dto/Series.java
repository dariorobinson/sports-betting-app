package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Relies on the global snake_case Jackson naming strategy (see application.yml) to map JSON fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Series(
        String ticker,
        String frequency,
        String title,
        String category,
        List<String> tags
) {
}
