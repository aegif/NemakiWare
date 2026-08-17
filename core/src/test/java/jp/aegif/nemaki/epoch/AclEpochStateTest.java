package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure tests for the epoch state constants (fail-closed {@code isKnown}). */
public class AclEpochStateTest {

    @Test
    public void isKnownAcceptsTheThreeDefinedStates() {
        assertTrue(AclEpochState.isKnown(AclEpochState.PENDING_EPOCH));
        assertTrue(AclEpochState.isKnown(AclEpochState.FINALIZED_NEEDS_RECONCILE));
        assertTrue(AclEpochState.isKnown(AclEpochState.RECONCILE_ENQUEUED));
    }

    @Test
    public void isKnownRejectsNullAndUnknown() {
        assertFalse(AclEpochState.isKnown(null));
        assertFalse(AclEpochState.isKnown(""));
        assertFalse(AclEpochState.isKnown("SOMETHING_ELSE"));
        assertFalse(AclEpochState.isKnown("pending_epoch")); // case-sensitive
    }

    @Test
    public void newMutationIdIsAFreshUuidEveryCall() {
        String a = AclEpochState.newMutationId();
        String b = AclEpochState.newMutationId();
        assertTrue(AclEpochState.isValidMutationId(a), "a fresh mutation id is a valid UUID");
        assertTrue(AclEpochState.isValidMutationId(b));
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b, "every Phase-1 mutation gets a NEW id");
    }

    @Test
    public void isValidMutationIdEnforcesCanonicalUuid() {
        assertTrue(AclEpochState.isValidMutationId("123e4567-e89b-12d3-a456-426614174000"));
        assertFalse(AclEpochState.isValidMutationId(null));
        assertFalse(AclEpochState.isValidMutationId(""));
        assertFalse(AclEpochState.isValidMutationId("m-1"));                 // not a UUID
        assertFalse(AclEpochState.isValidMutationId("123e4567e89b12d3a456426614174000")); // no dashes
        assertFalse(AclEpochState.isValidMutationId("123e4567-e89b-12d3-a456-42661417400")); // too short
    }
}
