package com.kalshi.betting.scheduler;

import com.kalshi.betting.discord.DiscordMessages;
import com.kalshi.betting.orchestrator.OrchestratorService;
import com.kalshi.betting.service.PortfolioService;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Autonomously PLACES real combo bets every 6 hours — this actually risks real money with no human
 * confirmation step (there used to be a separate twice-daily scheduler that only *recommended*
 * plays; it was removed in favor of this one doing everything, recommendation-quality reasoning
 * included). Bet size is computed here in Java (a fixed fraction of current available balance)
 * rather than left to the model: precise arithmetic on real money shouldn't be delegated to an LLM.
 * The exact dollar figure is embedded directly in the prompt and the model is instructed to pass it
 * through to {@code PlaceComboBetTool} verbatim, not recompute or estimate it.
 * <p>
 * There is deliberately no loss-based circuit breaker (per explicit user choice) — only the
 * per-bet sizing rule bounds risk. Every run, successful or not, DMs a summary so there's always a
 * record of what happened.
 */
@Component
public class AutoComboBettingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoComboBettingScheduler.class);

    /** Fraction of current available balance risked per bet — user-specified: 2%. */
    private static final BigDecimal BET_SIZE_FRACTION = new BigDecimal("0.02");
    private static final int NUMBER_OF_BETS = 2;
    /** Minimum implied payout multiple (1/price) a combo must clear — user-specified: 1.5x. */
    private static final String MIN_PAYOUT_MULTIPLE = "1.5";

    private final ObjectProvider<JDA> jdaProvider;
    private final OrchestratorService orchestratorService;
    private final PortfolioService portfolioService;
    private final String authorizedUserId;

    public AutoComboBettingScheduler(ObjectProvider<JDA> jdaProvider,
                                       OrchestratorService orchestratorService,
                                       PortfolioService portfolioService,
                                       @Value("${discord.authorized-user-id}") String authorizedUserId) {
        this.jdaProvider = jdaProvider;
        this.orchestratorService = orchestratorService;
        this.portfolioService = portfolioService;
        this.authorizedUserId = authorizedUserId;
    }

    @Scheduled(cron = "0 0 0,6,12,18 * * *", zone = "America/Chicago")
    public void run() {
        JDA jda = jdaProvider.getIfAvailable();
        if (jda == null) {
            log.warn("Skipping autonomous combo betting — Discord bot is not configured (no DISCORD_BOT_TOKEN).");
            return;
        }
        if (authorizedUserId == null || authorizedUserId.isBlank()) {
            log.warn("Skipping autonomous combo betting — DISCORD_AUTHORIZED_USER_ID is not set.");
            return;
        }

        String response;
        try {
            BigDecimal balance = new BigDecimal(portfolioService.getBalance().balanceDollars());
            BigDecimal betSize = balance.multiply(BET_SIZE_FRACTION).setScale(2, RoundingMode.DOWN);
            if (betSize.signum() <= 0) {
                log.warn("Skipping autonomous combo betting — computed bet size is $0 (balance: ${})", balance);
                notifyUser(jda, "Autonomous combo betting skipped this cycle — balance ($" + balance
                        + ") is too low to size a bet.");
                return;
            }

            log.info("Running autonomous combo betting for user {} — balance=${}, betSize=${}",
                    authorizedUserId, balance, betSize);
            response = orchestratorService.chat(authorizedUserId, buildPrompt(betSize));
            if (response == null || response.isEmpty()) {
                response = "Autonomous combo betting: I couldn't generate a response this cycle.";
            }
        } catch (Exception e) {
            log.error("Autonomous combo betting failed", e);
            response = "Autonomous combo betting failed: " + e.getMessage();
        }

        notifyUser(jda, response);
    }

    private String buildPrompt(BigDecimal betSize) {
        return """
                Autonomously place %d REAL combo bets right now — this actually executes with real \
                money, no confirmation needed from me, that's the point of this scheduled task.

                Bet exactly $%s on EACH of the %d bets (this is a precomputed, fixed dollar figure — \
                pass it verbatim as targetDollars to PlaceComboBetTool, do not recalculate or estimate \
                it yourself).

                Selection criteria: only consider combos with an implied payout multiple of at least \
                %sx (equivalently, implied probability at or below ~%s%%). Among combos that clear \
                that bar, prefer the SAFEST one — the one closest to %sx / highest probability — not \
                the highest payout multiple available. Use PriceComboTool to check real quoted prices \
                on multiple candidates before picking.

                Follow the mandatory checks from your instructions in full before placing anything: \
                (1) call GetPositionsTool and exclude any candidate leg whose event is already held \
                directly OR already appears in another active combo's underlyingLegEventTickers — \
                reusing a leg across combos has happened before and must not happen again, treat it \
                as a hard rule; (2) research analytics (records, streaks, rankings, head-to-head) for \
                every team/player in every leg you're considering, not just the ones you end up \
                picking.

                If fewer than %d qualifying combos can be found and priced this cycle, place as many \
                as you can and say so — don't force a bet that doesn't meet the criteria just to hit \
                the count, and don't reuse a leg just to fill the quota either.

                Report back in Discord with the SAME level of research detail you'd give for a \
                regular play recommendation — this is a placement report, not a one-line \
                announcement. For each of the (up to %d) bets, whether it executed or not, include:
                - The league/tour and the full matchup or leg list, with the side you took on each leg
                - The American odds and implied probability for the combo as a whole
                - A real, specific reasoning paragraph: each leg's relevant record/streak/ranking/\
                head-to-head, and why THIS combination beat the other real candidates you priced \
                (name at least one alternative you passed on and why)
                - The actual outcome: for `executed`, the real contracts/price/total cost from \
                PlaceComboBetTool's result; for `declined`/`stalled_cancelled`/`not_filled`, state \
                that plainly and say why — these are normal outcomes, not failures to apologize for, \
                but don't gloss over them either.\
                """.formatted(NUMBER_OF_BETS, betSize, NUMBER_OF_BETS, MIN_PAYOUT_MULTIPLE,
                        impliedProbabilityCeiling(), MIN_PAYOUT_MULTIPLE, NUMBER_OF_BETS, NUMBER_OF_BETS);
    }

    /** 1 / payoutMultiple as a whole-number percentage — e.g. 1.5x -> 67%. */
    private static String impliedProbabilityCeiling() {
        BigDecimal multiple = new BigDecimal(MIN_PAYOUT_MULTIPLE);
        return BigDecimal.ONE.divide(multiple, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for autonomous combo betting", error)),
                error -> log.error("Failed to retrieve Discord user for autonomous combo betting", error));
    }
}
