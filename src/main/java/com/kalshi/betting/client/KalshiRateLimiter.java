package com.kalshi.betting.client;

import java.util.concurrent.TimeUnit;

/**
 * App-wide throttle for every outbound Kalshi API call, blocking the calling thread until its turn
 * comes up rather than capping concurrency alone. Kalshi enforces a per-endpoint token-bucket rate
 * limit (see {@code openapi.yaml}: "Rate limit: 2 tokens per request") — a simple concurrency cap
 * (e.g. a semaphore of size 4) does NOT protect against that, since 4 threads firing requests back
 * to back as fast as each one completes can still exceed tokens/second if each call is fast. This
 * instead has every caller "reserve" the next free time slot at a fixed rate, so the real request
 * rate across the whole app — regardless of how many threads are calling concurrently — never
 * exceeds {@link #PERMITS_PER_SECOND}.
 * <p>
 * Added after a confirmed production incident: {@code SportsCatalogService#getSportBoard("SOCCER")}
 * fires one Kalshi call per series via {@code parallelStream()} — 78 series once the soccer
 * competition count grew from 8 to 14 — which reliably triggered a wave of HTTP 429s with no
 * throttle in place. The rate chosen here is deliberately conservative since Kalshi's exact bucket
 * capacity/refill rate isn't published, only the per-request token cost.
 */
final class KalshiRateLimiter {

    private static final double PERMITS_PER_SECOND = 5.0;
    private static final long INTERVAL_NANOS = (long) (1_000_000_000.0 / PERMITS_PER_SECOND);

    private static final Object LOCK = new Object();
    private static long nextFreeNanos = System.nanoTime();

    private KalshiRateLimiter() {
    }

    /** Blocks the calling thread, if needed, until it's this request's turn under the shared rate. */
    static void acquire() {
        long waitNanos;
        synchronized (LOCK) {
            long now = System.nanoTime();
            long start = Math.max(now, nextFreeNanos);
            nextFreeNanos = start + INTERVAL_NANOS;
            waitNanos = start - now;
        }
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
