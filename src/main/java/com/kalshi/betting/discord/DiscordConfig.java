package com.kalshi.betting.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Only created when a bot token is actually configured — this connects to Discord's gateway
 * immediately at bean construction, so without a token the whole app would fail to start
 * otherwise. See {@link DiscordListener} for the DM/allowlist access gate.
 * <p>
 * Deliberately checked in code rather than via {@code @ConditionalOnProperty}: that annotation
 * treats a property as "present" even when it resolves to an empty string (which
 * {@code ${DISCORD_BOT_TOKEN:}} always does when unset), so it wouldn't actually skip this bean.
 */
@Configuration
public class DiscordConfig {

    private static final Logger log = LoggerFactory.getLogger(DiscordConfig.class);

    @Value("${discord.bot.token}")
    private String botToken;

    @Bean
    public JDA jda(DiscordListener discordListener) throws InterruptedException {
        if (botToken == null || botToken.isBlank()) {
            log.warn("DISCORD_BOT_TOKEN not set — the Discord bot will not start. Sports/Kalshi "
                    + "REST API still works normally.");
            return null;
        }
        return JDABuilder.createDefault(botToken)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
                .addEventListeners(discordListener)
                .build()
                .awaitReady();
    }
}
