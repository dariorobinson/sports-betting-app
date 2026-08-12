package com.kalshi.betting.scheduler;

import com.kalshi.betting.discord.DiscordMessages;
import com.kalshi.betting.orchestrator.OrchestratorService;
import com.kalshi.betting.orchestrator.ToolServices;
import com.kalshi.betting.service.ComboService;
import com.kalshi.betting.service.PortfolioService;
import com.kalshi.betting.web.dto.PricedComboCandidate;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Autonomously PLACES real combo bets twice a day — this actually risks real money with no human
 * confirmation step. Bet size is computed here in Java (a fixed fraction of current available
 * balance) rather than left to the model: precise arithmetic on real money shouldn't be delegated
 * to an LLM. The exact dollar figure is embedded directly in the prompt and the model is instructed
 * to pass it through to {@code PlaceComboBetTool} verbatim, not recompute or estimate it.
 * <p>
 * To keep Anthropic cost down, the expensive survey-and-price work is done deterministically in Java
 * ({@link ComboService#buildPricedCandidateShortlist}) BEFORE the model call: a shortlist of already-
 * RFQ-priced candidate combos plus current positions is injected into the prompt, so the model only
 * researches legs, selects, and places — it no longer browses or prices from scratch (that was the
 * dominant cost). It therefore runs with a reduced tool set ({@link OrchestratorService#SCHEDULER_TOOL_CLASSES}).
 * <p>
 * There is deliberately no loss-based circuit breaker (per explicit user choice) — only the
 * per-bet sizing rule bounds risk. Every run, successful or not, DMs a summary so there's always a
 * record of what happened.
 */
@Component
public class AutoComboBettingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoComboBettingScheduler.class);

    /** Fraction of current available balance risked per bet — user-specified: 4% (doubled from 2%). */
    private static final BigDecimal BET_SIZE_FRACTION = new BigDecimal("0.04");
    /** Combos to place per run — 4. The scheduler runs 2×/day (see cron), so this keeps daily volume
     *  at ~8 combos (same as the old 4×/day × 2) while halving the number of expensive model cycles. */
    private static final int NUMBER_OF_BETS = 4;
    /** Minimum PAYOUT MULTIPLE a whole combo must reach — user-specified: 1.6x. Payout ≈ 1/combined
     *  probability, so this means combined probability ≤ 1/1.6 = 62.5%. The shortlist builder stacks
     *  as many 70%+ favorite legs as it takes to get the combined probability down far enough to hit
     *  this multiple (e.g. two ~0.78 favorites, or ~five ~0.90 favorites). Replaces the old 60%
     *  combined-probability floor, which capped payouts at ~1.67x and left many combos under 1.5x. */
    private static final String MIN_PAYOUT_MULTIPLE = "1.6";
    /** Minimum implied probability of each INDIVIDUAL leg — user-specified: 70%. Every leg must be a
     *  clear favorite on its own; this screens out coin-flip legs and favors genuinely strong
     *  favorites (tennis especially). Legs are stacked to reach {@link #MIN_PAYOUT_MULTIPLE}. */
    private static final int MIN_LEG_PROBABILITY = 70;
    /** Most legs a combo may have — the builder adds legs (each still a 70%+ favorite) until the
     *  payout multiple is met; strong favorites multiply slowly, so it can take several. */
    private static final int MAX_COMBO_LEGS = 5;
    /** How many combo collections the Java shortlist builder surveys per cycle. */
    private static final int MAX_COLLECTIONS_TO_SURVEY = 4;

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final PortfolioService portfolioService;
    private final ComboService comboService;
    private final String authorizedUserId;

    public AutoComboBettingScheduler(ObjectProvider<JDA> jdaProvider,
                                       OrchestratorService orchestratorService,
                                       PortfolioService portfolioService,
                                       ComboService comboService,
                                       @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.portfolioService = portfolioService;
        this.comboService = comboService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 0 11,17 * * *", zone = "America/Chicago")
    public void run() {
        executeCycle();
    }

    /**
     * Runs one autonomous combo-betting cycle and returns the summary text (the same text that gets
     * DMed) — separated from the {@code @Scheduled} entrypoint so it can also be triggered on-demand
     * (see {@code DebugController}) without waiting for the next scheduled time. Real money, same as
     * the scheduled path — this is not a dry run.
     */
    public String executeCycle() {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.warn("Skipping autonomous combo betting — Discord bot is not configured (no DISCORD_BOT_TOKEN).");
            return "Skipped — Discord bot is not configured (no DISCORD_BOT_TOKEN).";
        }
        if (authorizedUserId == null || authorizedUserId.isBlank()) {
            log.warn("Skipping autonomous combo betting — DISCORD_AUTHORIZED_USER_ID is not set.");
            return "Skipped — DISCORD_AUTHORIZED_USER_ID is not set.";
        }

        String response;
        try {
            BigDecimal balance = new BigDecimal(portfolioService.getBalance().balanceDollars());
            BigDecimal betSize = balance.multiply(BET_SIZE_FRACTION).setScale(2, RoundingMode.DOWN);
            if (betSize.signum() <= 0) {
                log.warn("Skipping autonomous combo betting — computed bet size is $0 (balance: ${})", balance);
                response = "Autonomous combo betting skipped this cycle — balance ($" + balance
                        + ") is too low to size a bet.";
                notifyUser(jda, response);
                return response;
            }

            log.info("Running autonomous combo betting for user {} — balance=${}, betSize=${}",
                    authorizedUserId, balance, betSize);

            // Do the expensive survey + candidate pricing in Java (deterministic, no model) so the
            // model only has to select and place — this is the main Anthropic cost saving. K = 2×
            // the target so the model has real alternatives to choose among.
            List<PricedComboCandidate> shortlist = comboService.buildPricedCandidateShortlist(
                    MIN_LEG_PROBABILITY, new BigDecimal(MIN_PAYOUT_MULTIPLE), MAX_COMBO_LEGS,
                    NUMBER_OF_BETS * 2, MAX_COLLECTIONS_TO_SURVEY);

            if (shortlist.isEmpty()) {
                // No qualifying combos priced — don't spend a single Anthropic token this cycle.
                log.info("Autonomous combo betting: no qualifying combos this cycle — skipping the model call.");
                response = "Autonomous combo betting: no combos reached the " + MIN_PAYOUT_MULTIPLE
                        + "x payout floor with " + MIN_LEG_PROBABILITY + "%+ legs this cycle (checked "
                        + MAX_COLLECTIONS_TO_SURVEY + " collections). No bets placed.";
                notifyUser(jda, response);
                return response;
            }

            String shortlistJson = ToolServices.toJson(shortlist);
            String positionsJson = ToolServices.toJson(portfolioService.getPositions());

            // chatOnce, not chat: this cycle is fully self-contained, so it gets no benefit from
            // persisted conversation memory — persisting it would resend every past cycle's
            // prompt+report, uncached, on every future call forever (see chatOnce's javadoc). The
            // reduced SCHEDULER_TOOL_CLASSES ships ~4 tool schemas instead of 15.
            response = orchestratorService.chatOnce("scheduler:" + authorizedUserId,
                    buildPrompt(betSize, shortlistJson, positionsJson),
                    OrchestratorService.SCHEDULER_TOOL_CLASSES);
            if (response == null || response.isEmpty()) {
                response = "Autonomous combo betting: I couldn't generate a response this cycle.";
            }
        } catch (Exception e) {
            log.error("Autonomous combo betting failed", e);
            response = "Autonomous combo betting failed: " + e.getMessage();
        }

        notifyUser(jda, response);
        return response;
    }

    private String buildPrompt(BigDecimal betSize, String shortlistJson, String positionsJson) {
        return """
                Autonomously place up to %d REAL combo bets right now — this actually executes with \
                real money, no confirmation needed from me, that's the point of this scheduled task.

                I have ALREADY surveyed the market and RFQ-priced a shortlist of candidate combos for \
                you (below). Every candidate already clears the bar: each leg is a market-implied \
                favorite of at least %d%% on its own, and the whole combo pays at least %sx. A combo \
                may have several legs — that's how the payout is reached while keeping every leg a \
                strong favorite. You do NOT need to browse markets or price combos from scratch — \
                pick from this shortlist.

                PRE-PRICED CANDIDATE SHORTLIST (JSON):
                %s

                YOUR CURRENT POSITIONS (JSON):
                %s

                Do this:
                1. For the candidates you're seriously considering, research analytics with \
                GetTeamAnalyticsTool / GetIndividualAnalyticsTool (records, streaks, rankings, \
                head-to-head) for the teams/players in their legs — each candidate leg has a `label` \
                to identify who to look up. This is the judgment step: prefer candidates the stats \
                actually support, not just the raw price.
                2. Exclude any candidate whose leg's event is already held directly OR already appears \
                in another active combo's `underlyingLegEventTickers` in your positions above — never \
                reuse a leg across combos. Some existing combo positions show \
                `underlyingLegEventTickers` as null; that's a PERMANENT, KNOWN data gap (Kalshi can't \
                look up an old combo's legs), NOT a reason to skip anything — just check what you can \
                and move on.
                3. The shortlist is already ordered safest-first (highest combined probability among \
                combos that still hit the payout floor). Every candidate already pays enough, so \
                don't chase payout — just pick the ones the analytics most support, favoring the \
                earlier (safer) ones when the stats are comparable.
                4. Place up to %d of them with PlaceComboBetTool, passing the candidate's \
                `collectionTicker` and its legs (each leg's `eventTicker`, `marketTicker`, `side`) \
                verbatim, and targetDollars EXACTLY $%s (a precomputed fixed figure — pass it verbatim, \
                do not recalculate). If fewer than %d survive analytics/leg-conflict checks, place \
                fewer and say so — don't force a bet just to hit the count.

                The shortlist is already priced, so you normally won't need PriceComboTool; only use \
                it if you want to sanity-check an alternative combination. You do not have survey or \
                positions tools this cycle — everything you need is injected above.

                Report back in Discord, but keep it SHORT — 3-4 sentences per bet, no more, for each \
                bet you place. Per bet, cover only:
                - The matchup/leg list, side taken, American odds, and implied probability
                - The key stats/records/streaks behind why you picked it (briefly — not a full \
                paragraph, not a comparison against every alternative)
                - The outcome in one short clause: executed with contracts/price, or \
                declined/not_filled.
                Skip narrating the positions check or any other process detail unless something is \
                actually actionable (e.g. a real leg conflict found) — just the bottom line per bet.\
                """.formatted(NUMBER_OF_BETS, MIN_LEG_PROBABILITY, MIN_PAYOUT_MULTIPLE,
                        shortlistJson, positionsJson, NUMBER_OF_BETS, betSize, NUMBER_OF_BETS);
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for autonomous combo betting", error)),
                error -> log.error("Failed to retrieve Discord user for autonomous combo betting", error));
    }
}
