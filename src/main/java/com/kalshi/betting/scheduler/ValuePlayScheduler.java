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
 * Runs one independent {@link OrchestratorService#chatOnce} call per sport (see {@link #SPORT_TASKS})
 * rather than one giant survey across all of them — a single unbounded survey has previously
 * exhausted the whole tool-call budget before producing anything useful (see
 * {@code docs/instructions.md}'s warning about this exact failure mode). Scoping each call to one
 * sport gives it a full iteration budget of its own and keeps each individual research task small
 * enough to actually finish. Every play is same-day only (see {@code docs/instructions.md}) — this
 * is a same-day research tool, not a multi-day/swing-trade one.
 */
@Component
public class ValuePlayScheduler {

    private static final Logger log = LoggerFactory.getLogger(ValuePlayScheduler.class);
    private static final String ZONE = "America/Chicago";

    /** One research task per sport. Same-day only (see {@link #VALUE_METHODOLOGY}) means the pool per
     *  sport is naturally bounded to a single day's slate, so each prompt asks for AS MANY plays as
     *  genuinely clear the bar rather than a fixed small count — a deeper survey of a smaller (today-
     *  only) pool is the whole point of going same-day. */
    private record SportTask(String label, String prompt) {
    }

    private static final List<SportTask> SPORT_TASKS = List.of(
            new SportTask("College Football", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, Team Total, and all the team-level game \
                    props (touchdowns, rushing yards, receiving yards, sacks, interceptions) — not just \
                    Moneyline. For any total/team-total/game-prop play, pull both teams' recent scoring \
                    trend (GetTeamRecentFormTool), not just their season-aggregate record."""),
            new SportTask("Soccer", """
                    Call GetSportBoardTool with sport="SOCCER" (all 6 leagues in one call). Evaluate EVERY \
                    market group for today's games — Moneyline, Spread, Point Total, Team Total, Anytime \
                    Goalscorer, and Both Teams to Score — not just one market type. Apply extra scrutiny to \
                    Moneyline specifically (soccer's draw outcome makes it a structurally weaker fit than a \
                    clean over/under or goalscorer line), but don't skip it outright if the data shows a \
                    real gap. Pull recent form (GetTeamRecentFormTool) and head-to-head for both teams."""),
            new SportTask("NFL", """
                    Call GetSportBoardTool with sport="NFL". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, Team Total, and all the player props \
                    (passing yards, rushing yards, receptions, receiving yards, passing touchdowns) — not \
                    just Moneyline. Back game lines with standings/head-to-head/recent form \
                    (GetTeamAnalyticsTool + GetTeamRecentFormTool); back every player prop with \
                    GetPlayerRecentStatsTool — do not recommend a player prop you haven't pulled recent \
                    game logs for."""),
            new SportTask("NBA", """
                    Call GetSportBoardTool with sport="NBA". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, Team Total, and all the player props \
                    (points, rebounds, assists, steals, blocks, threes made) — not just Moneyline or just \
                    props. Back game lines with GetTeamAnalyticsTool/GetTeamRecentFormTool; back every \
                    player prop with GetPlayerRecentStatsTool for that player's last ~10 games — state \
                    plainly how many actually cleared the specific threshold Kalshi is pricing. Do not \
                    recommend a player prop you haven't pulled recent game logs for."""),
            new SportTask("NCAA Men's Basketball", """
                    Call GetSportBoardTool with sport="NCAAMB". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, and Team Total (no individual player \
                    props exist for this sport on Kalshi). Back every play with recent scoring form \
                    (GetTeamRecentFormTool, ESPN league slug "mens-college-basketball") — early season \
                    means limited sample sizes, so say so plainly when a team's recent-form read is thin \
                    rather than overstating confidence.""")
    );

    /** Shared instruction appended to every sport task. Same-day-only and output-brevity rules already
     *  live in {@code docs/instructions.md} (applies to every call, not just this scheduler) — this is
     *  only the value-search framing specific to this scheduled report. */
    private static final String VALUE_METHODOLOGY = """

            Remember: this is a VALUE search, not a safe-favorite search — a play is only worth including \
            if the real stats-based probability you estimate is meaningfully better than Kalshi's own \
            implied probability for that specific line. Use web search only to fill a specific gap the \
            structured ESPN tools don't cover — prefer the structured tools first since their numbers are \
            exact.""";

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final String authorizedUserId;

    public ValuePlayScheduler(ObjectProvider<JDA> jdaProvider, OrchestratorService orchestratorService,
                               @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 30 7 * * *", zone = ZONE)
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
