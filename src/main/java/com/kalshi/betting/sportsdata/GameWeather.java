package com.kalshi.betting.sportsdata;

/** Forecast conditions for one outdoor game, e.g. "Rain", "Snow", "Mostly sunny". {@code condition}
 *  is "Indoor/Dome" (temperatureF null) when ESPN reports no weather at all for that game — a real,
 *  common case for a roofed stadium, not a data gap. */
public record GameWeather(String game, String condition, Integer temperatureF) {
}
