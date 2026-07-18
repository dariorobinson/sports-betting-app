package com.kalshi.betting.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GetOrdersResponse(List<Order> orders, String cursor) {
}
