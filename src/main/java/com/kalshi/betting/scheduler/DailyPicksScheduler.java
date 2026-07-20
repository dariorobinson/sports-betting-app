package com.kalshi.betting.scheduler;

import com.kalshi.betting.discord.DiscordMessages;
import com.kalshi.betting.orchestrator.OrchestratorService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Proactively DMs the authorized Discord user twice a day with recommended Kalshi sports plays.
 * Reuses the exact same {@link OrchestratorService} (and its tool-calling loop over the live
 * Kalshi API) that answers reactive Discord messages — this just initiates the conversation
 * itself instead of waiting for an incoming message.
 * <p>
 * Recommendations are Claude's own reasoning plus whatever the Kalshi tools return (current
 * listings/prices) — there's no independent statistical model behind this; it's not "phase 2"
 * probability-vs-market-price edge-finding, just an on-demand version of what you'd get asking
 * the bot directly.
 */
@Component
public class DailyPicksScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyPicksScheduler.class);

    private static final String PROMPT = """
            Give me your top recommended plays on Kalshi sports markets right now — both \
            single-leg markets and combo/parlay-compatible plays. Use your tools to see what's \
            actually available and priced right now (don't rely on memory). Recommend a handful \
            of the best single-leg plays and, separately, a couple of combo/parlay ideas if there \
            are reasonable ones available, with your reasoning and the actual current prices for \
            each. Keep it concise and readable as a Discord message.""";

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final String authorizedUserId;

    public DailyPicksScheduler(ObjectProvider<JDA> jdaProvider,
                                OrchestratorService orchestratorService,
                                @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 0 23 * * *", zone = "America/Chicago")
    public void nightlyPicks() {
        run();
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "America/Chicago")
    public void morningPicks() {
        run();
    }

    private void run() {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.warn("Skipping scheduled Kalshi picks — Discord bot is not configured (no DISCORD_BOT_TOKEN).");
            return;
        }
        if (authorizedUserId == null || authorizedUserId.isBlank()) {
            log.warn("Skipping scheduled Kalshi picks — DISCORD_AUTHORIZED_USER_ID is not set.");
            return;
        }

        log.info("Running scheduled Kalshi picks for user {}", authorizedUserId);
        String response;
        try {
            response = orchestratorService.chat(authorizedUserId, PROMPT);
            if (response == null || response.isEmpty()) {
                response = "Scheduled picks: I couldn't generate a response this time.";
            }
        } catch (Exception e) {
            log.error("Scheduled Kalshi picks failed", e);
            response = "Scheduled picks failed: " + e.getMessage();
        }

        String finalResponse = response;
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, finalResponse),
                        error -> log.error("Failed to open Discord DM channel for scheduled picks", error)),
                error -> log.error("Failed to retrieve Discord user for scheduled picks", error));
    }
}
