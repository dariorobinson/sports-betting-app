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
 * Runs one independent {@link OrchestratorService#chatOnce} call per (sport, market-category) task
 * (see {@link #SPORT_TASKS}) rather than one giant survey across everything, or even one call per
 * sport — both of those reliably produced only partial coverage in production (a single sport-wide
 * call would research game lines OR player props, never both, leaving the user to manually ask
 * follow-up chat questions for the rest, defeating the point of an autonomous scheduled report).
 * Scoping each call to one narrow (sport, category) slice gives it a full iteration budget of its
 * own and keeps each individual research task small enough to actually exhaust. Every play is
 * same-day only (see {@code docs/instructions.md}) — this is a same-day research tool, not a
 * multi-day/swing-trade one.
 */
@Component
public class ValuePlayScheduler {

    private static final Logger log = LoggerFactory.getLogger(ValuePlayScheduler.class);
    private static final String ZONE = "America/Chicago";

    /** One research task per (sport, market-category) pair — NOT one task per sport. Bundling every
     *  market category for a sport into a single prompt (the original design) reliably produced only
     *  partial coverage in production: asked once for "all NBA markets," the model would research
     *  just game lines OR just player props, never reliably both, requiring the user to manually ask
     *  follow-up chat questions to get the rest — exactly the outcome a fully autonomous scheduled
     *  report should never require. Splitting each sport's game-lines and props into separate tasks
     *  gives each one its own full tool-iteration budget and a narrow enough scope to actually
     *  exhaust, the same fix already applied once before at the sport level (see the class javadoc).
     *  Same-day only (see {@link #VALUE_METHODOLOGY}) means the pool per task is naturally bounded to
     *  a single day's slate, so each prompt asks for AS MANY plays as genuinely clear the bar rather
     *  than a fixed small count. */
    private record SportTask(String label, String prompt) {
    }

    private static final List<SportTask> SPORT_TASKS = List.of(
            new SportTask("College Football — Game Lines", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Pull both teams' recent scoring trend (GetTeamRecentFormTool), not just \
                    their season-aggregate record."""),
            new SportTask("College Football — Team Props", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate EVERY team-level game-prop market \
                    group it returns for today's games — team touchdowns, team rushing yards, team \
                    receiving yards, team sacks, team interceptions (no individual player props exist for \
                    this sport on Kalshi). Pull both teams' recent scoring/stat trend \
                    (GetTeamRecentFormTool) to back each one, not just their season-aggregate record."""),
            new SportTask("Soccer — Game Lines", """
                    Call GetSportBoardTool with sport="SOCCER" (all 6 leagues in one call). Evaluate EVERY \
                    game-line market group for today's games — Moneyline, Spread, Point Total, and Team \
                    Total — not just one market type. Apply extra scrutiny to Moneyline specifically \
                    (soccer's draw outcome makes it a structurally weaker fit than a clean over/under \
                    line), but don't skip it outright if the data shows a real gap. Pull recent form \
                    (GetTeamRecentFormTool) and head-to-head for both teams."""),
            new SportTask("Soccer — Player/Game Props", """
                    Call GetSportBoardTool with sport="SOCCER" (all 6 leagues in one call). Evaluate EVERY \
                    Anytime Goalscorer and Both Teams to Score market group for today's games across all 6 \
                    leagues. Back goalscorer props with the player's recent scoring form; back Both Teams \
                    to Score with both teams' recent scoring/conceding trend (GetTeamRecentFormTool)."""),
            new SportTask("NFL — Game Lines", """
                    Call GetSportBoardTool with sport="NFL". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Back every play with standings/head-to-head/recent form \
                    (GetTeamAnalyticsTool + GetTeamRecentFormTool)."""),
            new SportTask("NFL — Player Props", """
                    Call GetSportBoardTool with sport="NFL". Evaluate EVERY player-prop market group it \
                    returns for today's games — passing yards, rushing yards, receptions, receiving yards, \
                    passing touchdowns. Back every single player prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — do not recommend a player prop you haven't pulled \
                    recent game logs for."""),
            new SportTask("NBA — Game Lines", """
                    Call GetSportBoardTool with sport="NBA". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Back every play with GetTeamAnalyticsTool/GetTeamRecentFormTool."""),
            new SportTask("NBA — Player Props", """
                    Call GetSportBoardTool with sport="NBA". Evaluate EVERY player-prop market group it \
                    returns for today's games — points, rebounds, assists, steals, blocks, threes made. \
                    Back every single player prop with GetPlayerRecentStatsTool for that player's last ~10 \
                    games — state plainly how many actually cleared the specific threshold Kalshi is \
                    pricing. Do not recommend a player prop you haven't pulled recent game logs for."""),
            new SportTask("NCAA Men's Basketball", """
                    Call GetSportBoardTool with sport="NCAAMB". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, and Team Total (no individual player \
                    props exist for this sport on Kalshi). Back every play with recent scoring form \
                    (GetTeamRecentFormTool, ESPN league slug "mens-college-basketball") — early season \
                    means limited sample sizes, so say so plainly when a team's recent-form read is thin \
                    rather than overstating confidence."""),
            new SportTask("NHL — Game Lines", """
                    Call GetSportBoardTool with sport="NHL". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total (goals), and Team Total — \
                    not just Moneyline. Back every play with standings/head-to-head/recent scoring trend \
                    (GetTeamAnalyticsTool + GetTeamRecentFormTool, ESPN sport="hockey", league="nhl")."""),
            new SportTask("NHL — Player/Game Props", """
                    Call GetSportBoardTool with sport="NHL". Evaluate EVERY player-prop market group it \
                    returns for today's games — goals, points, assists, goaltender saves, first \
                    goalscorer — plus the "Goal in First 10 Minutes" game prop. Back every single player \
                    prop with GetPlayerRecentStatsTool for that player's last ~10 games (goaltenders too, \
                    for saves) — do not recommend a player prop you haven't pulled recent game logs for. \
                    Back the first-10-minutes game prop with both teams' recent scoring trend.""")
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

    /** Runs the full research cycle (every task in {@link #SPORT_TASKS}) and DMs the combined
     *  report; also returns the report text so a manual trigger (see {@code ValuePlayController})
     *  can show the result directly. For an on-demand run triggered FROM a Discord chat turn (see
     *  {@code RunFullSlateResearchTool}), call {@link #runResearch()} instead — that variant doesn't
     *  DM the result itself, since the normal chat response path already delivers it, and calling
     *  both would deliver the same report twice. */
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

        String message = runResearch();
        notifyUser(jda, message);
        return message;
    }

    /**
     * The actual research cycle — every task in {@link #SPORT_TASKS} run independently, combined into
     * one report — with NO side effect (no Discord DM). Exists as its own method specifically so a
     * tool-triggered on-demand run (see {@code RunFullSlateResearchTool}) can get the report text back
     * as a normal tool result and let the already-in-progress chat turn deliver it once, rather than
     * this method ALSO pushing a separate DM (which {@link #execute()} does, for the cases where
     * there's no live chat turn to deliver through — the cron and the manual REST trigger).
     */
    public String runResearch() {
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
        return report.toString();
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for value-play report", error)),
                error -> log.error("Failed to retrieve Discord user for value-play report", error));
    }
}
