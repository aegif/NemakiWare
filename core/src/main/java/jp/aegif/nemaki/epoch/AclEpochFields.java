package jp.aegif.nemaki.epoch;

import java.util.Map;

/**
 * The SINGLE validator for the ACL-epoch fields on a content document (review 3a [P1]).
 *
 * <p>Both the finalization service (which OWNS the state machine) and the effective-epoch service
 * (which CONSUMES it to compute a fence value) validate through here, so the two sides can never
 * drift apart again — previously each had its own rules, and the consumer accepted a
 * {@code RECONCILE_ENQUEUED} document with a missing / non-UUID mutation id or a missing / zero
 * epoch as "settled". Because {@code RECONCILE_ENQUEUED} does NOT gate, that fed wrong fence
 * values straight into the index.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li><b>Quarantine marker</b> ({@link #requireNotQuarantined}) — PRESENCE alone disqualifies the
 *       document, whatever the value. The state machine's contract (increments 2d/2e) is: absent =
 *       usable, {@code true} = quarantined, ANY other present value ({@code false} / explicit null
 *       / string / number) = a malformed marker, itself an anomaly. Accepting {@code false} as
 *       "fine" would let a corrupt-but-false-marked document contribute a high epoch and fence out
 *       later correct writers.</li>
 *   <li><b>State</b> — if present it must be a String naming one of the three known states. Whether
 *       ABSENCE is allowed differs by caller: the finalizer requires a state ({@code stateRequired
 *       = true}); the effective-epoch walk does not, because a settled document has had its marker
 *       cleared and normal content never had one.</li>
 *   <li><b>Mutation id</b> — every state-bearing document must carry a canonical-form UUID (reusing
 *       or malforming it would let an old finalizer believe it still owns a newer mutation).</li>
 *   <li><b>Epoch</b> — {@code FINALIZED_NEEDS_RECONCILE} and {@code RECONCILE_ENQUEUED} MUST carry a
 *       strictly positive, strictly-integral epoch (an epoch is assigned exactly when the state
 *       advances to FINALIZED). {@code PENDING_EPOCH} has no epoch yet, so none is required. With
 *       NO state, an epoch is optional — ABSENT means {@code 0} (design §4.1, pre-migration
 *       content) — but a PRESENT one must still be a strict non-negative integer (an explicit JSON
 *       null is PRESENT under the SDK contract, and is corruption rather than "absent").</li>
 * </ul>
 */
public final class AclEpochFields {

    private AclEpochFields() {}

    /** The validated epoch fields of one document. */
    public static final class Values {
        /** One of the three known states, or {@code null} when the document carries no state. */
        public final String state;
        /** Canonical UUID; {@code null} only when {@code state} is {@code null}. */
        public final String mutationId;
        /** The epoch: assigned for FINALIZED / ENQUEUED, {@code 0} when absent (§4.1). */
        public final long epoch;

        Values(String state, String mutationId, long epoch) {
            this.state = state;
            this.mutationId = mutationId;
            this.epoch = epoch;
        }
    }

    /**
     * Fail-closed on the quarantine marker being PRESENT AT ALL (not merely {@code true}).
     *
     * @throws AclEpochAnomalyException if the document carries a quarantine marker of any value
     */
    public static void requireNotQuarantined(String docId, Map<String, Object> props) {
        if (props == null || !props.containsKey(AclEpochState.FIELD_QUARANTINED)) {
            return;
        }
        Object marker = props.get(AclEpochState.FIELD_QUARANTINED);
        if (Boolean.TRUE.equals(marker)) {
            throw new AclEpochAnomalyException("document is quarantined on " + docId);
        }
        throw new AclEpochAnomalyException("malformed aclEpochQuarantined marker (not Boolean true, "
                + "including explicit null / false) on " + docId);
    }

    /**
     * Validate the epoch fields, IGNORING the quarantine marker (callers that must inspect a
     * quarantined document — the quarantine writer itself — use this directly; everyone else calls
     * {@link #requireNotQuarantined} first).
     *
     * @param stateRequired {@code true} for the finalizer (a state-less document is not its
     *                      business); {@code false} for the effective-epoch walk, where a
     *                      state-less document is ordinary settled content
     * @throws AclEpochAnomalyException on any violation of the rules documented on this class
     */
    public static Values validate(String docId, Map<String, Object> props, boolean stateRequired) {
        Object rawState = props != null ? props.get(AclEpochState.FIELD_STATE) : null;
        boolean statePresent = props != null && props.containsKey(AclEpochState.FIELD_STATE);

        if (!statePresent) {
            if (stateRequired) {
                throw new AclEpochAnomalyException("missing / non-String aclEpochState on " + docId);
            }
            return new Values(null, null, optionalEpoch(docId, props));
        }
        if (!(rawState instanceof String)) {
            // Includes an explicit JSON null, which the SDK stores as a PRESENT entry.
            throw new AclEpochAnomalyException("missing / non-String aclEpochState on " + docId);
        }
        String state = (String) rawState;
        if (!AclEpochState.isKnown(state)) {
            throw new AclEpochAnomalyException("unknown aclEpochState '" + state + "' on " + docId);
        }

        Object rawMut = props.get(AclEpochState.FIELD_MUTATION_ID);
        String mutationId = (rawMut instanceof String && !((String) rawMut).isBlank())
                ? (String) rawMut : null;
        if (mutationId == null) {
            throw new AclEpochAnomalyException(state + " without aclEpochMutationId on " + docId);
        }
        if (!AclEpochState.isValidMutationId(mutationId)) {
            throw new AclEpochAnomalyException(state + " with non-UUID aclEpochMutationId on " + docId);
        }

        long epoch = 0L;
        if (AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(state)
                || AclEpochState.RECONCILE_ENQUEUED.equals(state)) {
            // An epoch is assigned exactly when the state advances to FINALIZED, so both of these
            // states MUST carry a strictly positive one. RECONCILE_ENQUEUED in particular does not
            // gate the effective-epoch walk, so an unvalidated one would become a fence value.
            try {
                epoch = AclEpochCounterService.parseExactLong(props.get(AclEpochState.FIELD_SOURCE_EPOCH));
            } catch (RuntimeException e) {
                throw new AclEpochAnomalyException(state + " with invalid epoch on " + docId
                        + ": " + e.getMessage());
            }
            if (epoch < 1) {
                throw new AclEpochAnomalyException(state + " with non-positive epoch " + epoch
                        + " on " + docId);
            }
        }
        // PENDING_EPOCH: no epoch assigned yet — none required (the finalizer writes it).
        return new Values(state, mutationId, epoch);
    }

    /**
     * The epoch of a state-less document: ABSENT = {@code 0} (design §4.1 pre-migration content),
     * PRESENT = a strict non-negative integer (an explicit null / non-Number / fractional /
     * negative value is corruption, not "absent").
     */
    private static long optionalEpoch(String docId, Map<String, Object> props) {
        if (props == null || !props.containsKey(AclEpochState.FIELD_SOURCE_EPOCH)) {
            return 0L;
        }
        Object raw = props.get(AclEpochState.FIELD_SOURCE_EPOCH);
        long epoch;
        try {
            epoch = AclEpochCounterService.parseExactLong(raw);
        } catch (RuntimeException e) {
            throw new AclEpochAnomalyException("non-integer aclSourceEpoch on " + docId
                    + " (value=" + raw + ")");
        }
        if (epoch < 0) {
            throw new AclEpochAnomalyException("negative aclSourceEpoch " + epoch + " on " + docId);
        }
        return epoch;
    }
}
