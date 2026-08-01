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
}
