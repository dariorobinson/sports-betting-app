package com.kalshi.betting.ws;

import com.kalshi.betting.ws.dto.QuoteExecutedMsg;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The race mechanism between {@code ComboService}'s REST poll and the WebSocket's realtime
 * {@code quote_executed} events. A caller waiting on a specific (rfqId, quoteId) registers a
 * future via {@link #awaitExecution}; {@link KalshiWebSocketClient} completes it the instant a
 * matching event arrives. Purely additive — if the WebSocket never fires (disabled, disconnected,
 * or the event is simply late), the future just never completes and the REST poll proceeds as it
 * always did.
 */
@Service
public class QuoteExecutionSignal {

    private final ConcurrentHashMap<QuoteKey, CompletableFuture<QuoteExecutedMsg>> pending =
            new ConcurrentHashMap<>();

    public record QuoteKey(String rfqId, String quoteId) {
    }

    public CompletableFuture<QuoteExecutedMsg> awaitExecution(String rfqId, String quoteId) {
        return pending.computeIfAbsent(new QuoteKey(rfqId, quoteId), k -> new CompletableFuture<>());
    }

    /** Called by {@link KalshiWebSocketClient} when a matching quote_executed event arrives. A
     *  no-op if nobody is currently waiting on this key (already resolved via REST, already timed
     *  out and moved on, or a duplicate/late delivery). */
    public void complete(QuoteExecutedMsg msg) {
        CompletableFuture<QuoteExecutedMsg> future = pending.remove(new QuoteKey(msg.rfqId(), msg.quoteId()));
        if (future != null) {
            future.complete(msg);
        }
    }

    /** Deregisters a wait without completing it — always called once the caller stops waiting
     *  (whether the REST poll or the WS signal ultimately resolved things), so the map never
     *  accumulates stale entries. */
    public void cancel(String rfqId, String quoteId) {
        pending.remove(new QuoteKey(rfqId, quoteId));
    }
}
