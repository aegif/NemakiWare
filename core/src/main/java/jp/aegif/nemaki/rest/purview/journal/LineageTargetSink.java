package jp.aegif.nemaki.rest.purview.journal;

/**
 * Target sink for lineage event projection.
 *
 * <p>Each implementation publishes {@link LineageEvent}s to a specific
 * external target (e.g. Purview, Atlas, Dataplex). The projection loop
 * iterates over all registered sinks and calls {@link #publish} for
 * each eligible event.
 */
public interface LineageTargetSink {

    /** Returns the unique name of this target (e.g. "purview"). */
    String targetName();

    /**
     * Publishes a lineage event to the external target.
     *
     * @param event the lineage event to publish
     * @return the result of the publish operation
     * @throws Exception if the publish fails (caller handles retry/dead-letter)
     */
    LineageTargetSinkResult publish(LineageEvent event) throws Exception;

    /**
     * Returns {@code true} if this sink is properly configured and available.
     *
     * <p>When {@code false}, the projection loop skips this sink entirely
     * (no publish attempts, no FAILED transitions).
     */
    boolean isAvailable();
}
