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
}
