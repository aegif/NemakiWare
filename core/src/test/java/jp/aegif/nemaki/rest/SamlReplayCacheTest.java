package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SamlReplayCache}.
 *
 * <p>The replay cache is the post-RC13 defence against captured-Response
 * replay within the SAML validity window: even if an attacker holds a
 * still-valid signed Assertion, the second presentation must be refused.
 */
class SamlReplayCacheTest {

    @Test
    void firstUseIsAccepted_secondIsRejected() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            String id = "_response-" + System.nanoTime();
            assertFalse(cache.isReplayAndRecord(id), "first use must be accepted");
            assertTrue(cache.isReplayAndRecord(id), "second use must be rejected as replay");
        } finally {
            cache.clear();
        }
    }

    @Test
    void differentIdsAreIndependent() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            assertFalse(cache.isReplayAndRecord("a"));
            assertFalse(cache.isReplayAndRecord("b"));
            assertFalse(cache.isReplayAndRecord("c"));
            assertTrue(cache.isReplayAndRecord("a"));
            assertEquals(3, cache.size());
        } finally {
            cache.clear();
        }
    }

    @Test
    void blankIdsAreTreatedAsReplayDeny() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            // Defensive: an unidentifiable Response is suspicious.
            assertTrue(cache.isReplayAndRecord(null));
            assertTrue(cache.isReplayAndRecord(""));
            assertTrue(cache.isReplayAndRecord("   "));
            // No entries are stored for blank ids.
            assertEquals(0, cache.size());
        } finally {
            cache.clear();
        }
    }

    @Test
    void expiredEntriesAreReusable() throws InterruptedException {
        // 2-second TTL with 3-second sleep so the boundary is well clear of
        // the seconds-resolution clock used by getEpochSecond().
        SamlReplayCache cache = new SamlReplayCache(2);
        try {
            String id = "expiring";
            assertFalse(cache.isReplayAndRecord(id));
            assertTrue(cache.isReplayAndRecord(id));
            // Wait > TTL, then try again — must be accepted because the cache TTL
            // is itself the replay window. (Production TTL is 15min, longer than
            // the typical SAML validity window, so this case won't fire in real use.)
            Thread.sleep(3000);
            assertFalse(cache.isReplayAndRecord(id),
                    "after TTL the same id should be accepted again");
        } finally {
            cache.clear();
        }
    }

    @Test
    void singletonInstanceShared() {
        SamlReplayCache a = SamlReplayCache.getInstance();
        SamlReplayCache b = SamlReplayCache.getInstance();
        assertSame(a, b);
    }
}
