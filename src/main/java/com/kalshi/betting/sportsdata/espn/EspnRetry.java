package com.kalshi.betting.sportsdata.espn;

import java.util.function.Supplier;

/**
 * Retries a transient ESPN hidden-API failure (a timeout, a brief 5xx/429) a bounded number of
 * times before giving up — confirmed in production: a research session doing a burst of ~20
 * roster lookups (player-prop + injury-report checks across many games) failed across EVERY one of
 * them, while unrelated ESPN endpoints called in the SAME session (standings, team schedule)
 * succeeded, and manually re-running the identical roster calls afterward succeeded immediately with
 * no code change. That's the signature of a transient rate-limit/hiccup on an undocumented API under
 * back-to-back load, not a genuine outage or a parsing bug — worth absorbing with a quick retry
 * rather than surfacing as a tool failure on the first hiccup.
 */
final class EspnRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 500;

    private EspnRetry() {
    }

    static <T> T call(Supplier<T> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastFailure;
    }
}
