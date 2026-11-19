package com.kalshi.betting.scheduler;

import com.kalshi.betting.discord.DiscordMessages;
import com.kalshi.betting.orchestrator.OrchestratorService;
import com.kalshi.betting.service.SportsCatalogService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daily, fully autonomous "value play" research report — this app's entire remaining purpose. Does
 * NOT place any bet; it DMs a list of recommended plays with the stats behind each one, backed by
 * real research (ESPN analytics + server-side web search, see {@link OrchestratorService}), so the
 * user can decide and bet manually.
 * <p>
 * Runs one independent {@link OrchestratorService#chatOnce} call per (sport, market-category) task
 * (see {@link #CATEGORY_TEMPLATES}) rather than one giant survey across everything, or even one call
 * per sport — both of those reliably produced only partial coverage in production (a single
 * sport-wide call would research game lines OR player props, never both, leaving the user to
 * manually ask follow-up chat questions for the rest, defeating the point of an autonomous scheduled
 * report). Scoping each call to one narrow (sport, category) slice gives it a full iteration budget
 * of its own and keeps each individual research task small enough to actually exhaust.
 * <p>
 * How many actual tasks a template turns into is decided FRESH each run by {@link #buildTasksForToday(java.util.List)}
 * based on today's real game count per sport — a fixed one-task-per-category count was still not
 * enough on a busy day: confirmed in production that soccer's 14 combined competitions needed
 * splitting by competition cluster, and a follow-up chat question routinely surfaced a real play that
 * a single oversized scheduled task had missed. The same dynamic split now applies to every sport, not
 * just soccer — a quiet day still runs its normal small task count, a busy one gets more, narrower
 * sub-tasks instead of asking one task to go deep on more games than it reliably can. Every play is
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
     *  {@code assignedGames} is the exact game list this task is responsible for (the whole slate for
     *  an unsplit task, one chunk for a split one) — kept so {@link #runResearch()} can check the
     *  model's own GAMES_CHECKED confirmation against it afterward and log a warning if any assigned
     *  game went unreported, rather than just trusting the "evaluate every game" instruction blindly. */
    private record SportTask(String label, String prompt, List<String> assignedGames) {
    }

    /** One (sport key, category) template — the fixed instruction body for a research category,
     *  BEFORE deciding how many actual {@link SportTask}s it turns into for today (see
     *  {@link #buildTasksForToday}). Kept separate from {@code SportTask} itself because the same
     *  template can produce one task on a quiet day or several on a busy one. */
    private record CategoryTemplate(String sportKey, String label, String prompt) {
    }

    /** Above this many games today, a category template is split into multiple {@link SportTask}s,
     *  each scoped to a named subset of games, instead of one task trying to cover the whole slate in
     *  depth — confirmed in production that an oversized single task (first seen with soccer's 14
     *  combined competitions, up to 55 series in one call) reliably goes shallow on part of the board.
     *  8 is a starting heuristic (not an empirically measured ceiling) matched to the busiest normal
     *  night for NFL/NBA/NHL — tune it if a specific sport still comes up short at this size. */
    private static final int GAMES_PER_TASK_THRESHOLD = 8;
    /** Hard cap on how many sub-tasks one category template can split into, regardless of game count —
     *  without this, a 90+ game College Football Saturday would divide into a dozen-plus tasks at the
     *  threshold above. Busy sports instead get a few bigger chunks rather than many tiny ones. */
    private static final int MAX_SPLIT_PARTS = 4;
    /** Matches the "GAMES_CHECKED: ..." line every task is asked to append (see
     *  {@link #VALUE_METHODOLOGY}) — the one piece of structured, machine-checkable signal about
     *  which games a task's response actually covered, since the required play-list format itself
     *  deliberately says nothing about games that didn't produce a play (see
     *  {@code docs/instructions.md}'s "say nothing about it" rule), which otherwise makes coverage
     *  unverifiable from the visible report alone. */
    private static final Pattern GAMES_CHECKED_PATTERN = Pattern.compile("(?m)^GAMES_CHECKED:\\s*(.*)$");

    private static final List<CategoryTemplate> CATEGORY_TEMPLATES = List.of(
            new CategoryTemplate("NCAAF", "College Football — Game Lines", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Pull both teams' recent scoring trend (GetTeamRecentFormTool), not just \
                    their season-aggregate record."""),
            new CategoryTemplate("NCAAF", "College Football — Team Touchdowns", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate ONLY the "Team Touchdowns (game \
                    prop)" market group for today's games — ignore every other market group, those are \
                    covered by separate tasks (no individual player props exist for this sport on Kalshi, \
                    only team-level ones). Pull both teams' recent scoring/stat trend \
                    (GetTeamRecentFormTool) to back each play, not just their season-aggregate record."""),
            new CategoryTemplate("NCAAF", "College Football — Team Rushing Yards", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate ONLY the "Team Rushing Yards (game \
                    prop)" market group for today's games — ignore every other market group, those are \
                    covered by separate tasks. Pull both teams' recent scoring/stat trend \
                    (GetTeamRecentFormTool) to back each play, not just their season-aggregate record."""),
            new CategoryTemplate("NCAAF", "College Football — Team Receiving Yards", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate ONLY the "Team Receiving Yards \
                    (game prop)" market group for today's games — ignore every other market group, those \
                    are covered by separate tasks. Pull both teams' recent scoring/stat trend \
                    (GetTeamRecentFormTool) to back each play, not just their season-aggregate record."""),
            new CategoryTemplate("NCAAF", "College Football — Team Sacks", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate ONLY the "Team Sacks (game prop)" \
                    market group for today's games — ignore every other market group, those are covered \
                    by separate tasks. Pull both teams' recent scoring/stat trend (GetTeamRecentFormTool) \
                    to back each play, not just their season-aggregate record."""),
            new CategoryTemplate("NCAAF", "College Football — Team Interceptions", """
                    Call GetSportBoardTool with sport="NCAAF". Evaluate ONLY the "Team Interceptions (game \
                    prop)" market group for today's games — ignore every other market group, those are \
                    covered by separate tasks. Pull both teams' recent scoring/stat trend \
                    (GetTeamRecentFormTool) to back each play, not just their season-aggregate record."""),
            new CategoryTemplate("SOCCER", "Soccer — Game Lines", """
                    Call GetSportBoardTool with sport="SOCCER" (all 14 soccer competitions in one call). \
                    Evaluate EVERY game-line market group for today's games — Moneyline, Spread, Point \
                    Total, and Team Total (note: Team Total doesn't exist for CONCACAFNL — skip it only \
                    for that one) — not just one market type. Apply extra scrutiny to Moneyline \
                    specifically (soccer's draw outcome makes it a structurally weaker fit than a clean \
                    over/under line), but don't skip it outright if the data shows a real gap. Pull \
                    recent form (GetTeamRecentFormTool) and head-to-head for both teams."""),
            new CategoryTemplate("SOCCER", "Soccer — Anytime Goalscorer", """
                    Call GetSportBoardTool with sport="SOCCER" (all 14 soccer competitions in one call). \
                    Evaluate ONLY the Anytime Goalscorer market groups for today's games across all \
                    competitions (note: doesn't exist for UEL, UECL, CONMEBOLLIB, CONMEBOLSUD, or \
                    CONCACAFNL — just skip those) — ignore Both Teams to Score, that's covered by a \
                    separate task. Back every goalscorer prop with the player's recent scoring form."""),
            new CategoryTemplate("SOCCER", "Soccer — Both Teams to Score", """
                    Call GetSportBoardTool with sport="SOCCER" (all 14 soccer competitions in one call). \
                    Evaluate ONLY the Both Teams to Score market groups for today's games across all \
                    competitions — ignore Anytime Goalscorer, that's covered by a separate task. Back \
                    each one with both teams' recent scoring/conceding trend (GetTeamRecentFormTool)."""),
            new CategoryTemplate("NFL", "NFL — Game Lines", """
                    Call GetSportBoardTool with sport="NFL". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Back every play with standings/head-to-head/recent form \
                    (GetTeamAnalyticsTool + GetTeamRecentFormTool)."""),
            new CategoryTemplate("NFL", "NFL — Passing Yards", """
                    Call GetSportBoardTool with sport="NFL". Evaluate ONLY the "Passing Yards (player \
                    prop)" market group for today's games — ignore every other player-prop category, \
                    those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that player's last ~10 games — do not recommend one you \
                    haven't pulled recent game logs for."""),
            new CategoryTemplate("NFL", "NFL — Rushing Yards", """
                    Call GetSportBoardTool with sport="NFL". Evaluate ONLY the "Rushing Yards (player \
                    prop)" market group for today's games — ignore every other player-prop category, \
                    those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that player's last ~10 games — do not recommend one you \
                    haven't pulled recent game logs for."""),
            new CategoryTemplate("NFL", "NFL — Receptions", """
                    Call GetSportBoardTool with sport="NFL". Evaluate ONLY the "Receptions (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — do not recommend one you haven't pulled recent game \
                    logs for."""),
            new CategoryTemplate("NFL", "NFL — Receiving Yards", """
                    Call GetSportBoardTool with sport="NFL". Evaluate ONLY the "Receiving Yards (player \
                    prop)" market group for today's games — ignore every other player-prop category, \
                    those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that player's last ~10 games — do not recommend one you \
                    haven't pulled recent game logs for."""),
            new CategoryTemplate("NFL", "NFL — Passing Touchdowns", """
                    Call GetSportBoardTool with sport="NFL". Evaluate ONLY the "Passing Touchdowns \
                    (player prop)" market group for today's games — ignore every other player-prop \
                    category, those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that player's last ~10 games — do not recommend one you \
                    haven't pulled recent game logs for."""),
            new CategoryTemplate("NBA", "NBA — Game Lines", """
                    Call GetSportBoardTool with sport="NBA". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total, and Team Total — not just \
                    Moneyline. Back every play with GetTeamAnalyticsTool/GetTeamRecentFormTool."""),
            new CategoryTemplate("NBA", "NBA — Points", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Points (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NBA", "NBA — Rebounds", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Rebounds (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NBA", "NBA — Assists", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Assists (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NBA", "NBA — Steals", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Steals (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NBA", "NBA — Blocks", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Blocks (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NBA", "NBA — Threes Made", """
                    Call GetSportBoardTool with sport="NBA". Evaluate ONLY the "Threes Made (player prop)" \
                    market group for today's games — ignore every other player-prop category, those are \
                    covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool for \
                    that player's last ~10 games — state plainly how many actually cleared the specific \
                    threshold Kalshi is pricing. Do not recommend one you haven't pulled recent game logs \
                    for."""),
            new CategoryTemplate("NCAAMB", "NCAA Men's Basketball", """
                    Call GetSportBoardTool with sport="NCAAMB". Evaluate EVERY market group it returns for \
                    today's games — Moneyline, Spread, Point Total, and Team Total (no individual player \
                    props exist for this sport on Kalshi). Back every play with recent scoring form \
                    (GetTeamRecentFormTool, ESPN league slug "mens-college-basketball") — early season \
                    means limited sample sizes, so say so plainly when a team's recent-form read is thin \
                    rather than overstating confidence."""),
            new CategoryTemplate("NHL", "NHL — Game Lines", """
                    Call GetSportBoardTool with sport="NHL". Evaluate EVERY game-line market group it \
                    returns for today's games — Moneyline, Spread, Point Total (goals), and Team Total — \
                    not just Moneyline. Back every play with standings/head-to-head/recent scoring trend \
                    (GetTeamAnalyticsTool + GetTeamRecentFormTool, ESPN sport="hockey", league="nhl")."""),
            new CategoryTemplate("NHL", "NHL — Goals", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "Goals (player prop)" \
                    market group for today's games — ignore every other player/game-prop category, those \
                    are covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool \
                    for that player's last ~10 games — do not recommend one you haven't pulled recent \
                    game logs for."""),
            new CategoryTemplate("NHL", "NHL — Points", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "Points (player prop)" \
                    market group for today's games — ignore every other player/game-prop category, those \
                    are covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool \
                    for that player's last ~10 games — do not recommend one you haven't pulled recent \
                    game logs for."""),
            new CategoryTemplate("NHL", "NHL — Assists", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "Assists (player prop)" \
                    market group for today's games — ignore every other player/game-prop category, those \
                    are covered by separate tasks. Back every single prop with GetPlayerRecentStatsTool \
                    for that player's last ~10 games — do not recommend one you haven't pulled recent \
                    game logs for."""),
            new CategoryTemplate("NHL", "NHL — Goaltender Saves", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "Goaltender Saves (player \
                    prop)" market group for today's games — ignore every other player/game-prop category, \
                    those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that goaltender's last ~10 games — do not recommend one \
                    you haven't pulled recent game logs for."""),
            new CategoryTemplate("NHL", "NHL — First Goalscorer", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "First Goalscorer (player \
                    prop)" market group for today's games — ignore every other player/game-prop category, \
                    those are covered by separate tasks. Back every single prop with \
                    GetPlayerRecentStatsTool for that player's last ~10 games — do not recommend one you \
                    haven't pulled recent game logs for."""),
            new CategoryTemplate("NHL", "NHL — Goal in First 10 Minutes", """
                    Call GetSportBoardTool with sport="NHL". Evaluate ONLY the "Goal in First 10 Minutes \
                    (game prop)" market group for today's games — ignore every other player/game-prop \
                    category, those are covered by separate tasks. Back each one with both teams' recent \
                    scoring trend (GetTeamRecentFormTool).""")
    );

    /** Shared instruction appended to every sport task. Same-day-only and output-brevity rules already
     *  live in {@code docs/instructions.md} (applies to every call, not just this scheduler) — this is
     *  only the value-search framing specific to this scheduled report. */
    private static final String VALUE_METHODOLOGY = """

            Remember: this is a VALUE search, not a safe-favorite search — a play is only worth including \
            if the real stats-based probability you estimate is meaningfully better than Kalshi's own \
            implied probability for that specific line. Use web search only to fill a specific gap the \
            structured ESPN tools don't cover — prefer the structured tools first since their numbers are \
            exact.

            Only recommend a play where Kalshi's own implied probability for the side you're \
            recommending is AT LEAST 30% — this report isn't about speculative longshots. A real, \
            stats-based edge is still worth exactly as much at 35% implied as it is at 80% implied; \
            there's just no reason to surface a play the market already treats as a true longshot. If \
            every candidate you found in a category falls below that, say no plays qualify for it. \
            Within the 30%+ range, actively look harder for plays in the 50-70% implied band — that's \
            the sweet spot this report wants more of. Treat a 30-50% implied play as lower priority: \
            still worth including if it's genuinely the best thing you found for that player/game and \
            nothing in the 50-70% range covers it, but don't let a borderline 30-50% play crowd out a \
            real 50-70% opportunity you didn't look hard enough for.

            Your final answer must contain ONLY the formatted play list in docs/instructions.md's required \
            format (or one short "no plays" sentence) — nothing else, anywhere in the response. Do all \
            comparing/weighing of candidates silently and start your visible answer directly with "Plays \
            for {date}". This has leaked into real reports across multiple rounds of user feedback and is \
            not acceptable output — specifically banned: opening narration ("Now I have what I need," \
            "Let me finalize the analysis," "Key findings:," "Based on the data, let me note..."), a \
            walkthrough comparing candidates before picking winners, mid-sentence self-correction ("let me \
            restate... actually..."), a fake play entry for a market you're SKIPPING (e.g. "N/A ... \
            Skipped: no real edge"), and any closing mention of what else you checked but didn't include. \
            If you're not recommending it, it must leave zero trace in the response.

            After everything else above (the play list or the "no plays" sentence), add exactly one \
            more line, for internal logging only — it is never shown to the user, so it doesn't need to \
            read naturally, it just needs to match this format exactly: \
            "GAMES_CHECKED: {game1}; {game2}; ..." listing EVERY game from today's slate you actually \
            evaluated for this task (whether or not it produced a recommended play), using the same \
            team-matchup names given to you. This is how coverage gets verified after the fact — do not \
            list a game you didn't genuinely check.""";

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final SportsCatalogService sportsCatalogService;
    private final String authorizedUserId;

    public ValuePlayScheduler(ObjectProvider<JDA> jdaProvider, OrchestratorService orchestratorService,
                               SportsCatalogService sportsCatalogService,
                               @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.sportsCatalogService = sportsCatalogService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 30 7 * * *", zone = ZONE)
    public void runDailyReport() {
        execute();
    }

    /** Runs the full research cycle (every task in {@link #CATEGORY_TEMPLATES}) and DMs the combined
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
     * The actual research cycle — every task {@link #buildTasksForToday(java.util.List)} decides is needed today,
     * run independently and combined into one report — with NO side effect (no Discord DM). Exists as
     * its own method specifically so a tool-triggered on-demand run (see {@code RunFullSlateResearchTool})
     * can get the report text back as a normal tool result and let the already-in-progress chat turn
     * deliver it once, rather than this method ALSO pushing a separate DM (which {@link #execute()}
     * does, for the cases where there's no live chat turn to deliver through — the cron and the
     * manual REST trigger).
     */
    public String runResearch() {
        return runTasks(buildTasksForToday(CATEGORY_TEMPLATES));
    }

    /**
     * The same per-(sport, individual market/stat type) research cycle as {@link #runResearch()}, but
     * scoped to ONE sport (e.g. "NBA") instead of every sport — for a Discord chat request that asks
     * for a whole sport's plays (see {@code RunSportResearchTool}). Answering that kind of request
     * inline in one chat turn has the same under-coverage failure the scheduled report itself used to
     * have: one turn asked to cover a sport's game lines AND every individual prop type (NBA alone has
     * 6) reliably only writes up ONE of them (confirmed in production, repeatedly, for NBA Points
     * specifically) and silently never gets to the rest. Routing a whole-sport request through this
     * same multi-task machinery — instead of one unscoped GetSportBoardTool-driven turn — is the fix,
     * matching the "split further, don't just ask harder" lesson this app has already learned for
     * busy slates and for the full daily report.
     *
     * @return the combined report, or a short error message if {@code sportKey} matches no known
     *         category template (not an exception — a chat tool result, so a typo'd sport name reads
     *         as a plain reply rather than a stack trace).
     */
    public String runResearchForSport(String sportKey) {
        List<CategoryTemplate> matching = CATEGORY_TEMPLATES.stream()
                .filter(t -> t.sportKey().equalsIgnoreCase(sportKey))
                .toList();
        if (matching.isEmpty()) {
            List<String> knownSports = CATEGORY_TEMPLATES.stream()
                    .map(CategoryTemplate::sportKey).distinct().sorted().toList();
            return "Unknown sport \"" + sportKey + "\" — known sports: " + knownSports;
        }
        return runTasks(buildTasksForToday(matching));
    }

    /** Below this, a play isn't included at all — see {@link OrchestratorService#dedupeAndFilterReport}.
     *  Enforced twice: {@link #VALUE_METHODOLOGY} tells each task not to recommend one in the first
     *  place (fewer low-value plays to filter later), and this is the authoritative, deterministic
     *  backstop applied to the combined report, since a prompt-only rule isn't reliable enough to
     *  trust alone for something this mechanically checkable — the same lesson already learned for
     *  narration leakage and game/market-type coverage in this same class. */
    private static final int MIN_IMPLIED_PROBABILITY_PERCENT = 30;

    /** Runs every given task independently via {@link OrchestratorService#chatOnce}, verifying and
     *  stripping each one's GAMES_CHECKED line (see {@link #verifyAndStripGamesChecked}), combines the
     *  results into one report, then runs one final cross-task pass
     *  ({@link OrchestratorService#dedupeAndFilterReport}) to drop low-value plays and collapse
     *  duplicate plays (the same player/team recommended more than once across different sections,
     *  confirmed in production) down to the single most valuable occurrence — neither of those is
     *  visible to any one task on its own, only once everything is assembled. Shared by
     *  {@link #runResearch()} (the full daily cycle) and {@link #runResearchForSport(String)} (one
     *  sport, on demand from chat). */
    private String runTasks(List<SportTask> tasks) {
        StringBuilder report = new StringBuilder("Today's value plays\n");
        for (SportTask task : tasks) {
            log.info("Value-play research starting for {}", task.label());
            String result;
            try {
                result = orchestratorService.chatOnce("value-plays-" + task.label(),
                        task.prompt() + VALUE_METHODOLOGY, OrchestratorService.VALUE_PLAY_TOOL_CLASSES);
                result = verifyAndStripGamesChecked(task, result);
            } catch (Exception e) {
                log.error("Value-play research failed for {}", task.label(), e);
                result = "Research failed: " + e.getMessage();
            }
            log.info("Value-play research finished for {}: {} chars", task.label(), result.length());
            report.append("\n").append(task.label()).append("\n").append(result).append("\n");
        }
        return orchestratorService.dedupeAndFilterReport(report.toString(), MIN_IMPLIED_PROBABILITY_PERCENT);
    }

    /**
     * Turns each given {@link CategoryTemplate} into one or more {@link SportTask}s for TODAY
     * specifically, based on how many games that sport/competition-cluster actually has today — a
     * cheap, deterministic Java-side check ({@link SportsCatalogService#listTodaysGameMatchups}, no
     * LLM call) run BEFORE any research starts. A quiet day (at or under
     * {@link #GAMES_PER_TASK_THRESHOLD} games) gets exactly the one task it always got; a busy one
     * gets split into up to {@link #MAX_SPLIT_PARTS} sub-tasks, each told to evaluate ONLY a named
     * subset of today's games, so no single task is asked to go deep on more games than it reliably
     * can. If the game-count lookup itself fails for a sport, fall back to a single unsplit task
     * rather than losing that sport's research entirely for the day.
     */
    private List<SportTask> buildTasksForToday(List<CategoryTemplate> templates) {
        List<SportTask> tasks = new ArrayList<>();
        for (CategoryTemplate template : templates) {
            List<String> games;
            try {
                games = sportsCatalogService.listTodaysGameMatchups(template.sportKey());
            } catch (Exception e) {
                log.warn("Could not look up today's game count for {} ({}) — running it as a single "
                        + "unsplit task instead of deciding how to split it.", template.sportKey(), e.getMessage());
                tasks.add(new SportTask(template.label(), template.prompt(), List.of()));
                continue;
            }

            if (games.size() <= GAMES_PER_TASK_THRESHOLD) {
                tasks.add(new SportTask(template.label(), template.prompt(), games));
                continue;
            }

            int parts = Math.min(MAX_SPLIT_PARTS,
                    (int) Math.ceil(games.size() / (double) GAMES_PER_TASK_THRESHOLD));
            List<List<String>> chunks = partition(games, parts);
            log.info("{} has {} games today — splitting into {} sub-tasks", template.sportKey(), games.size(), chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String label = template.label() + " (" + (i + 1) + "/" + chunks.size() + ")";
                List<String> chunk = chunks.get(i);
                String scopedPrompt = template.prompt() + """


                        Today's slate for this sport/competition is unusually busy (%d games total) — \
                        this task covers ONLY these specific games, a separate task covers the rest, so \
                        focus your full depth on just these: %s.""".formatted(games.size(), String.join("; ", chunk));
                tasks.add(new SportTask(label, scopedPrompt, chunk));
            }
        }
        return tasks;
    }

    /** Splits {@code items} into {@code numParts} contiguous, roughly-equal-sized chunks (last one
     *  absorbs any remainder) — used to divide a busy sport's games across several sub-tasks. */
    private static List<List<String>> partition(List<String> items, int numParts) {
        List<List<String>> chunks = new ArrayList<>();
        int chunkSize = (int) Math.ceil(items.size() / (double) numParts);
        for (int i = 0; i < items.size(); i += chunkSize) {
            chunks.add(items.subList(i, Math.min(i + chunkSize, items.size())));
        }
        return chunks;
    }

    /**
     * Checks the task's self-reported "GAMES_CHECKED: ..." line (see {@link #VALUE_METHODOLOGY})
     * against the games it was actually assigned, logs a warning for any assigned game that line
     * doesn't mention, and strips the line out of the text before it's returned — it's an internal
     * signal for this check, never meant to reach the Discord report. Logging only, by design: this
     * doesn't block or retry anything, it just gives visibility into whether a task's "evaluate every
     * game" instruction is actually being followed instead of trusting it blindly.
     */
    private String verifyAndStripGamesChecked(SportTask task, String result) {
        Matcher matcher = GAMES_CHECKED_PATTERN.matcher(result);
        if (!matcher.find()) {
            if (!task.assignedGames().isEmpty()) {
                log.warn("Value-play research for {} didn't report a GAMES_CHECKED line — can't verify "
                        + "it actually covered its {} assigned game(s): {}",
                        task.label(), task.assignedGames().size(), task.assignedGames());
            }
            return result;
        }

        String checkedLower = matcher.group(1).toLowerCase(Locale.ROOT);
        List<String> missed = task.assignedGames().stream()
                .filter(game -> !checkedLower.contains(game.toLowerCase(Locale.ROOT)))
                .toList();
        if (!missed.isEmpty()) {
            log.warn("Value-play research for {} did not report checking {} of its {} assigned game(s): {}",
                    task.label(), missed.size(), task.assignedGames().size(), missed);
        } else if (!task.assignedGames().isEmpty()) {
            log.info("Value-play research for {} confirmed checking all {} assigned game(s)",
                    task.label(), task.assignedGames().size());
        }

        return matcher.replaceFirst("").stripTrailing();
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for value-play report", error)),
                error -> log.error("Failed to retrieve Discord user for value-play report", error));
    }
}
