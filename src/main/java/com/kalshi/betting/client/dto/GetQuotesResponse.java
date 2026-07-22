package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetQuotesResponse(List<Quote> quotes, String cursor) {
}
