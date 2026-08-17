package jp.aegif.nemaki.epoch;

/**
 * The walk hit a QUARANTINED document (design §5.1, wiring gate 4).
 *
 * <p>A subtype of {@link AclEpochAnomalyException} rather than a sibling, so every existing handler
 * that already treats an anomaly as "retain the task, repair required" keeps working unchanged —
 * but callers that need to act on the specific case can now do so WITHOUT parsing a message.
 *
 * <p>The distinction earns its keep because the blast radius is different in kind. An ordinary
 * anomaly is about the object being processed; a quarantine is usually about an ANCESTOR, and that
 * one document blocks its whole subtree's ACL-index refresh until it is repaired. An operator facing
 * a thousand stalled tasks needs the handful of blocking ancestor ids, not a thousand object ids —
 * so {@link #getQuarantinedId} carries it structurally.
 *
 * <p>Reading "just the epoch" from a quarantined document to avoid the block was explicitly REJECTED
 * in review: an exception like that dissolves what quarantine means. The block is the correct
 * behaviour; what §5.1 requires is that it be visible, bounded and self-healing.
 */
public class AclEpochQuarantineBlockedException extends AclEpochAnomalyException {

    private static final long serialVersionUID = 1L;

    /** The QUARANTINED document — the one whose repair unblocks the subtree. */
    private final String quarantinedId;
    /** The object whose walk was blocked (equal to {@link #quarantinedId} when it is itself). */
    private final String blockedObjectId;

    public AclEpochQuarantineBlockedException(String message, String quarantinedId) {
        this(message, quarantinedId, null);
    }

    public AclEpochQuarantineBlockedException(String message, String quarantinedId, String blockedObjectId) {
        super(message);
        this.quarantinedId = quarantinedId;
        this.blockedObjectId = blockedObjectId;
    }

    public String getQuarantinedId() {
        return quarantinedId;
    }

    public String getBlockedObjectId() {
        return blockedObjectId;
    }

    /** The same failure, re-stated once the walk knows which object it was serving. */
    public AclEpochQuarantineBlockedException withBlockedObject(String objectId) {
        if (blockedObjectId != null || objectId == null) {
            return this;
        }
        return new AclEpochQuarantineBlockedException(getMessage() + " (blocking the ACL-index "
                + "refresh of " + objectId + " and its subtree)", quarantinedId, objectId);
    }
}
