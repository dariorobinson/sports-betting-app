package com.kalshi.betting.orchestrator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
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
import java.util.concurrent.atomic.AtomicReference;
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
    private static final String MODEL = "claude-opus-4-8";
    private static final ZoneId USER_ZONE = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");
    private static final int MAX_TOOL_ITERATIONS = 25;

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
            GetTeamAnalyticsTool.class,
            GetIndividualAnalyticsTool.class);

    private final AnthropicClient client;
    private final String systemPromptTemplate;
    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> toolClassesByName = new LinkedHashMap<>();

    private record ChatMessage(String role, String content) {
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

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4096L)
                .system(currentSystemPrompt());
        for (Class<?> toolClass : TOOL_CLASSES) {
            builder.addTool(NonStrictTools.from(toolClass));
        }

        synchronized (history) {
            for (ChatMessage msg : history) {
                if ("user".equals(msg.role())) {
                    builder.addUserMessage(msg.content());
                } else {
                    builder.addAssistantMessage(msg.content());
                }
            }
        }

        AtomicReference<String> finalResponse = new AtomicReference<>("");
        MessageCreateParams.Builder currentBuilder = builder;
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            MessageCreateParams params = currentBuilder.build();
            BetaMessage message = client.beta().messages().create(params);

            log.debug("[{}] Orchestrator step: stopReason={}, blocks={}",
                    userId,
                    message.stopReason().map(Object::toString).orElse("(none)"),
                    message.content().size());

            List<BetaToolUseBlock> toolUses = new ArrayList<>();
            for (BetaContentBlock block : message.content()) {
                block.toolUse().ifPresent(t -> {
                    log.info("[{}] Orchestrator calling tool: {}", userId, t.name());
                    toolUses.add(t);
                });
                block.text().ifPresent(t -> finalResponse.set(t.text()));
            }

            if (toolUses.isEmpty()) {
                break;
            }

            List<BetaContentBlockParam> resultBlocks = new ArrayList<>();
            for (BetaToolUseBlock toolUse : toolUses) {
                resultBlocks.add(BetaContentBlockParam.ofToolResult(runTool(toolUse)));
            }
            BetaMessageParam toolResultMessage = BetaMessageParam.builder()
                    .role(BetaMessageParam.Role.USER)
                    .contentOfBetaContentBlockParams(resultBlocks)
                    .build();

            currentBuilder = params.toBuilder().addMessage(message).addMessage(toolResultMessage);
        }

        String responseText = finalResponse.get();
        if (!responseText.isEmpty()) {
            history.add(new ChatMessage("assistant", responseText));
        } else {
            history.remove(history.size() - 1);
            log.warn("[{}] Empty orchestrator response — rolled back user message from history", userId);
        }

        return responseText;
    }

    public void clearHistory(String userId) {
        histories.remove(userId);
        log.info("[{}] Orchestrator conversation history cleared", userId);
    }

    /**
     * Instantiates and runs the tool class matching this tool_use block's name, using
     * {@link BetaToolUseBlock#input(Class)} to parse arguments — the same public method
     * {@code BetaToolRunner} uses internally, so parsing behavior matches exactly.
     */
    private BetaToolResultBlockParam runTool(BetaToolUseBlock toolUse) {
        Class<?> toolClass = toolClassesByName.get(toolUse.name());
        if (toolClass == null) {
            log.error("Orchestrator tool dispatch failed: unknown tool '{}'", toolUse.name());
            return BetaToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content("Error: Tool '" + toolUse.name() + "' not found")
                    .isError(true)
                    .build();
        }
        try {
            Object instance = toolUse.input(toolClass);
            String output = ((Supplier<?>) instance).get().toString();
            return BetaToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content(output)
                    .build();
        } catch (Exception e) {
            log.error("Orchestrator tool dispatch failed for '{}'", toolUse.name(), e);
            return BetaToolResultBlockParam.builder()
                    .toolUseId(toolUse.id())
                    .content("Error: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
