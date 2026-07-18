package com.kalshi.betting.web.dto;

import java.util.List;

public record SportSummary(String seriesTicker, String title, String frequency, List<String> tags) {
}
