package jp.aegif.nemaki.epoch;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The ACL-epoch outbox state machine and the CouchDB content-document field names it
 * uses (design {@code docs/design/acl-epoch-fencing.md} §2.2 / §3 — increment 2).
 *
 * <p>The states live as fields ON THE CONTENT document (so the Phase-1 marker can be
 * committed atomically with the ACL change in a later increment):
 * <pre>
 *   PENDING_EPOCH               (ACL change committed; no epoch allocated yet)
 *     → FINALIZED_NEEDS_RECONCILE (epoch allocated; reconcile task not yet confirmed)
 *     → RECONCILE_ENQUEUED        (task durably exists; steady state = marker cleared)
 * </pre>
 *
 * <p><b>Increment-2 scope:</b> only {@code PENDING_EPOCH → FINALIZED_NEEDS_RECONCILE} is
 * implemented. The {@code → RECONCILE_ENQUEUED} ACK is deliberately NOT implemented here:
 * clearing the marker requires the v2.1 condition (an {@code ACL_REINDEX} task with
 * {@code minRequiredEpoch >= finalized}), whose task-side fields are a later increment —
 * so a finalized document STOPS at {@code FINALIZED_NEEDS_RECONCILE}.
 *
 * <p>A content document with NEITHER a state NOR a mutation id (every normal document) is never
 * selected by the scanner. A document carrying a LEFTOVER mutation id but no state is corruption —
 * the steady state clears both — and IS selected, by a dedicated pass (increment 3b).
 */
public final class AclEpochState {

    private AclEpochState() {}

    /** CouchDB content-doc field: the outbox state (one of the constants below). */
    public static final String FIELD_STATE = "aclEpochState";
    /** CouchDB content-doc field: the Phase-1 mutation UUID (finalize matches on it). */
    public static final String FIELD_MUTATION_ID = "aclEpochMutationId";
    /** CouchDB content-doc field: the allocated epoch (set at finalize). */
    public static final String FIELD_SOURCE_EPOCH = "aclSourceEpoch";
    /**
     * CouchDB content-doc field ({@code true} once set): DURABLE QUARANTINE. The scanner
     * sets it on a document whose epoch fields are anomalous (unknown / non-String / blank /
     * non-UUID mutation id, missing mutation id, or an invalid epoch) so the document is
     * excluded from the live valid/anomaly selectors and can never block a valid document
     * again — all OTHER fields are preserved for inspection / repair (a malformed marker is
     * the one field normalized to {@code true}).
     *
     * <p>Marker CONTRACT is by PRESENCE (the SDK stores an explicit JSON null as a present
     * entry): absent = process, Boolean {@code true} = quarantined, any other PRESENT value
     * ({@code false} / {@code null} / string / …) = a malformed marker that is itself an
     * anomaly and is normalized to {@code true}. Because CouchDB Mango {@code $ne}/{@code $not}
     * do NOT match an absent field, every scan selector excludes a TRUE marker via
     * {@code $or({$exists:false}, {$ne:true})} — which matches an absent OR malformed marker
     * while excluding a proper {@code true}.
     */
    public static final String FIELD_QUARANTINED = "aclEpochQuarantined";

    public static final String PENDING_EPOCH = "PENDING_EPOCH";
    public static final String FINALIZED_NEEDS_RECONCILE = "FINALIZED_NEEDS_RECONCILE";
    public static final String RECONCILE_ENQUEUED = "RECONCILE_ENQUEUED";

    /** True for the three defined states; false for null / any unknown value (fail-closed). */
    public static boolean isKnown(String state) {
        return PENDING_EPOCH.equals(state)
                || FINALIZED_NEEDS_RECONCILE.equals(state)
                || RECONCILE_ENQUEUED.equals(state);
    }

    /**
     * Canonical (8-4-4-4-12 hex) UUID form. Phase 1 writes a FRESH {@link #newMutationId()}
     * for every mutation; reusing an id would let an old finalizer believe it still owns a
     * newer mutation, so the format is enforced at read time (validate) — a non-UUID
     * {@code aclEpochMutationId} is a fail-closed anomaly.
     */
    private static final Pattern MUTATION_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** A fresh, unique mutation id for a Phase-1 write (canonical UUID form). */
    public static String newMutationId() {
        return UUID.randomUUID().toString();
    }

    /** True iff {@code id} is a canonical-form UUID (fail-closed: null / malformed → false). */
    public static boolean isValidMutationId(String id) {
        return id != null && MUTATION_ID_PATTERN.matcher(id).matches();
    }
}
