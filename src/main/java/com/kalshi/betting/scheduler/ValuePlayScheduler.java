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

import java.util.List;

/**
 * Daily, fully autonomous "value play" research report — this app's entire remaining purpose. Does
 * NOT place any bet; it DMs a list of recommended plays with the stats behind each one, backed by
 * real research (ESPN analytics + server-side web search, see {@link OrchestratorService}), so the
 * user can decide and bet manually.
 * <p>
 * Runs 4 independent {@link OrchestratorService#chatOnce} calls, one per sport, rather than one
 * giant survey across all of them — a single unbounded survey has previously exhausted the whole
 * tool-call budget before producing anything useful (see {@code docs/instructions.md}'s warning
 * about this exact failure mode). Scoping each call to one sport gives it a full iteration budget of
 * its own and keeps each individual research task small enough to actually finish.
 */
@Component
public class ValuePlayScheduler {

    private static final Logger log = LoggerFactory.getLogger(ValuePlayScheduler.class);
    private static final String ZONE = "America/Chicago";

    /** One research task per sport. Each prompt asks for a SMALL number of plays so four of them add
     *  up to roughly the user's requested 10, without over-committing any single call's tool budget. */
    private record SportTask(String label, String prompt) {
    }

    private static final List<SportTask> SPORT_TASKS = List.of(
            new SportTask("College Football", """
                    Find your best 2-3 VALUE plays in College Football (Kalshi series like KXNCAAF and \
                    its Spread/Total/Team Total variants) for games over the next few days (today through \
                    the next 3-4 days — CFB mostly plays on Saturdays, so don't limit yourself to literally \
                    today). Consider Moneyline, Spread, Point Total, and Team Total markets. For any total \
                    or team-total play, pull both teams' recent scoring trend (GetTeamRecentFormTool), not \
                    just their season-aggregate record — the whole point is finding a specific line where \
                    the recent data disagrees with Kalshi's price, not just picking safe favorites."""),
            new SportTask("Soccer", """
                    Find your best 2-3 VALUE plays in Soccer (top European leagues and MLS — Kalshi series \
                    like KXEPL, KXLALIGA, KXBUNDESLIGA, KXSERIEA, KXLIGUE1, KXMLS) for games over the next \
                    few days. Consider Moneyline, Spread (goal handicap), and Point Total (goals) markets. \
                    Pull recent form (GetTeamRecentFormTool) and head-to-head for both teams — soccer totals \
                    in particular depend heavily on each team's actual recent scoring/conceding pattern, not \
                    just their league position."""),
            new SportTask("NFL", """
                    Find your best 2-3 VALUE plays in the NFL (Kalshi series like KXNFL and its \
                    Spread/Total/Team Total variants) for games over the next few days (NFL mostly plays \
                    Thu/Sun/Mon, so don't limit yourself to literally today). Consider Moneyline, Spread, \
                    Point Total, and Team Total markets, backed by both teams' standings, head-to-head, and \
                    recent scoring form (GetTeamAnalyticsTool + GetTeamRecentFormTool)."""),
            new SportTask("NBA Player Props", """
                    Find your best 2-3 VALUE PLAYER PROP plays in the NBA (Kalshi's NBA player-prop markets \
                    — points/rebounds/assists thresholds) for games over the next few days. For every \
                    candidate player prop, call GetPlayerRecentStatsTool for that player's last ~10 games \
                    before recommending anything — state plainly how many of those recent games actually \
                    cleared the specific threshold Kalshi is pricing, and compare that real hit-rate against \
                    Kalshi's implied probability. Do not recommend a player prop you haven't pulled recent \
                    game logs for.""")
    );

    /** Shared instruction appended to every sport task, since it's identical advice regardless of sport. */
    private static final String VALUE_METHODOLOGY = """

            Remember: this is a VALUE search, not a safe-favorite search — a play is only worth including \
            if the real stats-based probability you estimate is meaningfully better than Kalshi's own \
            implied probability for that specific line (or, lacking a clean edge, state plainly that \
            nothing in this sport cleared the bar rather than listing a play just to fill a quota). Use \
            web search only to fill a specific gap the structured ESPN tools don't cover (e.g. injury \
            news) — prefer the structured tools first since their numbers are exact. Follow the \
            "Required format for recommended plays" from your instructions, extended to name the market \
            type and line, and to state both Kalshi's implied probability and your own estimate side by \
            side so the value is visible at a glance. This app never places bets — these are \
            recommendations only.""";

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final String authorizedUserId;

    public ValuePlayScheduler(ObjectProvider<JDA> jdaProvider, OrchestratorService orchestratorService,
                               @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = ZONE)
    public void runDailyReport() {
        execute();
    }

    /** Runs the full 4-sport research cycle and DMs the combined report; also returns the report text
     *  so a manual trigger (see {@code ValuePlayController}) can show the result directly. */
    public String execute() {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.warn("Skipping value-play report — Discord bot is not configured.");
            return "Skipped: Discord bot is not configured.";
        }
        if (authorizedUserId == null || authorizedUserId.isBlank()) {
            log.warn("Skipping value-play report — DISCORD_AUTHORIZED_USER_ID is not set.");
            return "Skipped: DISCORD_AUTHORIZED_USER_ID is not set.";
        }

        StringBuilder report = new StringBuilder("Today's value plays\n");
        for (SportTask task : SPORT_TASKS) {
            log.info("Value-play research starting for {}", task.label());
            String result;
            try {
                result = orchestratorService.chatOnce("value-plays-" + task.label(),
                        task.prompt() + VALUE_METHODOLOGY, OrchestratorService.VALUE_PLAY_TOOL_CLASSES);
            } catch (Exception e) {
                log.error("Value-play research failed for {}", task.label(), e);
                result = "Research failed: " + e.getMessage();
            }
            log.info("Value-play research finished for {}: {} chars", task.label(), result.length());
            report.append("\n").append(task.label()).append("\n").append(result).append("\n");
        }

        String message = report.toString();
        notifyUser(jda, message);
        return message;
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for value-play report", error)),
                error -> log.error("Failed to retrieve Discord user for value-play report", error));
    }
}
