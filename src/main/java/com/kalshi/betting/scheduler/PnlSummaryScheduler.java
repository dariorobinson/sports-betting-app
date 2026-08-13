package com.kalshi.betting.scheduler;

import com.kalshi.betting.discord.DiscordMessages;
import com.kalshi.betting.service.PnlService;
import com.kalshi.betting.web.dto.PnlBucket;
import com.kalshi.betting.web.dto.PnlReport;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * DMs human-readable realized-P&L summaries on a schedule (America/Chicago): a daily recap at 11:00pm,
 * a weekly recap Sunday 11:15pm (full Mon–Sun week), and a monthly recap on the last day of the month
 * at 11:30pm. Pure Java — the numbers come from {@link PnlService} (Kalshi settlements), so this adds
 * ZERO Anthropic cost.
 */
@Component
public class PnlSummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PnlSummaryScheduler.class);
    private static final String ZONE = "America/Chicago";

    private final ObjectProvider<JDA> jdaProvider;
    private final PnlService pnlService;
    private final String authorizedUserId;

    public PnlSummaryScheduler(ObjectProvider<JDA> jdaProvider, PnlService pnlService,
                                @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.pnlService = pnlService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 0 23 * * *", zone = ZONE)
    public void dailySummary() {
        send("📊 Daily P&L", "day", PnlReport::today);
    }

    @Scheduled(cron = "0 15 23 * * SUN", zone = ZONE)
    public void weeklySummary() {
        send("📈 Weekly P&L", "week", PnlReport::weekToDate);
    }

    @Scheduled(cron = "0 30 23 L * *", zone = ZONE)
    public void monthlySummary() {
        send("🗓️ Monthly P&L", "month", PnlReport::monthToDate);
    }

    private void send(String header, String periodWord, Function<PnlReport, PnlBucket> pick) {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.warn("Skipping {} P&L summary — Discord bot is not configured.", periodWord);
            return;
        }
        if (authorizedUserId == null || authorizedUserId.isBlank()) {
            log.warn("Skipping {} P&L summary — DISCORD_AUTHORIZED_USER_ID is not set.", periodWord);
            return;
        }
        try {
            PnlReport report = pnlService.getReport();
            notifyUser(jda, formatMessage(header, periodWord, pick.apply(report)));
        } catch (Exception e) {
            log.error("{} P&L summary failed", periodWord, e);
        }
    }

    /** Renders one bucket as a short, human-readable Discord message. */
    private static String formatMessage(String header, String periodWord, PnlBucket b) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(" — ").append(b.label()).append("\n");
        if (b.bets() == 0) {
            sb.append("No settled bets this ").append(periodWord).append(" yet.");
            return sb.toString();
        }
        sb.append("P&L for the ").append(periodWord).append(": ").append(money(b.netPnlDollars()));
        if (!"n/a".equals(b.returnPct())) {
            sb.append(" (").append(b.returnPct()).append(")");
        }
        sb.append("\n");
        sb.append("Won ").append(money(b.wonDollars())).append(" · Lost ").append(money(b.lostDollars())).append("\n");
        sb.append("Record: ").append(b.wins()).append("–").append(b.losses());
        if (!"n/a".equals(b.winRate())) {
            sb.append(" · ").append(b.winRate()).append(" win rate");
        }
        sb.append(" · ").append(money(b.stakedDollars())).append(" staked");
        return sb.toString();
    }

    /** "+3.00" -> "+$3.00", "-8.00" -> "-$8.00", "24.00" -> "$24.00". */
    private static String money(String s) {
        if (s == null || s.isBlank()) {
            return "$0.00";
        }
        if (s.startsWith("+") || s.startsWith("-")) {
            return s.charAt(0) + "$" + s.substring(1);
        }
        return "$" + s;
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for P&L summary", error)),
                error -> log.error("Failed to retrieve Discord user for P&L summary", error));
    }
}
