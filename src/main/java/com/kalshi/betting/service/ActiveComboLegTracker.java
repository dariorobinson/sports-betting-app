package com.kalshi.betting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kalshi.betting.web.dto.LegSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remembers which event tickers were used as legs in combo bets THIS APP placed, so future combo
 * selections can avoid reusing a leg that's still tied up in an active position. Kalshi's API has
 * no way to look up a combo market's composing legs after the fact — only we know that, since we
 * constructed it — so this is the only source of truth for "is this leg already in one of my
 * active combos."
 * <p>
 * Persisted to a small JSON file (under the user's home directory) so it survives app restarts.
 * Entries are pruned whenever {@link #activeLegsByMarket} is called, based on the caller's own
 * fresh list of currently-open position tickers (from {@code GetPositionsTool}) — this class never
 * calls Kalshi itself, it just tracks what it's told.
 */
@Service
public class ActiveComboLegTracker {

    private static final Logger log = LoggerFactory.getLogger(ActiveComboLegTracker.class);
    private static final Path STORE_PATH = Path.of(System.getProperty("user.home"), ".kalshi-bot",
            "active-combo-legs.json");
    private static final TypeReference<LinkedHashMap<String, List<String>>> STORE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();

    /** comboMarketTicker -> event tickers of the legs used to build it. */
    private Map<String, List<String>> byComboMarket;

    public ActiveComboLegTracker() {
        this.byComboMarket = load();
    }

    /** Call after successfully placing a combo bet. */
    public void record(String comboMarketTicker, List<LegSelection> legs) {
        lock.lock();
        try {
            List<String> eventTickers = legs.stream().map(LegSelection::eventTicker).distinct().toList();
            byComboMarket.put(comboMarketTicker, eventTickers);
            save();
            log.info("Recorded combo market {} as using legs {}", comboMarketTicker, eventTickers);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {comboMarketTicker -> legEventTickers} restricted to combos that are still open,
     * per {@code openMarketTickers} (the caller's fresh list of currently-open position tickers).
     * Prunes anything no longer open as a side effect, so the store doesn't grow unbounded.
     */
    public Map<String, List<String>> activeLegsByMarket(Set<String> openMarketTickers) {
        lock.lock();
        try {
            Map<String, List<String>> stillActive = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : byComboMarket.entrySet()) {
                if (openMarketTickers.contains(entry.getKey())) {
                    stillActive.put(entry.getKey(), entry.getValue());
                }
            }
            if (stillActive.size() != byComboMarket.size()) {
                byComboMarket = stillActive;
                save();
            }
            return stillActive;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Immediately removes a single combo market's leg-tracking entry — called by
     * {@code KalshiWebSocketClient} the instant a realtime position update shows that market's
     * position has closed, rather than waiting for the next {@link #activeLegsByMarket} call (which
     * only happens as a side effect of a REST positions check). Safe no-op if the market isn't
     * currently tracked. A single-key removal, not a reuse of {@link #activeLegsByMarket}, since
     * that method needs the *complete* open-ticker set to infer closure by absence — a single WS
     * event about one market shouldn't require a REST call to fetch the rest of the positions first.
     */
    public void pruneIfClosed(String marketTicker) {
        lock.lock();
        try {
            List<String> removed = byComboMarket.remove(marketTicker);
            if (removed != null) {
                save();
                log.info("Pruned combo market {} (legs {}) from active tracker — closed via WS signal",
                        marketTicker, removed);
            }
        } finally {
            lock.unlock();
        }
    }

    private Map<String, List<String>> load() {
        try {
            if (Files.exists(STORE_PATH)) {
                return mapper.readValue(STORE_PATH.toFile(), STORE_TYPE);
            }
        } catch (IOException e) {
            log.warn("Could not load active combo leg tracker store at {}, starting fresh: {}",
                    STORE_PATH, e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            mapper.writeValue(STORE_PATH.toFile(), byComboMarket);
        } catch (IOException e) {
            log.error("Could not persist active combo leg tracker store at {}: {}", STORE_PATH, e.getMessage());
        }
    }
}
