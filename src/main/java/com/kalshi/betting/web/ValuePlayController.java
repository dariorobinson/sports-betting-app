package com.kalshi.betting.web;

import com.kalshi.betting.scheduler.ValuePlayScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the daily value-play report — protected the same as every other
 * {@code /api/**} endpoint by {@link com.kalshi.betting.config.ApiKeyFilter} and by the app's
 * default loopback-only network binding.
 * <p>
 * {@code POST /api/scheduler/value-plays/run} runs the exact same research-and-DM cycle as the
 * {@code @Scheduled} 9am job — never places a bet, just on demand instead of waiting for 9am.
 */
@RestController
@RequestMapping("/api/scheduler")
public class ValuePlayController {

    private final ValuePlayScheduler valuePlayScheduler;

    public ValuePlayController(ValuePlayScheduler valuePlayScheduler) {
        this.valuePlayScheduler = valuePlayScheduler;
    }

    @PostMapping("/value-plays/run")
    public String runValuePlays() {
        return valuePlayScheduler.execute();
    }
}
