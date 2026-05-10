package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for the two-phase {@link SamlReplayCache} contract.
 *
 * <p>The split between {@link SamlReplayCache#isAlreadySeen(String)} and
 * {@link SamlReplayCache#recordIfNew(String)} closes the
 * "InResponseTo-failure poisons replay state" attack: replay commits must
 * happen only after every other validation has succeeded.
 */
class SamlReplayCacheTest {

    @Test
    void notSeenBeforeFirstRecord() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            String id = "_response-" + System.nanoTime();
            assertFalse(cache.isAlreadySeen(id), "fresh ID must not be flagged");
            assertTrue(cache.recordIfNew(id), "fresh ID must commit successfully");
            assertTrue(cache.isAlreadySeen(id), "after commit the ID must be flagged");
        } finally {
            cache.clear();
        }
    }

    @Test
    void recordIfNewIsAtomicAgainstDuplicateCommit() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            String id = "_dup";
            assertTrue(cache.recordIfNew(id), "first commit succeeds");
            assertFalse(cache.recordIfNew(id), "second commit must fail (replay)");
        } finally {
            cache.clear();
        }
    }

    @Test
    void lookupOnlyDoesNotCommit_failedValidationDoesNotPoisonState() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            // Simulate the verifier's read-only phase: many isAlreadySeen()
            // calls must NOT promote anything to consumed.
            String id = "_fresh";
            for (int i = 0; i < 5; i++) {
                assertFalse(cache.isAlreadySeen(id),
                        "isAlreadySeen must remain false until a successful recordIfNew");
            }
            // Simulate verifier deciding to abort (e.g. InResponseTo mismatch)
            // and never calling recordIfNew. The legitimate retry must still
            // be accepted.
            assertTrue(cache.recordIfNew(id), "legitimate retry must succeed because no commit happened earlier");
        } finally {
            cache.clear();
        }
    }

    @Test
    void blankIdsBehaveAsAlwaysSeenAndNeverRecord() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            assertTrue(cache.isAlreadySeen(null));
            assertTrue(cache.isAlreadySeen(""));
            assertTrue(cache.isAlreadySeen("   "));
            assertFalse(cache.recordIfNew(null));
            assertFalse(cache.recordIfNew(""));
            assertEquals(0, cache.size(), "no entries are stored for blank ids");
        } finally {
            cache.clear();
        }
    }

    @Test
    void differentIdsAreIndependent() {
        SamlReplayCache cache = new SamlReplayCache(60);
        try {
            assertTrue(cache.recordIfNew("a"));
            assertTrue(cache.recordIfNew("b"));
            assertTrue(cache.recordIfNew("c"));
            assertFalse(cache.recordIfNew("a"));
            assertEquals(3, cache.size());
        } finally {
            cache.clear();
        }
    }

    @Test
    void expiredEntriesAreReusable() throws InterruptedException {
        // Millisecond-precision TTL means the test is decisive even with a
        // 250 ms TTL: after the sleep the entry must be considered expired.
        SamlReplayCache cache = new SamlReplayCache(0); // 0s = immediate expiry
        try {
            String id = "_short";
            assertTrue(cache.recordIfNew(id));
            // With ttl=0 the entry expires immediately; isAlreadySeen reads false
            // and a re-record succeeds.
            Thread.sleep(50);
            assertFalse(cache.isAlreadySeen(id), "expired entry must read as not-seen");
            assertTrue(cache.recordIfNew(id), "expired entry can be re-recorded");
        } finally {
            cache.clear();
        }
    }

    @Test
    void singletonInstanceShared() {
        assertSame(SamlReplayCache.getInstance(), SamlReplayCache.getInstance());
    }
}
