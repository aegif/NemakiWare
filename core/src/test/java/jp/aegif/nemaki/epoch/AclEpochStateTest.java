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
}
