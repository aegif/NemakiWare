package jp.aegif.nemaki.rest.purview.journal;

/**
 * Publish lifecycle for a lineage event per target sink.
 *
 * <p>State machine:
 * <pre>
 *   PENDING → PROJECTING → PUBLISHED   (happy path)
 *   PENDING → PROJECTING → FAILED      (publish error, eligible for retry)
 *   PENDING → SKIPPED                   (event filtered out by target adapter)
 *   FAILED  → PROJECTING → PUBLISHED   (retry succeeds)
 *   FAILED  → DISCARDED                (operator/retention gives up)
 * </pre>
 *
 * <p><b>Terminal states:</b> {@code PUBLISHED}, {@code SKIPPED},
 * {@code DISCARDED}. Once in a terminal state, no further transitions
 * occur. Only events where <em>all</em> targets are in a terminal state
 * are eligible for purge.
 */
public enum LineagePublishStatus {

    /** Awaiting projection. Initial state for all targets. */
    PENDING,

    /**
     * A projector has claimed this event for publish.
     *
     * <p>Claim is acquired via CAS: the projector transitions
     * {@code PENDING → PROJECTING} (or {@code FAILED → PROJECTING}
     * for retry) using {@link LineageJournalStore#updatePublishStatus}.
     * CouchDB {@code _rev} ensures only one node wins the claim;
     * losers receive {@code 409 Conflict} and skip the event.
     *
     * <p>If the projector crashes mid-publish, the event remains
     * PROJECTING. A reaper/watchdog must detect stale PROJECTING
     * events (e.g. older than 5 minutes) and reset them to FAILED
     * for retry.
     */
    PROJECTING,

    /** Successfully published to the target. Terminal state. */
    PUBLISHED,

    /**
     * Publish attempt failed. Eligible for retry
     * (transition back to PROJECTING on next projector pass).
     */
    FAILED,

    /** Event was filtered out by the target adapter. Terminal state. */
    SKIPPED,

    /**
     * Operator or retention policy has given up on delivery.
     * Terminal state. The event will not be retried.
     */
    DISCARDED;

    /** Returns {@code true} if this status is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == PUBLISHED || this == SKIPPED || this == DISCARDED;
    }
}
