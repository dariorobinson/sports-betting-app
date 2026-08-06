package com.kalshi.betting.ws.dto;

import java.util.List;

/** The outgoing command sent once the socket opens to subscribe to one or more channels. */
public record SubscribeCommand(int id, String cmd, Params params) {

    public record Params(List<String> channels) {
    }

    public static SubscribeCommand subscribe(int id, List<String> channels) {
        return new SubscribeCommand(id, "subscribe", new Params(channels));
    }
}
