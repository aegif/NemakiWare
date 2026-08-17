package jp.aegif.nemaki.rest.purview.journal;

/**
 * Target sink for lineage event projection.
 *
 * <p>Each implementation publishes a {@link LineageRecord} to a specific external target (e.g.
 * Purview, Atlas, Dataplex). The projection loop iterates over all registered sinks and calls
 * {@link #publish} for each eligible record.
 *
 * <h2>Why a {@link LineageRecord} and not a {@link LineageEvent}</h2>
 *
 * <p>Publication is one of the three things the version-neutral projection exists for, and it is
 * the one with the most code behind it. Taking the concrete v1 envelope here would mean the write
 * flip in A-2 Slice 4 had to rewrite all three sinks in the same commit that changes the writer.
 * Taking the projection instead means the sinks are already running the code the v2 path will use,
 * against v1 traffic, from the day this lands.
 *
 * <p>Recovery is deliberately not part of this contract: a sink that fails hands its <em>original
 * envelope</em> to the dead-letter path, because {@code LineageRecord} cannot rebuild one (see its
 * javadoc). The caller keeps the envelope; the sink never needs it.
 */
public interface LineageTargetSink {

    /** Returns the unique name of this target (e.g. "purview"). */
    String targetName();

    /**
     * Publishes a lineage record to the external target.
     *
     * @param record the projected lineage record to publish
     * @return the result of the publish operation
     * @throws Exception if the publish fails (caller handles retry/dead-letter)
     */
    LineageTargetSinkResult publish(LineageRecord record) throws Exception;

    /**
     * Returns {@code true} if this sink is properly configured and available.
     *
     * <p>When {@code false}, the projection loop skips this sink entirely
     * (no publish attempts, no FAILED transitions).
     */
    boolean isAvailable();

    /**
     * §8-b v2 (D-rest-2): whether this sink can read a published record back from its target to
     * confirm the publish took effect.
     *
     * <p>STRUCTURAL and immutable per sink — not a runtime probe. The D-rest readiness gate
     * refuses to sequence v2 rows while any configured target's sink answers {@code false},
     * because a finalized v2 row is an ordered barrier and a barrier no sink can ever verify
     * would strand all later traffic. {@code PUBLISHED} on the v2 machine means verify
     * <em>succeeded</em>; a sink without verification simply cannot enter the machine.
     */
    default boolean supportsVerification() {
        return false;
    }

    /** Outcome of one {@link #verify} attempt. */
    enum VerifyResult {
        /** The record is durably visible at the target. */
        VERIFIED,
        /** Not visible yet — plausibly read lag; retry within the budget. */
        RETRYABLE,
        /**
         * Deterministic semantic mismatch (wrong type / repositoryId / shell): the same
         * payload can never verify. Terminal — UNPROJECTABLE.
         */
        MISMATCH,
        /** This sink cannot verify. Structural; must match {@code !supportsVerification()}. */
        UNSUPPORTED
    }

    /**
     * §8-b v2: one bounded read-back attempt for a record this projector just published.
     *
     * <p>{@code deadline} is the remaining encounter budget — the sink must bound its own IO
     * to it (v2.3.19: each call receives what is left, never the full timeout afresh).
     *
     * <p>A sink whose {@link #supportsVerification()} is {@code false} keeps this default; the
     * v2 machine never calls it. Answering UNSUPPORTED despite advertising capability is
     * treated by the loop as a structural fault: no publish, loud halt.
     */
    default VerifyResult verify(LineageRecord record, java.time.Duration deadline) {
        return VerifyResult.UNSUPPORTED;
    }
}
