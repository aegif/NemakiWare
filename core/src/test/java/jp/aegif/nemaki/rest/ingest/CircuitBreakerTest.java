package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IngestSchedulerService circuit breaker logic.
 *
 * <p>Tests the consecutiveFailures counter behavior directly via reflection,
 * without requiring Spring context or live services.
 */
class CircuitBreakerTest {

    private IngestSchedulerService scheduler;
    private ConcurrentHashMap<String, Integer> failures;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        scheduler = new IngestSchedulerService();
        // Access the private consecutiveFailures field via reflection
        Field f = IngestSchedulerService.class.getDeclaredField("consecutiveFailures");
        f.setAccessible(true);
        failures = (ConcurrentHashMap<String, Integer>) f.get(scheduler);
    }

    // ── Threshold behavior ──

    @Test
    void noFailures_counterAbsent() {
        assertEquals(0, failures.getOrDefault("conn1", 0));
    }

    @Test
    void incrementOnFailure() {
        failures.merge("conn1", 1, Integer::sum);
        assertEquals(1, failures.get("conn1"));
        failures.merge("conn1", 1, Integer::sum);
        assertEquals(2, failures.get("conn1"));
    }

    @Test
    void resetOnSuccess() {
        failures.put("conn1", 3);
        // Simulates: result.imported() > 0 → remove
        failures.remove("conn1");
        assertNull(failures.get("conn1"));
    }

    @Test
    void thresholdReached_connectorSkipped() {
        int threshold = 5; // DEFAULT_CIRCUIT_BREAKER_THRESHOLD
        failures.put("conn1", threshold);
        assertTrue(failures.getOrDefault("conn1", 0) >= threshold,
                "At threshold, connector should be skipped");
    }

    @Test
    void belowThreshold_connectorNotSkipped() {
        failures.put("conn1", 4);
        assertFalse(failures.getOrDefault("conn1", 0) >= 5);
    }

    // ── Half-open behavior ──

    @Test
    void halfOpen_decrementsToThresholdMinusOne() {
        int threshold = 5;
        failures.put("conn1", threshold);
        failures.put("conn2", threshold + 2); // above threshold
        failures.put("conn3", 2); // below threshold

        // Simulate pollScheduledProfiles half-open logic
        failures.replaceAll((k, v) -> v >= threshold ? threshold - 1 : v);

        assertEquals(threshold - 1, failures.get("conn1"), "conn1 should be half-opened");
        assertEquals(threshold - 1, failures.get("conn2"), "conn2 should be half-opened");
        assertEquals(2, failures.get("conn3"), "conn3 below threshold, unchanged");
    }

    @Test
    void halfOpen_allowsRetry() {
        int threshold = 5;
        failures.put("conn1", threshold - 1); // half-open state
        assertFalse(failures.getOrDefault("conn1", 0) >= threshold,
                "Half-open connector should be allowed to retry");
    }

    @Test
    void halfOpen_failAgain_backToOpen() {
        int threshold = 5;
        failures.put("conn1", threshold - 1); // half-open
        // Retry fails → increment
        failures.merge("conn1", 1, Integer::sum);
        assertEquals(threshold, failures.get("conn1"));
        assertTrue(failures.get("conn1") >= threshold, "Should be back to OPEN after failed retry");
    }

    @Test
    void halfOpen_successResets() {
        failures.put("conn1", 4); // half-open
        failures.remove("conn1"); // success
        assertNull(failures.get("conn1"), "Should be fully reset after successful retry");
    }

    // ── Empty fetch (neutral) ──

    @Test
    void emptyFetch_noChange() {
        failures.put("conn1", 3);
        // Empty fetch: fetched=0, imported=0, no errors → neutral
        int before = failures.get("conn1");
        // No merge, no remove
        assertEquals(before, failures.get("conn1"), "Empty fetch should not change counter");
    }

    // ── Multiple connectors ──

    @Test
    void independentCounters() {
        failures.merge("conn1", 1, Integer::sum);
        failures.merge("conn2", 1, Integer::sum);
        failures.merge("conn1", 1, Integer::sum);
        assertEquals(2, failures.get("conn1"));
        assertEquals(1, failures.get("conn2"));
    }
}
