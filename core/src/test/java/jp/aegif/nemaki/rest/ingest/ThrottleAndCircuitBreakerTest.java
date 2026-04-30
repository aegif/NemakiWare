package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FetchSupport.throttle cancellation and circuit breaker constants.
 */
class ThrottleAndCircuitBreakerTest {

    // ── throttle cancellation ──

    @Test
    void throttle_interruptedThread_throwsCancellation() {
        FetchSupport support = new FetchSupport();
        Thread.currentThread().interrupt();
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> support.throttle(0));
            assertTrue(ex.getMessage().contains("cancelled"));
        } finally {
            // Clear interrupt flag
            Thread.interrupted();
        }
    }

    @Test
    void throttle_zeroDelay_doesNotSleep() {
        FetchSupport support = new FetchSupport();
        long start = System.currentTimeMillis();
        support.throttle(0);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 100, "throttle(0) should return immediately, took " + elapsed + "ms");
    }

    @Test
    void throttle_shortDelay_sleeps() {
        FetchSupport support = new FetchSupport();
        long start = System.currentTimeMillis();
        support.throttle(50);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 40, "throttle(50) should sleep ~50ms, took " + elapsed + "ms");
    }

    // ── calculateThrottleDelayMs edge cases ──

    @Test
    void throttleDelay_negativeRpm_returnsZero() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(-10);
        assertEquals(0, FetchSupport.calculateThrottleDelayMs(c));
    }

    @Test
    void throttleDelay_oneRpm_60seconds() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(1);
        assertEquals(60_000, FetchSupport.calculateThrottleDelayMs(c));
    }

    @Test
    void throttleDelay_600rpm_100ms() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(600);
        assertEquals(100, FetchSupport.calculateThrottleDelayMs(c));
    }

    // ── FetchSupport.addError cap ──

    @Test
    void addError_exactlyAtCap_addsTruncationMessage() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) errors.add("e");
        FetchSupport.addError(errors, "overflow");
        assertEquals(10_001, errors.size());
        assertTrue(errors.get(10_000).contains("truncated"));
        // Further adds should be silently ignored
        FetchSupport.addError(errors, "ignored");
        assertEquals(10_001, errors.size());
    }

    // ── sanitizeSubject edge cases ──

    @Test
    void sanitizeSubject_allIllegalChars() {
        assertEquals("_________", FetchSupport.sanitizeSubject("/\\:*?\"<>|"));
    }

    @Test
    void sanitizeSubject_japaneseTitlePreserved() {
        assertEquals("テスト文書_v2", FetchSupport.sanitizeSubject("テスト文書_v2"));
    }

    @Test
    void sanitizeSubject_mixedContent() {
        assertEquals("会議_資料 2026-04-01", FetchSupport.sanitizeSubject("会議/資料 2026-04-01"));
    }
}
