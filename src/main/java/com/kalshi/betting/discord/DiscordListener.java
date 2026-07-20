package com.kalshi.betting.discord;

import com.kalshi.betting.orchestrator.OrchestratorService;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Only acts on direct messages from a single pre-authorized Discord user — everyone else,
 * including messages in servers/channels, is silently ignored. This is the entire access-control
 * boundary for a bot that can place real-money bets, so it's deliberately narrow rather than
 * relying on Discord server permissions.
 */
@Component
public class DiscordListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordListener.class);
    private static final int DISCORD_MESSAGE_LIMIT = 2000;

    private final OrchestratorService orchestratorService;
    private final String authorizedUserId;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public DiscordListener(OrchestratorService orchestratorService,
                            @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.orchestratorService = orchestratorService;
        this.authorizedUserId = authorizedUserId;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.isFromType(ChannelType.PRIVATE)) return;

        String userId = event.getAuthor().getId();
        if (authorizedUserId == null || authorizedUserId.isBlank() || !authorizedUserId.equals(userId)) {
            log.warn("Ignoring DM from unauthorized Discord user {}", userId);
            return;
        }

        String message = event.getMessage().getContentDisplay().trim();
        if (message.isEmpty()) return;

        log.info("[{}] Discord message: \"{}\"", userId, message);

        MessageChannel channel = event.getChannel();
        channel.sendTyping().queue();

        executor.submit(() -> {
            try {
                String response = orchestratorService.chat(userId, message);
                if (response == null || response.isEmpty()) {
                    response = "I couldn't generate a response. Please try again.";
                }
                sendChunked(channel, response);
            } catch (Exception e) {
                log.error("[{}] Error processing Discord message: {}", userId, e.getMessage(), e);
                channel.sendMessage("An error occurred: " + e.getMessage()).queue();
            }
        });
    }

    private void sendChunked(MessageChannel channel, String response) {
        if (response.length() <= DISCORD_MESSAGE_LIMIT) {
            channel.sendMessage(response).queue();
            return;
        }
        for (int i = 0; i < response.length(); i += DISCORD_MESSAGE_LIMIT) {
            channel.sendMessage(response.substring(i, Math.min(i + DISCORD_MESSAGE_LIMIT, response.length()))).queue();
        }
    }
}
