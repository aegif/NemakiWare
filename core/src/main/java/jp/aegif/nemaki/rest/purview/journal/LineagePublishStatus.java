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
    DISCARDED,

    /**
     * The pre-sink gate (§7) refused to publish: the event's endpoints failed repository-scope or
     * artifact-operation re-validation at the last moment before the sink call.
     *
     * <p>Terminal — a validation failure is a property of the data, and retrying cannot change
     * the data — but <b>not purge-eligible</b>. A REJECTED v2 row has no dead-letter copy (the
     * dead-letter sink records v1 envelopes only until D-spool), so its journal document is the
     * only evidence that a cross-repository or wrong-operation event was attempted. Purging it
     * would delete the record of the violation along with the violation. Increment E's durable
     * §6-format record is what eventually makes these purgeable.
     */
    REJECTED;

    /** Returns {@code true} if this status is a terminal state (no further transitions). */
    public boolean isTerminal() {
        return this == PUBLISHED || this == SKIPPED || this == DISCARDED || this == REJECTED;
    }

    /**
     * Whether retention purge may delete a row that is in this state.
     *
     * <p>Distinct from {@link #isTerminal()} on purpose. Terminal answers "does the projector
     * still owe this target anything" — it drives claim skipping and cursor advancement. Purge
     * eligibility answers "may the stored document be destroyed", and those questions part ways
     * exactly at {@link #REJECTED}: the projector is done with it, but the document is evidence.
     */
    public boolean isPurgeEligible() {
        return this == PUBLISHED || this == SKIPPED || this == DISCARDED;
    }
}
