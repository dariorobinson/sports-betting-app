package com.kalshi.betting.web;

import com.kalshi.betting.scheduler.AutoComboBettingScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triggers for scheduled tasks — protected the same as every other {@code /api/**} endpoint
 * by {@link com.kalshi.betting.config.ApiKeyFilter} (requires {@code X-App-Api-Key} if
 * {@code app.api-key} is set) and by the app's default loopback-only network binding.
 * <p>
 * {@code POST /api/scheduler/auto-combo-betting/run} runs the exact same autonomous combo-betting
 * cycle as the {@code @Scheduled} 6-hourly job — same real-money placement, same lack of a
 * confirmation step — just on demand instead of waiting for the next 0/6/12/18h mark. Exists purely
 * to make debugging/testing that flow practical without a multi-hour wait between attempts.
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    private final AutoComboBettingScheduler autoComboBettingScheduler;

    public SchedulerController(AutoComboBettingScheduler autoComboBettingScheduler) {
        this.autoComboBettingScheduler = autoComboBettingScheduler;
    }

    @PostMapping("/auto-combo-betting/run")
    public String runAutoComboBetting() {
        return autoComboBettingScheduler.executeCycle();
    }
}
