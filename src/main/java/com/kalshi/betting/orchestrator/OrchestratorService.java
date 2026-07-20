package com.kalshi.betting.orchestrator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.kalshi.betting.orchestrator.tool.*;
import com.kalshi.betting.service.BettingService;
import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.service.PortfolioService;
import com.kalshi.betting.service.SportsCatalogService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the agentic tool-calling loop for the Discord bot: takes a user message, lets Claude decide
 * which Kalshi tools to call (via {@link BetaToolRunner}, which executes tool calls automatically
 * with no confirmation step), and returns the final text response. Per-user conversation history
 * is kept in memory only — it doesn't survive an app restart.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private static final String MODEL = "claude-opus-4-8";

    private final AnthropicClient client;
    private final String systemPrompt;
    private final Map<String, List<ChatMessage>> histories = new ConcurrentHashMap<>();

    private record ChatMessage(String role, String content) {
    }

    public OrchestratorService(AnthropicClient client,
                                SportsCatalogService sportsCatalogService,
                                BettingService bettingService,
                                PortfolioService portfolioService,
                                ComboService comboService,
                                Validator validator) {
        this.client = client;
        ToolServices.sportsCatalogService = sportsCatalogService;
        ToolServices.bettingService = bettingService;
        ToolServices.portfolioService = portfolioService;
        ToolServices.comboService = comboService;
        ToolServices.validator = validator;
        this.systemPrompt = loadInstructions();
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

    public String chat(String userId, String userMessage) {
        List<ChatMessage> history = histories.computeIfAbsent(
                userId, k -> Collections.synchronizedList(new ArrayList<>()));

        log.info("[{}] Orchestrator received: \"{}\" (history: {} turns)", userId, userMessage, history.size());

        history.add(new ChatMessage("user", userMessage));

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4096L)
                .system(systemPrompt)
                .addTool(ListSportsTool.class)
                .addTool(ListGamesTool.class)
                .addTool(GetGameTool.class)
                .addTool(GetMarketOrderbookTool.class)
                .addTool(GetBalanceTool.class)
                .addTool(GetPositionsTool.class)
                .addTool(ListMyOrdersTool.class)
                .addTool(ListSportsCombosTool.class)
                .addTool(GetComboLegsTool.class)
                .addTool(PlaceBetTool.class)
                .addTool(CancelBetTool.class)
                .addTool(PriceComboTool.class);

        synchronized (history) {
            for (ChatMessage msg : history) {
                if ("user".equals(msg.role())) {
                    builder.addUserMessage(msg.content());
                } else {
                    builder.addAssistantMessage(msg.content());
                }
            }
        }

        BetaToolRunner toolRunner = client.beta().messages().toolRunner(builder.build());

        AtomicReference<String> finalResponse = new AtomicReference<>("");
        for (BetaMessage message : toolRunner) {
            log.debug("[{}] Orchestrator step: stopReason={}, blocks={}",
                    userId,
                    message.stopReason().map(Object::toString).orElse("(none)"),
                    message.content().size());
            for (BetaContentBlock block : message.content()) {
                block.toolUse().ifPresent(t -> log.info("[{}] Orchestrator calling tool: {}", userId, t.name()));
                block.text().ifPresent(t -> finalResponse.set(t.text()));
            }
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
}
