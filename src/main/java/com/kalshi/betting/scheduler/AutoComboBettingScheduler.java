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

    /** Fraction of current available balance risked per bet — user-specified: 4% (doubled from 2%). */
    private static final BigDecimal BET_SIZE_FRACTION = new BigDecimal("0.04");
    private static final int NUMBER_OF_BETS = 2;
    /** Minimum COMBINED implied probability a whole combo must clear — user-specified: 60%. A
     *  combo's combined probability is roughly the product of its legs, so two ~65% team favorites
     *  land around 42% (a coin-flip lottery ticket, which is what we're moving away from). Because
     *  payout multiple ≈ 1/probability, a 60% floor caps the payout at ~1.67x — that's the
     *  intended trade: more likely to actually hit, in exchange for a smaller multiple. */
    private static final int MIN_COMBO_PROBABILITY = 60;
    /** Minimum implied probability of each INDIVIDUAL leg — user-specified: 70%. Every leg must be
     *  a clear favorite on its own; this screens out coin-flip legs and, combined with the 60%
     *  combo floor, naturally favors sports with genuinely strong favorites (tennis especially)
     *  over near-even team matchups. Necessary but not sufficient: three 70% legs only combine to
     *  ~34%, so in practice the legs that actually clear the combo floor are stronger than 70%. */
    private static final int MIN_LEG_PROBABILITY = 70;
    /** How many sports/series the initial browse phase may survey before narrowing down, ON TOP OF
     *  the mandatory ATP/WTA tennis check (see buildPrompt) — tennis doesn't count against this cap
     *  since it's a single mandatory check, not a competing choice. A real cycle once burned its
     *  ENTIRE tool-call budget just browsing NFL+WNBA+MLB+soccer prices and never reached analytics,
     *  real pricing, or placement — a single busy series (e.g. a full NFL week) can be huge on its
     *  own. Capping this leaves room for the steps that actually matter. */
    private static final int MAX_SERIES_TO_SURVEY = 2;

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
            // chatOnce, not chat: this cycle is fully self-contained (fresh positions/pricing/
            // analytics every time per instructions.md), so it gets no benefit from persisted
            // conversation memory — but persisting it anyway would mean resending every past
            // cycle's prompt+report, uncached, on every future call forever. See chatOnce's javadoc.
            response = orchestratorService.chatOnce("scheduler:" + authorizedUserId, buildPrompt(betSize));
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

    private String buildPrompt(BigDecimal betSize) {
        return """
                Autonomously place %d REAL combo bets right now — this actually executes with real \
                money, no confirmation needed from me, that's the point of this scheduled task.

                Bet exactly $%s on EACH of the %d bets (this is a precomputed, fixed dollar figure — \
                pass it verbatim as targetDollars to PlaceComboBetTool, do not recalculate or estimate \
                it yourself).

                Selection criteria — READ CAREFULLY, this is the priority this cycle. Build combos \
                out of STRONG individual favorites so the combined probability stays high; do NOT \
                chase big payout multiples built on near-coin-flip legs.
                (a) Each individual leg must have a market-implied probability of at least %d%% on \
                its own (a clear favorite). Drop any candidate leg below that.
                (b) The COMBINED combo (all legs together, from PriceComboTool) must have a market-\
                implied probability of at least %d%%. Legs multiply, so two ~65%% favorites combine \
                to only ~42%% — that sub-coin-flip kind of combo is exactly what we're moving away \
                from. You'll typically need legs well above the leg minimum to clear this (roughly \
                ~78%% each for a 2-leg combo, or ~85%% each for a 3-leg combo).
                (c) Among combos clearing both (a) and (b), prefer the best PAYOUT — the combo whose \
                combined probability sits closest to (but not below) the %d%% floor. Payout multiple \
                is about 1 divided by probability, so a combo right at the floor pays about %sx while \
                a safer ~72%% combo pays only ~1.38x. Adding a strong third leg is the main way to \
                lift the payout back up while staying above the floor: three ~85%% favorites combine \
                to ~61%% (paying ~1.64x), which beats two ~85%% favorites at ~72%% (paying ~1.38x). \
                So prefer a 3-leg combo when you can find three legs that each clear (a) and together \
                clear (b); fall back to 2 legs otherwise. Tennis (ATP/WTA) is usually the best source \
                of these strong favorites — lean on it. Use PriceComboTool on several candidates and \
                compare their real combined quotes before committing.

                Always check ATP and WTA (tennis) as part of every survey — call ListSportsTool and \
                look for any currently open tennis series, then ListGamesTool on whichever are open. \
                Tennis has real edge worth checking and has been getting crowded out by always-\
                in-season team sports like MLB/WNBA — treat checking it as mandatory every single \
                cycle, not just an option competing for a survey slot. If no ATP/WTA series are \
                currently open (e.g. between tournaments), that's fine — note it in your report and \
                move on, don't force a tennis pick that isn't actually there.

                Beyond tennis, keep the rest of the survey tight: browse at most %d additional \
                sports/series (e.g. ListGamesTool for %d more series, not every sport available) \
                before narrowing down to candidates. Surveying broadly across many series eats your \
                entire tool-call budget before you ever reach analytics or real combo pricing — pick \
                the %d series that look most promising by price alone and commit to them; don't keep \
                browsing more series "just in case." The steps after narrowing down (analytics, real \
                pricing, placement) matter more than survey breadth and need most of your budget.

                Follow the mandatory checks from your instructions in full before placing anything: \
                (1) call GetPositionsTool and exclude any candidate leg whose event is already held \
                directly OR already appears in another active combo's underlyingLegEventTickers — \
                reusing a leg across combos has happened before and must not happen again, treat it \
                as a hard rule for whichever candidates you can actually check. Some existing combo \
                positions will show underlyingLegEventTickers as null — that's a PERMANENT, KNOWN \
                data gap (Kalshi has no way to look up an old combo's legs, more research will never \
                fix it), NOT a reason to stop early or skip anything below. Check what you can, note \
                the gap in your final report if relevant, and keep going regardless; (2) research \
                analytics (records, streaks, rankings, head-to-head) for every team/player in every \
                leg you're considering, not just the ones you end up picking.

                You MUST actually call GetTeamAnalyticsTool (or GetIndividualAnalyticsTool) at least \
                once AND PriceComboTool at least once before you are done — stopping before making \
                those calls is not an acceptable outcome for any reason, including incomplete \
                position data. If, after actually pricing real candidates, fewer than the target \
                number qualify, that's a legitimate reason to place fewer bets — but you must reach \
                the pricing step first every single cycle.

                If fewer than %d qualifying combos can be found and priced this cycle, place as many \
                as you can and say so — don't force a bet that doesn't meet the criteria just to hit \
                the count, and don't reuse a leg just to fill the quota either.

                Report back in Discord, but keep it SHORT — 3-4 sentences per bet, no more, for each \
                of the (up to %d) bets. Do the full research/retry/comparison work as instructed \
                above, but don't narrate any of it in the report. Per bet, cover only:
                - The matchup/leg list, side taken, American odds, and implied probability
                - The key stats/records/streaks behind why you picked it (this is the "why," briefly \
                — not a full paragraph, not a comparison against every alternative you considered)
                - The outcome in one short clause: executed with contracts/price, or \
                declined/not_filled — no need to explain every retry attempt or exact reason it \
                didn't fill.
                Skip narrating the tennis survey, the positions check, or any other process detail \
                unless something is actually actionable (e.g. a real leg conflict found) — just the \
                bottom line per bet.\
                """.formatted(NUMBER_OF_BETS, betSize, NUMBER_OF_BETS, MIN_LEG_PROBABILITY,
                        MIN_COMBO_PROBABILITY, MIN_COMBO_PROBABILITY, maxPayoutMultipleAtFloor(),
                        MAX_SERIES_TO_SURVEY, MAX_SERIES_TO_SURVEY, MAX_SERIES_TO_SURVEY,
                        NUMBER_OF_BETS, NUMBER_OF_BETS);
    }

    /** Max payout multiple implied by the combo-probability floor (1 / probability) — e.g. a 60%
     *  floor -> ~1.67x. Shown in the prompt so the model knows the payout it should be aiming near
     *  (the best-paying end of the qualifying range is the combo sitting right at the 60% floor). */
    private static String maxPayoutMultipleAtFloor() {
        BigDecimal probability = BigDecimal.valueOf(MIN_COMBO_PROBABILITY)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return BigDecimal.ONE.divide(probability, 2, RoundingMode.HALF_UP).toPlainString();
    }

    private void notifyUser(JDA jda, String message) {
        jda.retrieveUserById(authorizedUserId).queue(
                user -> user.openPrivateChannel().queue(
                        channel -> DiscordMessages.sendChunked(channel, message),
                        error -> log.error("Failed to open Discord DM channel for autonomous combo betting", error)),
                error -> log.error("Failed to retrieve Discord user for autonomous combo betting", error));
    }
}
