package jp.aegif.nemaki.epoch;

import jp.aegif.nemaki.model.Content;

/**
 * Phase 1 of the two-phase ACL mutation (design §2.2, wired by §11.2): record
 * {@code aclEpochState = PENDING_EPOCH} and a fresh {@code aclEpochMutationId} on the model
 * object so the SAME DAO PUT that persists the ACL change persists the marker — one {@code _rev},
 * atomic by construction.
 *
 * <p>A marker already present is OVERWRITTEN with a fresh mutation id: a newer mutation SUPERSEDES
 * an unfinished older one, and the older finalizer then observes the id mismatch and abandons
 * (§2.2 consequence). The stored {@code aclSourceEpoch} in the carrier is left untouched — it is
 * the previous settled epoch until the new finalize replaces it.
 *
 * <p>Static and flag-free on purpose: callers gate on the wiring flag; this class only knows how
 * to mark. It never reads configuration and never talks to a store.
 */
public final class AclEpochPhase1 {

    private AclEpochPhase1() {}

    /** Mark the model for the next persist. Returns the fresh mutation id (never null). */
    public static String markPending(Content content) {
        String mutationId = AclEpochState.newMutationId();
        content.putAclEpochField(AclEpochState.FIELD_STATE, AclEpochState.PENDING_EPOCH);
        content.putAclEpochField(AclEpochState.FIELD_MUTATION_ID, mutationId);
        return mutationId;
    }
}
