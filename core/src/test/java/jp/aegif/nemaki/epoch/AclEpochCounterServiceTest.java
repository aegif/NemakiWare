package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit tests for {@link AclEpochCounterService} (the id encoding and the
 * overflow/corruption-guarded successor). The CAS allocation semantics against a live
 * CouchDB are covered by {@link AclEpochCounterServiceIT}.
 */
public class AclEpochCounterServiceTest {

    @Test
    public void counterDocIdIsDeterministicAndPrefixed() {
        assertEquals("acl-epoch-counter::bedroom", AclEpochCounterService.counterDocId("bedroom"));
        assertEquals(AclEpochCounterService.counterDocId("repoX"),
                AclEpochCounterService.counterDocId("repoX"), "same repo → same id");
        assertTrue(AclEpochCounterService.counterDocId("r").startsWith(AclEpochCounterService.ID_PREFIX));
    }

    @Test
    public void nextValueIncrementsFromSeedBaseline() {
        // Patch seeds 0 → the first allocation is 1 (the fresh-repository baseline).
        assertEquals(1L, AclEpochCounterService.nextValue(AclEpochCounterService.SEED_VALUE));
        assertEquals(2L, AclEpochCounterService.nextValue(1L));
        assertEquals(100L, AclEpochCounterService.nextValue(99L));
    }

    @Test
    public void nextValueRejectsOverflow() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.nextValue(Long.MAX_VALUE));
        assertTrue(ex.getMessage().toLowerCase().contains("overflow"));
    }

    @Test
    public void nextValueRejectsNegativeAsCorruption() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.nextValue(-1L));
        assertTrue(ex.getMessage().toLowerCase().contains("corrupt"));
    }

    @Test
    public void allocateRequiresRepositoryId() {
        AclEpochCounterService svc = new AclEpochCounterService();
        assertThrows(IllegalArgumentException.class, () -> svc.allocate(null));
        assertThrows(IllegalArgumentException.class, () -> svc.allocate("  "));
    }

    @Test
    public void currentHighWatermarkRequiresRepositoryId() {
        AclEpochCounterService svc = new AclEpochCounterService();
        // The guard fires before any CouchDB access, so no connectorPool is needed.
        assertThrows(IllegalArgumentException.class, () -> svc.currentHighWatermark(null));
        assertThrows(IllegalArgumentException.class, () -> svc.currentHighWatermark("  "));
    }

    // ── strict value parsing (increment 1a fail-closed read) ───────

    @Test
    public void parseExactLongAcceptsIntegralNumbers() {
        assertEquals(0L, AclEpochCounterService.parseExactLong(0L));
        assertEquals(5L, AclEpochCounterService.parseExactLong(Integer.valueOf(5)));
        assertEquals(7L, AclEpochCounterService.parseExactLong(Double.valueOf(7.0d))); // 7.0 is integral
        assertEquals(Long.MAX_VALUE, AclEpochCounterService.parseExactLong(Long.MAX_VALUE));
    }

    @Test
    public void parseExactLongRejectsFractional() {
        assertThrows(IllegalStateException.class, () -> AclEpochCounterService.parseExactLong(1.5d));
        assertThrows(IllegalStateException.class, () -> AclEpochCounterService.parseExactLong(-0.5d));
    }

    @Test
    public void parseExactLongRejectsOutOfLongRange() {
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.parseExactLong(1e30d));
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.parseExactLong(
                        java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)));
    }

    @Test
    public void parseExactLongRejectsNonFinite() {
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.parseExactLong(Double.NaN));
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.parseExactLong(Double.POSITIVE_INFINITY));
    }

    @Test
    public void parseExactLongRejectsMissingAndNonNumeric() {
        assertThrows(IllegalStateException.class, () -> AclEpochCounterService.parseExactLong(null));
        assertThrows(IllegalStateException.class, () -> AclEpochCounterService.parseExactLong("5"));
        assertThrows(IllegalStateException.class, () -> AclEpochCounterService.parseExactLong(Boolean.TRUE));
    }

    @Test
    public void requireValidCounterAcceptsWellFormed() {
        assertEquals(3L, AclEpochCounterService.requireValidCounter(
                AclEpochCounterService.DOC_TYPE, 3L, "1-abc"));
    }

    @Test
    public void requireValidCounterRejectsWrongType() {
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter("someOtherType", 3L, "1-abc"));
    }

    @Test
    public void requireValidCounterRejectsMissingRev() {
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter(AclEpochCounterService.DOC_TYPE, 3L, null));
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter(AclEpochCounterService.DOC_TYPE, 3L, "  "));
    }

    @Test
    public void requireValidCounterRejectsNegativeAndFractionalAndMissing() {
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter(AclEpochCounterService.DOC_TYPE, -1L, "1-abc"));
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter(AclEpochCounterService.DOC_TYPE, 1.5d, "1-abc"));
        assertThrows(IllegalStateException.class,
                () -> AclEpochCounterService.requireValidCounter(AclEpochCounterService.DOC_TYPE, null, "1-abc"));
    }
}
