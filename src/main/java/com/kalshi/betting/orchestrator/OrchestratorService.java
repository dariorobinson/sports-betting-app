package com.kalshi.betting.orchestrator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigDisabled;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.kalshi.betting.orchestrator.tool.*;
import com.kalshi.betting.scheduler.ValuePlayScheduler;
import com.kalshi.betting.service.SportsCatalogService;
import com.kalshi.betting.sportsdata.SportsAnalyticsService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs the agentic tool-calling loop for the Discord bot: takes a user message, lets Claude decide
 * which Kalshi tools to call, executes them, and returns the final text response. Per-user
 * conversation history is kept in memory only — it doesn't survive an app restart.
 * <p>
 * This hand-rolls the tool-calling loop instead of using the SDK's {@code BetaToolRunner} because
 * that helper only dispatches tools registered via {@code addTool(Class<?>)}, which hardcodes
 * {@code strict=true} — routing every tool schema through Anthropic's server-side grammar
 * compiler, which has a real complexity ceiling our tool set exceeds ("Schema is too complex for
 * compilation"). Tools here are built via {@link NonStrictTools} instead (same schema generation,
 * {@code strict=false}), and dispatched by tool name using {@link BetaToolUseBlock#input(Class)} —
 * the same public method {@code BetaToolRunner} itself uses to parse tool-call arguments.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private static final String MODEL = "claude-sonnet-5";
    private static final ZoneId USER_ZONE = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");
    /** Mandatory research (positions check, per-leg analytics, per-combo pricing, leg-reuse checks)
     *  can legitimately need a lot of tool calls now — a single ListGamesTool call for a busy series
     *  like KXNFLGAME alone can be huge. Raised from 25 after a real autonomous-betting cycle hit
     *  that cap mid-research and silently produced no response at all (see forceSynthesis below for
     *  the other half of that fix — this alone doesn't guarantee headroom is always enough). */
    private static final int MAX_TOOL_ITERATIONS = 40;
    /** Cap on persisted turns per user in {@link #histories} (one entry per user message, one per
     *  assistant reply — so 20 = last 10 exchanges). {@code histories} entries are plain messages
     *  with no cache breakpoint, so every entry gets resent at full, uncached input-token price on
     *  every future call for that user — left unbounded, this grows forever and the bill grows with
     *  it. 10 exchanges is enough conversational memory for follow-up questions ("what did you say
     *  about the Lakers") without carrying the cost of the user's entire history indefinitely. */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** Anthropic allows at most 4 cache_control breakpoints per request. Two are static (system
     *  prompt + last tool definition); the remaining budget is used as a SLIDING window over the
     *  most-recent tool-result messages (see {@link #runAgentLoop}). Rather than freezing breakpoints
     *  at the first couple of iterations (which left everything after them uncached and reprocessed
     *  every later iteration), the marker moves forward each turn so the whole growing prefix stays
     *  cached and only the newest delta is written. 2 sliding markers keeps the total at the 4 ceiling
     *  and adds resilience when a single turn emits many tool-result blocks. */
    private static final int SLIDING_BREAKPOINTS = 2;

    /** Tool set for the daily value-play scheduler's per-(sport, category) research tasks — 30 on a
     *  normal day (one per individual market/stat type, not just per sport/category), automatically
     *  more if a sport's slate is unusually busy (see
     *  {@code ValuePlayScheduler}) — deliberately does NOT include {@link RunFullSlateResearchTool},
     *  which itself runs this exact same set of tasks; including it here would let a sub-task
     *  recursively re-trigger the whole cycle. */
    public static final List<Class<?>> VALUE_PLAY_TOOL_CLASSES = List.of(
            GetSportBoardTool.class,
            ListSportsTool.class,
            ListGamesTool.class,
            GetGameTool.class,
            GetMarketOrderbookTool.class,
            GetTeamAnalyticsTool.class,
            GetIndividualAnalyticsTool.class,
            GetTeamRecentFormTool.class,
            GetPlayerRecentStatsTool.class,
            GetInjuryReportTool.class);

    /** Tool set for interactive Discord chat — everything in {@link #VALUE_PLAY_TOOL_CLASSES} plus
     *  {@link RunFullSlateResearchTool} (unscoped "give me every possible play") and
     *  {@link RunSportResearchTool} (one whole sport's plays, e.g. "NBA plays"). Neither is included
     *  in {@link #VALUE_PLAY_TOOL_CLASSES} — a chat turn can use them, but never a scheduled sub-task,
     *  which would let a sub-task recursively re-trigger a whole research cycle. */
    private static final List<Class<?>> TOOL_CLASSES;
    static {
        List<Class<?>> all = new ArrayList<>(VALUE_PLAY_TOOL_CLASSES);
        all.add(RunFullSlateResearchTool.class);
        all.add(RunSportResearchTool.class);
        TOOL_CLASSES = List.copyOf(all);
    }

    /** {@link #VALUE_PLAY_TOOL_CLASSES} minus {@link GetSportBoardTool} and {@link GetInjuryReportTool}
     *  plus {@link RecordPlayTool} — used only by {@link #chatOnceForPlays}, i.e. {@code
     *  ValuePlayScheduler}'s own research tasks. Both excluded tools are deliberately NOT offered:
     *  {@code ValuePlayScheduler} now fetches each sport's board AND every team's injury report once
     *  in Java and embeds them directly in the task's prompt (see {@code
     *  ValuePlayScheduler#buildTasksForToday}, {@code SportsCatalogService#getSportBoard}) instead of
     *  having every one of a sport's several sibling tasks independently call these tools and each pay
     *  full price for the same data — confirmed as the dominant driver of this app's Anthropic API
     *  cost once task count grew to one-per-market-type (e.g. 7 separate NBA tasks, each re-fetching
     *  NBA's entire board, and GetInjuryReportTool being "mandatory for both teams before finalizing
     *  any play" meant dozens of redundant per-team calls across those same sibling tasks too).
     *  Offering either tool anyway would just invite the model to redundantly re-fetch data it's
     *  already been given, defeating the whole point. Deliberately NOT folded into {@link #TOOL_CLASSES}
     *  either way: a top-level chat turn should keep answering a narrow, one-off question in plain
     *  text as it always has, with both tools available as normal, not be offered a structured
     *  "record a play" tool that only makes sense for the scheduler's report-building pipeline. */
    public static final List<Class<?>> RECORD_PLAY_TOOL_CLASSES;
    static {
        List<Class<?>> all = new ArrayList<>(VALUE_PLAY_TOOL_CLASSES);
        all.remove(GetSportBoardTool.class);
        all.remove(GetInjuryReportTool.class);
        all.add(RecordPlayTool.class);
        RECORD_PLAY_TOOL_CLASSES = List.copyOf(all);
    }

    /** The result of a research task: whatever plain text it wrote (for {@code chatOnceForPlays}
     *  tasks, expected to be just a GAMES_CHECKED line — see {@code ValuePlayScheduler} — never a
     *  formatted play list anymore) plus every play it reported via {@link RecordPlayTool}. {@code
     *  chat}/{@code chatOnce} only ever care about {@code text}; {@code chatOnceForPlays} is the one
     *  that needs {@code plays}. */
    public record PlaysResult(String text, List<RecordedPlay> plays) {
    }

    private final AnthropicClient client;
    private final String systemPromptTemplate;
    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> toolClassesByName = new LinkedHashMap<>();

    private record ChatMessage(String role, String content) {
    }

    /** Raw ingredients of one tool result, kept so the tool-result message (and its sliding cache
     *  marker) can be rebuilt fresh each iteration instead of reusing an immutable block verbatim. */
    private record RawResult(String toolUseId, String content, boolean isError) {
    }

    /** One completed loop turn: the assistant message (which requested tools) and the results of
     *  running them. Re-emitted every iteration by {@link #assembleRequest}. */
    private record LoopTurn(BetaMessage assistant, List<RawResult> results) {
    }

    public OrchestratorService(AnthropicClient client,
                                SportsCatalogService sportsCatalogService,
                                SportsAnalyticsService sportsAnalyticsService,
                                ObjectProvider<ValuePlayScheduler> valuePlaySchedulerProvider,
                                Validator validator) {
        this.client = client;
        ToolServices.sportsCatalogService = sportsCatalogService;
        ToolServices.sportsAnalyticsService = sportsAnalyticsService;
        ToolServices.valuePlayScheduler = valuePlaySchedulerProvider;
        ToolServices.validator = validator;
        this.systemPromptTemplate = loadInstructions();
        // TOOL_CLASSES doesn't include RecordPlayTool (see RECORD_PLAY_TOOL_CLASSES) — dispatch still
        // needs to recognize it by name when a chatOnceForPlays task calls it, so register the union
        // of both lists rather than just the ones a top-level chat turn sees.
        Set<Class<?>> allToolClasses = new LinkedHashSet<>(TOOL_CLASSES);
        allToolClasses.addAll(RECORD_PLAY_TOOL_CLASSES);
        for (Class<?> toolClass : allToolClasses) {
            toolClassesByName.put(NonStrictTools.from(toolClass).name(), toolClass);
        }
    }

    private static String loadInstructions() {
        try {
            ClassPathResource resource = new ClassPathResource("docs/instructions.md");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not load instructions.md, falling back to default prompt: {}", e.getMessage());
            return "You are a helpful assistant for browsing Kalshi sports markets and placing bets.";
        }
    }

    /** Instructions.md is a static resource — append the actual current date each call so the
     *  model never has to guess it (it has no live clock) or fall back to a training-data date. */
    private String currentSystemPrompt() {
        String today = LocalDate.now(USER_ZONE).format(DATE_FORMAT);
        return systemPromptTemplate + "\n\nToday's date is " + today + " (America/Chicago).";
    }

    public String chat(String userId, String userMessage) {
        List<ChatMessage> history = histories.computeIfAbsent(
                userId, k -> Collections.synchronizedList(new ArrayList<>()));

        log.info("[{}] Orchestrator received: \"{}\" (history: {} turns)", userId, userMessage, history.size());

        history.add(new ChatMessage("user", userMessage));

        List<ChatMessage> baseMessages;
        synchronized (history) {
            baseMessages = new ArrayList<>(history);
        }

        String responseText = runAgentLoop(userId, TOOL_CLASSES, baseMessages).text();

        if (!responseText.isEmpty()) {
            synchronized (history) {
                history.add(new ChatMessage("assistant", responseText));
                while (history.size() > MAX_HISTORY_MESSAGES) {
                    history.remove(0);
                }
            }
        } else {
            history.remove(history.size() - 1);
            log.warn("[{}] Empty orchestrator response — rolled back user message from history", userId);
        }

        return responseText;
    }

    /**
     * Runs one fully self-contained agent turn with NO persisted conversation memory — unlike
     * {@link #chat}, nothing is read from or written to {@link #histories}. Built for the scheduled
     * autonomous combo-betting task: every cycle already re-derives everything it needs from scratch
     * (GetPositionsTool, fresh pricing, fresh analytics — see instructions.md's mandatory checks), so
     * remembering past cycles' prompts/reports would buy zero decision-quality benefit. It would,
     * however, cost real money forever: {@code histories} entries never get a cache breakpoint, so
     * every past cycle's prompt+report would get resent at full, uncached input-token price on every
     * single future call (scheduler or regular chat) for as long as the process stays up. Keeping
     * the scheduler on this stateless path instead avoids that unbounded, ever-growing cost outright.
     *
     * @param logId identifies this run in logs the same way {@code userId} does for {@link #chat}
     */
    public String chatOnce(String logId, String userMessage) {
        return chatOnce(logId, userMessage, TOOL_CLASSES);
    }

    /**
     * Single-turn variant that runs with a caller-supplied tool set instead of all {@link #TOOL_CLASSES}.
     * The autonomous combo-betting scheduler passes {@link #SCHEDULER_TOOL_CLASSES} (a small subset),
     * since it injects a pre-priced candidate shortlist + positions into the prompt and no longer needs
     * the survey/pricing tools — fewer tool schemas means a smaller cached prefix on every iteration.
     */
    public String chatOnce(String logId, String userMessage, List<Class<?>> toolClasses) {
        return runAgentLoop(logId, toolClasses, List.of(new ChatMessage("user", userMessage))).text();
    }

    /**
     * Like {@link #chatOnce(String, String, List)}, but for a task that reports its plays as
     * structured {@link RecordPlayTool} calls instead of free-text prose — pass
     * {@link #RECORD_PLAY_TOOL_CLASSES} as {@code toolClasses}. Returns the parsed plays directly
     * alongside whatever text the task wrote, so the caller ({@code ValuePlayScheduler}) can dedupe,
     * floor-filter, band-sort, and format the final report in plain Java instead of spending two more
     * Anthropic calls (narration-stripping plus a separate dedupe/filter pass) just to clean up
     * free-text output after the fact — this is the whole point of recording plays structurally in
     * the first place.
     */
    public PlaysResult chatOnceForPlays(String logId, String userMessage, List<Class<?>> toolClasses) {
        return runAgentLoop(logId, toolClasses, List.of(new ChatMessage("user", userMessage)));
    }

    /** System prompt + tool definitions only (NO messages) — the static, cacheable prefix shared by
     *  every request. Messages are added separately by {@link #assembleRequest} so the tool-result
     *  cache markers can slide forward each iteration. Cache breakpoints here: the system block (1)
     *  and the last tool definition (1). */
    private MessageCreateParams.Builder newRequestBuilder(List<Class<?>> toolClasses) {
        // System prompt and tools are identical on every call (within the same day) — mark cache
        // breakpoints on both so Anthropic serves them from cache instead of full input-token price
        // on every request, including every iteration of the tool-calling loop below.
        BetaTextBlockParam systemBlock = BetaTextBlockParam.builder()
                .text(currentSystemPrompt())
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build();

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(MODEL)
                // Doubled from 4096 as headroom, not a fix by itself — billing follows tokens
                // actually generated, not this ceiling, so this costs nothing unless a turn was
                // genuinely truncating. The real fix for the thinking-block issue is disabling
                // thinking below; this just protects the (verbose, multi-bet) final report from
                // ever hitting the same kind of cap on legitimately long output.
                .maxTokens(8192L)
                // Confirmed via prod logs: without this, some turns come back with stopReason=
                // max_tokens and a single "thinking" content block — the model spent its entire
                // turn budget reasoning internally and never got to emit the tool call or text,
                // wasting the turn outright. This app's hand-rolled loop has no use for thinking
                // traces anyway (they're never surfaced to the user), so disable it outright rather
                // than just padding maxTokens and hoping it's enough headroom.
                .thinking(BetaThinkingConfigDisabled.builder().build())
                .system(MessageCreateParams.System.ofBetaTextBlockParams(List.of(systemBlock)));
        for (int i = 0; i < toolClasses.size(); i++) {
            boolean isLastTool = i == toolClasses.size() - 1;
            builder.addTool(NonStrictTools.from(toolClasses.get(i), isLastTool));
        }
        // Server-side web search — lets the model pull real, current numbers from ESPN or anywhere
        // else on the web (injury news, a stat the structured tools above don't expose) instead of
        // relying only on this app's own curated ESPN endpoints. Anthropic executes the search and
        // feeds the result back to the model server-side (no tool_use/tool_result round trip through
        // this app's loop is needed). maxUses caps the cost of a single turn going search-happy.
        builder.addTool(BetaWebSearchTool20260209.builder().maxUses(5).build());
        return builder;
    }

    /** Assembles a full request: the static prefix ({@link #newRequestBuilder}) + the base messages
     *  (conversation history for {@link #chat}, or the single user turn for {@link #chatOnce}) + every
     *  completed loop turn (assistant message + its tool results). A cache breakpoint is applied to the
     *  LAST tool-result block of only the newest {@link #SLIDING_BREAKPOINTS} turns — so as the loop
     *  grows, the marker slides forward and the whole prior prefix stays a cache hit while only the
     *  newest delta is written. Rebuilt fresh each iteration (rather than mutating one builder) so the
     *  marker can move; older turns are re-emitted byte-identical, which is what keeps them cache-hits. */
    private MessageCreateParams.Builder assembleRequest(List<Class<?>> toolClasses,
                                                         List<ChatMessage> baseMessages,
                                                         List<LoopTurn> turns) {
        MessageCreateParams.Builder builder = newRequestBuilder(toolClasses);
        for (ChatMessage msg : baseMessages) {
            if ("user".equals(msg.role())) {
                builder.addUserMessage(msg.content());
            } else {
                builder.addAssistantMessage(msg.content());
            }
        }
        int firstCachedTurn = Math.max(0, turns.size() - SLIDING_BREAKPOINTS);
        for (int i = 0; i < turns.size(); i++) {
            LoopTurn turn = turns.get(i);
            builder.addMessage(turn.assistant());
            builder.addMessage(toolResultMessage(turn.results(), i >= firstCachedTurn));
        }
        return builder;
    }

    /** Builds the USER message carrying a turn's tool results; marks the last result block as a cache
     *  breakpoint when {@code cacheLast} is true (the sliding-window marker). */
    private static BetaMessageParam toolResultMessage(List<RawResult> results, boolean cacheLast) {
        List<BetaContentBlockParam> blocks = new ArrayList<>();
        for (int j = 0; j < results.size(); j++) {
            boolean cache = cacheLast && j == results.size() - 1;
            blocks.add(BetaContentBlockParam.ofToolResult(toBlock(results.get(j), cache)));
        }
        return BetaMessageParam.builder()
                .role(BetaMessageParam.Role.USER)
                .contentOfBetaContentBlockParams(blocks)
                .build();
    }

    private static BetaToolResultBlockParam toBlock(RawResult r, boolean cache) {
        BetaToolResultBlockParam.Builder b = BetaToolResultBlockParam.builder()
                .toolUseId(r.toolUseId())
                .content(r.content());
        if (r.isError()) {
            b.isError(true);
        }
        if (cache) {
            b.cacheControl(BetaCacheControlEphemeral.builder().build());
        }
        return b.build();
    }

    /** The actual tool-calling loop — shared by {@link #chat}, {@link #chatOnce}, and
     *  {@link #chatOnceForPlays}. {@code logId} is just a label for log lines, not necessarily a real
     *  Discord user id (see {@link #chatOnce}). */
    private PlaysResult runAgentLoop(String logId, List<Class<?>> toolClasses, List<ChatMessage> baseMessages) {
        String finalResponse = "";
        List<RecordedPlay> recordedPlays = new ArrayList<>();
        Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
        int iterationsUsed = 0;
        // Aggregate token usage across the loop so cost is visible from the normal INFO logs (input
        // is the dominant driver — the whole conversation is resent each iteration; cache_read is
        // billed at ~10% of input, cache_write at ~125%, so the split matters for reading the bill).
        long inputTokens = 0, outputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0;
        List<LoopTurn> turns = new ArrayList<>();
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            iterationsUsed = iteration + 1;
            // Rebuild the whole request each iteration so the sliding cache marker can move forward
            // (see assembleRequest). Everything below the newest marker is a cache read; only the
            // newest turn is written.
            MessageCreateParams params = assembleRequest(toolClasses, baseMessages, turns).build();
            BetaMessage message = client.beta().messages().create(params);

            BetaUsage usage = message.usage();
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            cacheReadTokens += usage.cacheReadInputTokens().orElse(0L);
            cacheWriteTokens += usage.cacheCreationInputTokens().orElse(0L);

            log.debug("[{}] Orchestrator step: stopReason={}, blocks={}",
                    logId,
                    message.stopReason().map(Object::toString).orElse("(none)"),
                    message.content().size());

            List<BetaToolUseBlock> toolUses = new ArrayList<>();
            boolean sawText = false;
            for (BetaContentBlock block : message.content()) {
                if (block.toolUse().isPresent()) {
                    BetaToolUseBlock t = block.toolUse().get();
                    log.info("[{}] Orchestrator calling tool: {}", logId, t.name());
                    toolUses.add(t);
                    toolCallCounts.merge(t.name(), 1, Integer::sum);
                }
                if (block.text().isPresent()) {
                    sawText = true;
                    finalResponse = block.text().get().text();
                }
            }

            if (toolUses.isEmpty() && !sawText) {
                // Neither a tool call nor text — the loop is about to end via the break below with
                // nothing to show for this turn, which is the exact anomaly that's been intermittently
                // triggering forceSynthesis with only a handful of iterations used (nowhere near the
                // 40-iteration cap the fallback's log message assumes). Anthropic's SDK models several
                // other content-block kinds (thinking, server tool use, compaction, mcp, ...) that this
                // app never requests but could in principle still show up — dump exactly what came back
                // so this is diagnosable from one log line instead of guessed at again.
                log.warn("[{}] Orchestrator step produced neither a tool call nor text (turn wasted) — "
                                + "stopReason={}, blockCount={}, blockKinds={}",
                        logId,
                        message.stopReason().map(Object::toString).orElse("(none)"),
                        message.content().size(),
                        describeBlocks(message.content()));
            }

            if (toolUses.isEmpty()) {
                break;
            }

            List<RawResult> results = new ArrayList<>();
            for (BetaToolUseBlock toolUse : toolUses) {
                results.add(runTool(toolUse, recordedPlays));
            }
            turns.add(new LoopTurn(message, results));
        }

        // One cheap summary line per turn (not per-iteration) so a budget-exhaustion cycle is
        // diagnosable from the default INFO logs alone — no need to re-enable DEBUG in prod, which
        // has previously flooded logs when left on for a firehose-style channel.
        log.info("[{}] Orchestrator tool loop finished after {} iteration(s), tool calls: {}, "
                        + "tokens: input={}, output={}, cache_read={}, cache_write={}",
                logId, iterationsUsed, toolCallCounts,
                inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);

        String responseText = finalResponse;
        if (responseText.isEmpty() && recordedPlays.isEmpty()) {
            // The loop ended (almost always by hitting MAX_TOOL_ITERATIONS with tool calls still
            // pending) without the model ever producing a text turn OR recording any plays — silently
            // returning nothing is worse than a rushed answer, so force one more call with NO tools
            // attached, so the model physically cannot keep researching and must respond with text.
            // Skipped when plays WERE already recorded via RecordPlayTool calls (a chatOnceForPlays
            // task with no closing text is a normal, successful outcome, not a failure to paper over
            // with an extra paid call). Reassemble the full accumulated conversation (all turns) to
            // hand it the complete context.
            log.warn("[{}] Tool loop ended without a final text response or any recorded plays "
                    + "(likely hit the {}-iteration cap) — forcing a no-tools synthesis call instead "
                    + "of returning nothing.", logId, MAX_TOOL_ITERATIONS);
            responseText = forceSynthesis(assembleRequest(toolClasses, baseMessages, turns));
        }

        return new PlaysResult(stripThinkTags(sanitizePlaysResponse(responseText)), recordedPlays);
    }

    /**
     * Draft responses have repeatedly leaked scratch-work/narration around the required play-list
     * format despite instructions.md explicitly prohibiting it — confirmed across multiple rounds of
     * real user feedback (opening narration like "Now I have what I need," a walkthrough comparing
     * candidates before picking winners, mid-sentence self-correction like "let me restate... actually
     * ...", fake entries for markets that were SKIPPED, a closing "here's what else I checked"
     * paragraph). Strengthening the prompt alone didn't hold up reliably once a single turn had to
     * juggle many tool results and candidates while ALSO never thinking out loud — that's two hard
     * jobs at once. This runs a second, tools-disabled pass whose ONLY job is to mechanically strip
     * anything outside the required format from an already-written draft — a much easier instruction
     * to follow in isolation than "analyze N candidates and never narrate" in the same turn. Skipped
     * (no extra API call) when the draft plainly isn't a play list at all.
     */
    /** Matches the trailing "GAMES_CHECKED: ..." line {@code ValuePlayScheduler} asks every research
     *  task to append, for internal coverage-verification logging — never meant for the user. It
     *  would otherwise read to this method as exactly the kind of stray non-play-list text it's told
     *  to strip, so it's pulled out before sanitizing and reattached after, rather than teaching this
     *  generic cleanup pass about a scheduler-specific concern. */
    private static final java.util.regex.Pattern GAMES_CHECKED_LINE =
            java.util.regex.Pattern.compile("(?m)^GAMES_CHECKED:.*$\\n?");

    private String sanitizePlaysResponse(String rawResponse) {
        if (rawResponse.isBlank() || !rawResponse.contains("Plays for ")) {
            return rawResponse;
        }

        java.util.regex.Matcher gamesCheckedMatch = GAMES_CHECKED_LINE.matcher(rawResponse);
        String gamesCheckedLine = gamesCheckedMatch.find() ? gamesCheckedMatch.group() : null;
        String toSanitize = gamesCheckedLine == null ? rawResponse
                : gamesCheckedMatch.replaceFirst("").stripTrailing();

        String prompt = """
                Below is a draft response that's supposed to contain ONLY a sports value-play list, but \
                earlier drafts from this same system have sometimes leaked extra text around it: opening \
                narration ("Now I have what I need," "Let me finalize the analysis," "Key findings:," \
                "Based on the data, let me note..."), a walkthrough comparing candidates before picking \
                winners, mid-sentence self-correction ("let me restate... actually..."), a fake entry for \
                a market that was SKIPPED (e.g. "N/A ... Skipped: no real edge"), or a closing paragraph \
                about what else was checked but not included.

                Re-emit the draft with ALL of that removed, keeping ONLY: the "Plays for {date}" header \
                line, and for each genuinely recommended play, its one formatted line (league/team/\
                market/odds/probabilities) plus its one-sentence reasoning, with one blank line between \
                plays. If the draft says no plays qualify, output just that one sentence (still drop any \
                narration around it). Do not change, add, or remove any actual team, market, number, \
                odds, or probability — only strip the narration/scratch-work/skipped-entry text around \
                them. If the draft already has none of that leakage, return it byte-for-byte unchanged.

                Draft:
                %s""".formatted(toSanitize);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8192L)
                .thinking(BetaThinkingConfigDisabled.builder().build())
                .addUserMessage(prompt)
                .build();

        BetaMessage message = client.beta().messages().create(params);
        StringBuilder text = new StringBuilder();
        for (BetaContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        String cleaned = stripThinkTags(text.toString());
        if (cleaned.isBlank()) {
            return rawResponse;
        }
        if (!cleaned.equals(toSanitize)) {
            log.info("Sanitized narration/scratch-work out of a play-list response ({} chars -> {} chars)",
                    toSanitize.length(), cleaned.length());
        }
        return gamesCheckedLine == null ? cleaned : cleaned + "\n\n" + gamesCheckedLine.strip();
    }

    /** Matches a literal "&lt;think&gt;...&lt;/think&gt;" block some responses have written directly \
     *  into their visible text (not the SDK's separate, already-disabled "thinking" content-block \
     *  type — this is the model adopting its own scratchpad convention in plain text, which disabling \
     *  that feature does nothing to prevent). Confirmed in production, once as a multi-paragraph \
     *  reasoning walkthrough ahead of a free-text report (before plays moved to structured \
     *  {@link RecordPlayTool} calls, which made that whole failure mode moot for this app's own \
     *  cleanup passes — this strip remains as a defensive backstop for the general chat path). */
    private static String stripThinkTags(String text) {
        return text.replaceAll("(?is)<think>.*?</think>\\s*", "").stripLeading();
    }

    /** Rebuilds the accumulated conversation with NO tools attached and a blunt "stop researching,
     *  answer now" nudge appended, so the model can only respond with text — used when the normal
     *  tool-calling loop exhausts its iteration budget without ever producing a final answer. */
    private String forceSynthesis(MessageCreateParams.Builder currentBuilder) {
        MessageCreateParams currentParams = currentBuilder
                .addUserMessage("You're out of research budget for this turn — stop calling tools and "
                        + "give your final answer now, based on everything you've found so far. If "
                        + "research feels incomplete, say so plainly rather than guessing at anything "
                        + "you haven't actually checked.")
                .build();

        MessageCreateParams.Builder noToolsBuilder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8192L)
                .thinking(BetaThinkingConfigDisabled.builder().build())
                .messages(currentParams.messages());
        currentParams.system().ifPresent(noToolsBuilder::system);

        BetaMessage finalMessage = client.beta().messages().create(noToolsBuilder.build());
        StringBuilder text = new StringBuilder();
        for (BetaContentBlock block : finalMessage.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        return text.toString();
    }

    /** Best-effort human-readable label per content block, for the anomaly log above — covers every
     *  block kind the SDK models, not just the text/tool_use ones this app actually requests, since
     *  the whole point is to see if something unexpected (e.g. thinking, compaction) is showing up. */
    private static List<String> describeBlocks(List<BetaContentBlock> blocks) {
        List<String> kinds = new ArrayList<>();
        for (BetaContentBlock block : blocks) {
            if (block.isText()) kinds.add("text");
            else if (block.isToolUse()) kinds.add("tool_use");
            else if (block.isThinking()) kinds.add("thinking");
            else if (block.isRedactedThinking()) kinds.add("redacted_thinking");
            else if (block.isServerToolUse()) kinds.add("server_tool_use");
            else if (block.isMcpToolUse()) kinds.add("mcp_tool_use");
            else if (block.isMcpToolResult()) kinds.add("mcp_tool_result");
            else if (block.isCompaction()) kinds.add("compaction");
            else if (block.isContainerUpload()) kinds.add("container_upload");
            else if (block.isWebSearchToolResult()) kinds.add("web_search_tool_result");
            else if (block.isWebFetchToolResult()) kinds.add("web_fetch_tool_result");
            else if (block.isCodeExecutionToolResult()) kinds.add("code_execution_tool_result");
            else kinds.add("unknown");
        }
        return kinds;
    }

    public void clearHistory(String userId) {
        histories.remove(userId);
        log.info("[{}] Orchestrator conversation history cleared", userId);
    }

    /**
     * Instantiates and runs the tool class matching this tool_use block's name, using
     * {@link BetaToolUseBlock#input(Class)} to parse arguments — the same public method
     * {@code BetaToolRunner} uses internally, so parsing behavior matches exactly. Returns the raw
     * result (id/content/error); the tool-result message and any cache marker are built later in
     * {@link #toolResultMessage} so the sliding cache window can be applied fresh each iteration.
     * <p>
     * A {@link RecordPlayTool} call is additionally captured into {@code recordedPlays} (a no-op for
     * every other tool) — this is how {@link #chatOnceForPlays} gets its structured output instead of
     * having to parse it back out of free text.
     */
    private RawResult runTool(BetaToolUseBlock toolUse, List<RecordedPlay> recordedPlays) {
        Class<?> toolClass = toolClassesByName.get(toolUse.name());
        if (toolClass == null) {
            log.error("Orchestrator tool dispatch failed: unknown tool '{}'", toolUse.name());
            return new RawResult(toolUse.id(), "Error: Tool '" + toolUse.name() + "' not found", true);
        }
        try {
            Object instance = toolUse.input(toolClass);
            if (instance instanceof RecordPlayTool recordPlayTool) {
                RecordedPlay play = RecordedPlay.from(recordPlayTool);
                if (play != null) {
                    recordedPlays.add(play);
                } else {
                    log.warn("Orchestrator: RecordPlayTool call missing a required field, dropping it "
                            + "(subject={})", recordPlayTool.displaySubject);
                }
            }
            String output = ((Supplier<?>) instance).get().toString();
            return new RawResult(toolUse.id(), output, false);
        } catch (Exception e) {
            log.error("Orchestrator tool dispatch failed for '{}'", toolUse.name(), e);
            return new RawResult(toolUse.id(), "Error: " + e.getMessage(), true);
        }
    }
}
