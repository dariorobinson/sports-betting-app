package com.kalshi.betting.service;

import com.kalshi.betting.web.dto.LegSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActiveComboLegTracker} persists to a fixed path under the real user's home directory
 * (there's no dependency-injected override for it — see the class itself). To avoid a test run
 * corrupting real tracked state on a developer's machine, this backs up whatever's there before
 * each test and restores it afterward, rather than changing the store's storage location.
 */
class ActiveComboLegTrackerPruneTest {

    private static final Path STORE_PATH = Path.of(System.getProperty("user.home"), ".kalshi-bot",
            "active-combo-legs.json");

    private byte[] backup;
    private boolean existedBefore;

    @BeforeEach
    void backupRealStore() throws IOException {
        existedBefore = Files.exists(STORE_PATH);
        if (existedBefore) {
            backup = Files.readAllBytes(STORE_PATH);
        }
    }

    @AfterEach
    void restoreRealStore() throws IOException {
        if (existedBefore) {
            Files.createDirectories(STORE_PATH.getParent());
            Files.write(STORE_PATH, backup);
        } else {
            Files.deleteIfExists(STORE_PATH);
        }
    }

    @Test
    void pruneIfClosedRemovesTrackedCombo() {
        ActiveComboLegTracker tracker = new ActiveComboLegTracker();
        tracker.record("COMBO-MKT-TEST-1", List.of(
                new LegSelection("EVT-A", "EVT-A-MKT", "YES"),
                new LegSelection("EVT-B", "EVT-B-MKT", "YES")));

        Map<String, List<String>> beforePrune = tracker.activeLegsByMarket(Set.of("COMBO-MKT-TEST-1"));
        assertTrue(beforePrune.containsKey("COMBO-MKT-TEST-1"));

        tracker.pruneIfClosed("COMBO-MKT-TEST-1");

        Map<String, List<String>> afterPrune = tracker.activeLegsByMarket(Set.of("COMBO-MKT-TEST-1"));
        assertFalse(afterPrune.containsKey("COMBO-MKT-TEST-1"));
    }

    @Test
    void pruneIfClosedOnUnknownMarketIsNoOp() {
        ActiveComboLegTracker tracker = new ActiveComboLegTracker();
        // Should not throw, and should not affect an unrelated tracked entry.
        tracker.record("COMBO-MKT-TEST-2", List.of(new LegSelection("EVT-C", "EVT-C-MKT", "NO")));

        tracker.pruneIfClosed("COMBO-MKT-DOES-NOT-EXIST");

        Map<String, List<String>> stillActive = tracker.activeLegsByMarket(Set.of("COMBO-MKT-TEST-2"));
        assertTrue(stillActive.containsKey("COMBO-MKT-TEST-2"));
    }
}
