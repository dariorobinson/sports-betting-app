package com.kalshi.betting.ws;

import com.kalshi.betting.ws.dto.QuoteExecutedMsg;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteExecutionSignalTest {

    @Test
    void completeResolvesTheMatchingFuture() throws Exception {
        QuoteExecutionSignal signal = new QuoteExecutionSignal();
        CompletableFuture<QuoteExecutedMsg> future = signal.awaitExecution("rfq-1", "quote-1");

        QuoteExecutedMsg msg = new QuoteExecutedMsg("quote-1", "rfq-1", "MKT-1", "order-1", "2026-01-01T00:00:00Z");
        signal.complete(msg);

        assertTrue(future.isDone());
        assertEquals(msg, future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void completeWithNonMatchingKeyIsNoOp() {
        QuoteExecutionSignal signal = new QuoteExecutionSignal();
        CompletableFuture<QuoteExecutedMsg> future = signal.awaitExecution("rfq-1", "quote-1");

        signal.complete(new QuoteExecutedMsg("quote-DIFFERENT", "rfq-DIFFERENT", "MKT-1", "order-1", "ts"));

        assertFalse(future.isDone());
    }

    @Test
    void cancelRemovesWithoutCompleting() {
        QuoteExecutionSignal signal = new QuoteExecutionSignal();
        CompletableFuture<QuoteExecutedMsg> future = signal.awaitExecution("rfq-1", "quote-1");

        signal.cancel("rfq-1", "quote-1");

        assertFalse(future.isDone());
        // A late/duplicate event after cancel re-registers a *new* future rather than resurrecting
        // the cancelled one — complete() for the old key is now a safe no-op.
        signal.complete(new QuoteExecutedMsg("quote-1", "rfq-1", "MKT-1", "order-1", "ts"));
        assertFalse(future.isDone());
    }

    @Test
    void timesOutIfNeverCompleted() {
        QuoteExecutionSignal signal = new QuoteExecutionSignal();
        CompletableFuture<QuoteExecutedMsg> future = signal.awaitExecution("rfq-1", "quote-1");

        assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
    }
}
