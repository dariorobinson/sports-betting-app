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
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.kalshi.betting.orchestrator.tool.*;
import com.kalshi.betting.service.BettingService;
import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.service.PortfolioService;
import com.kalshi.betting.service.SportsCatalogService;
import com.kalshi.betting.sportsdata.SportsAnalyticsService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final List<Class<?>> TOOL_CLASSES = List.of(
            ListSportsTool.class,
            ListGamesTool.class,
            GetGameTool.class,
            GetMarketOrderbookTool.class,
            GetBalanceTool.class,
            GetPositionsTool.class,
            ListMyOrdersTool.class,
            ListSportsCombosTool.class,
            GetComboLegsTool.class,
            PlaceBetTool.class,
            CancelBetTool.class,
            PriceComboTool.class,
            PlaceComboBetTool.class,
            GetTeamAnalyticsTool.class,
            GetIndividualAnalyticsTool.class);

    /** Reduced tool set for the autonomous combo-betting scheduler. The scheduler now pre-computes a
     *  priced candidate shortlist and current positions in Java and injects them into the prompt
     *  (see {@code AutoComboBettingScheduler}), so the model no longer surveys or prices from scratch
     *  — it only researches legs, selects, and places. Shipping ~4 tool schemas instead of 15 shrinks
     *  the cached prefix read on every loop iteration. PriceComboTool stays only as a fallback for the
     *  model to sanity-check an alternative combination; PlaceComboBetTool actually places. */
    public static final List<Class<?>> SCHEDULER_TOOL_CLASSES = List.of(
            GetTeamAnalyticsTool.class,
            GetIndividualAnalyticsTool.class,
            PriceComboTool.class,
            PlaceComboBetTool.class);

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
                                BettingService bettingService,
                                PortfolioService portfolioService,
                                ComboService comboService,
                                SportsAnalyticsService sportsAnalyticsService,
                                Validator validator) {
        this.client = client;
        ToolServices.sportsCatalogService = sportsCatalogService;
        ToolServices.bettingService = bettingService;
        ToolServices.portfolioService = portfolioService;
        ToolServices.comboService = comboService;
        ToolServices.sportsAnalyticsService = sportsAnalyticsService;
        ToolServices.validator = validator;
        this.systemPromptTemplate = loadInstructions();
        for (Class<?> toolClass : TOOL_CLASSES) {
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

        String responseText = runAgentLoop(userId, TOOL_CLASSES, baseMessages);

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

    /** The actual tool-calling loop — shared by {@link #chat} and {@link #chatOnce}. {@code logId}
     *  is just a label for log lines, not necessarily a real Discord user id (see {@link #chatOnce}). */
    private String runAgentLoop(String logId, List<Class<?>> toolClasses, List<ChatMessage> baseMessages) {
        String finalResponse = "";
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
                results.add(runTool(toolUse));
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
        if (responseText.isEmpty()) {
            // The loop ended (almost always by hitting MAX_TOOL_ITERATIONS with tool calls still
            // pending) without the model ever producing a text turn — silently returning nothing is
            // worse than a rushed answer, so force one more call with NO tools attached, so the
            // model physically cannot keep researching and must respond with text. Reassemble the
            // full accumulated conversation (all turns) to hand it the complete context.
            log.warn("[{}] Tool loop ended without a final text response (likely hit the {}-iteration "
                    + "cap) — forcing a no-tools synthesis call instead of returning nothing.",
                    logId, MAX_TOOL_ITERATIONS);
            responseText = forceSynthesis(assembleRequest(toolClasses, baseMessages, turns));
        }

        return responseText;
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
     */
    private RawResult runTool(BetaToolUseBlock toolUse) {
        Class<?> toolClass = toolClassesByName.get(toolUse.name());
        if (toolClass == null) {
            log.error("Orchestrator tool dispatch failed: unknown tool '{}'", toolUse.name());
            return new RawResult(toolUse.id(), "Error: Tool '" + toolUse.name() + "' not found", true);
        }
        try {
            Object instance = toolUse.input(toolClass);
            String output = ((Supplier<?>) instance).get().toString();
            return new RawResult(toolUse.id(), output, false);
        } catch (Exception e) {
            log.error("Orchestrator tool dispatch failed for '{}'", toolUse.name(), e);
            return new RawResult(toolUse.id(), "Error: " + e.getMessage(), true);
        }
    }
}
