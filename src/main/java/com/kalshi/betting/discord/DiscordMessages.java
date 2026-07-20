package com.kalshi.betting.discord;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/** Shared helper for sending a message that may exceed Discord's 2000-character limit. */
public final class DiscordMessages {

    private static final int DISCORD_MESSAGE_LIMIT = 2000;

    private DiscordMessages() {
    }

    public static void sendChunked(MessageChannel channel, String message) {
        if (message.length() <= DISCORD_MESSAGE_LIMIT) {
            channel.sendMessage(message).queue();
            return;
        }
        for (int i = 0; i < message.length(); i += DISCORD_MESSAGE_LIMIT) {
            channel.sendMessage(message.substring(i, Math.min(i + DISCORD_MESSAGE_LIMIT, message.length()))).queue();
        }
    }
}
