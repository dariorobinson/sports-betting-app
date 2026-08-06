package com.kalshi.betting.ws.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** The common wrapper every Kalshi WebSocket message arrives in — parsed first so {@code type} can
 *  decide which specific record {@code msg} should be converted to. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WsEnvelope(String type, Integer sid, Integer id, JsonNode msg) {
}
