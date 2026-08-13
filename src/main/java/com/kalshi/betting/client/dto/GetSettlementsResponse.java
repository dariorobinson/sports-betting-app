package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Response of {@code GET /portfolio/settlements}. {@code cursor} is non-blank when more pages exist. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetSettlementsResponse(
        List<Settlement> settlements,
        String cursor
) {
}
