package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Document;

/** §11.2 Phase-1 marking semantics (the helper both applyAcl and move ride). */
public class AclEpochPhase1Test {

    @Test
    public void marksPENDINGWithAFreshMutationId() {
        Document d = new Document();
        d.setId("x");
        String mid = AclEpochPhase1.markPending(d);
        assertNotNull(mid);
        assertEquals(AclEpochState.PENDING_EPOCH,
                d.getAclEpochFields().get(AclEpochState.FIELD_STATE));
        assertEquals(mid, d.getAclEpochFields().get(AclEpochState.FIELD_MUTATION_ID));
    }

    /** §2.2: a newer mutation SUPERSEDES an unfinished older one — overwrite, fresh id. */
    @Test
    public void aSecondMarkSupersedesWithANewId() {
        Document d = new Document();
        d.setId("x");
        String first = AclEpochPhase1.markPending(d);
        String second = AclEpochPhase1.markPending(d);
        assertNotEquals(first, second, "the older finalizer must observe an id MISMATCH and abandon");
        assertEquals(second, d.getAclEpochFields().get(AclEpochState.FIELD_MUTATION_ID));
    }

    /** The previous settled epoch is NOT touched — finalize replaces it, not Phase 1. */
    @Test
    public void theStoredSourceEpochIsLeftUntouched() {
        Document d = new Document();
        d.setId("x");
        d.putAclEpochField(AclEpochState.FIELD_SOURCE_EPOCH, 9L);
        AclEpochPhase1.markPending(d);
        assertEquals(9L, d.getAclEpochFields().get(AclEpochState.FIELD_SOURCE_EPOCH));
    }
}
