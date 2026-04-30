package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FetchSupport static utilities.
 */
class FetchSupportTest {

    // ── sanitizeSubject ──

    @Test
    void sanitizeSubject_replacesIllegalCharacters() {
        assertEquals("a_b_c_d", FetchSupport.sanitizeSubject("a/b\\c:d"));
        assertEquals("hello_world", FetchSupport.sanitizeSubject("hello*world"));
        assertEquals("test_file_name", FetchSupport.sanitizeSubject("test?file\"name"));
    }

    @Test
    void sanitizeSubject_nullReturnsUntitled() {
        assertEquals("untitled", FetchSupport.sanitizeSubject(null));
    }

    @Test
    void sanitizeSubject_blankReturnsUntitled() {
        assertEquals("untitled", FetchSupport.sanitizeSubject("   "));
    }

    @Test
    void sanitizeSubject_normalStringUnchanged() {
        assertEquals("normal-subject_123", FetchSupport.sanitizeSubject("normal-subject_123"));
    }

    // ── truncateForContext ──

    @Test
    void truncateForContext_shortStringUnchanged() {
        assertEquals("hello", FetchSupport.truncateForContext("hello"));
    }

    @Test
    void truncateForContext_nullReturnsEmpty() {
        assertEquals("", FetchSupport.truncateForContext(null));
    }

    @Test
    void truncateForContext_longStringTruncated() {
        String longStr = "x".repeat(3000);
        String result = FetchSupport.truncateForContext(longStr);
        assertEquals(2001, result.length()); // 2000 + "…"
        assertTrue(result.endsWith("…"));
    }

    @Test
    void truncateForContext_exactLimitNotTruncated() {
        String exact = "x".repeat(2000);
        assertEquals(exact, FetchSupport.truncateForContext(exact));
    }

    // ── guessMimeType ──

    @Test
    void guessMimeType_nullReturnsNull() {
        assertNull(FetchSupport.guessMimeType(null));
    }

    @Test
    void guessMimeType_knownExtension() {
        // probeContentType is OS-dependent, but .txt should work on most systems
        String mime = FetchSupport.guessMimeType("test.txt");
        // May be null on some CI environments, so just verify no exception
        if (mime != null) {
            assertTrue(mime.contains("text"), "Expected text/ MIME for .txt, got: " + mime);
        }
    }

    // ── addError ──

    @Test
    void addError_addsToList() {
        List<String> errors = new ArrayList<>();
        FetchSupport.addError(errors, "error1");
        FetchSupport.addError(errors, "error2");
        assertEquals(2, errors.size());
        assertEquals("error1", errors.get(0));
    }

    @Test
    void addError_capsAt10000() {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 10_002; i++) {
            FetchSupport.addError(errors, "err-" + i);
        }
        assertEquals(10_001, errors.size()); // 10000 + 1 truncation message
        assertTrue(errors.get(10_000).contains("truncated"));
    }

    // ── calculateThrottleDelayMs ──

    @Test
    void calculateThrottleDelayMs_noRateLimit() {
        ConnectorDefinition c = new ConnectorDefinition();
        assertEquals(0, FetchSupport.calculateThrottleDelayMs(c));
    }

    @Test
    void calculateThrottleDelayMs_60rpm() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(60);
        assertEquals(1000, FetchSupport.calculateThrottleDelayMs(c));
    }

    @Test
    void calculateThrottleDelayMs_120rpm() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(120);
        assertEquals(500, FetchSupport.calculateThrottleDelayMs(c));
    }

    @Test
    void calculateThrottleDelayMs_zeroRpm() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setRateLimitRpm(0);
        assertEquals(0, FetchSupport.calculateThrottleDelayMs(c));
    }

    // ── FetchResult ──

    @Test
    void fetchResult_convenienceConstructor() {
        FetchResult r = new FetchResult(10, 5, List.of("err"));
        assertEquals(10, r.fetched());
        assertEquals(5, r.imported());
        assertEquals(0, r.skipped());
        assertTrue(r.hasErrors());
    }

    @Test
    void fetchResult_fullConstructor() {
        FetchResult r = new FetchResult(10, 5, 3, List.of());
        assertEquals(3, r.skipped());
        assertFalse(r.hasErrors());
    }

    @Test
    void fetchResult_nullErrors() {
        FetchResult r = new FetchResult(0, 0, 0, null);
        assertFalse(r.hasErrors());
    }
}
