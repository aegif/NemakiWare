package jp.aegif.nemaki.rest.purview.journal;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journaled emitter used when {@code lineage.mode=journaled}.
 *
 * <h3>Emit / Publish Boundary</h3>
 *
 * <p>{@link #emit(LineageEvent)} writes the event to the local CouchDB
 * journal (<b>synchronous, typically &lt;10ms</b>) and returns
 * immediately. <b>No external network I/O</b> (Purview/Atlas/Dataplex)
 * occurs on the caller's thread. The caller's response latency is
 * determined solely by the local CouchDB write.
 *
 * <p>A separate <b>projector thread</b> asynchronously polls for
 * events with {@link LineagePublishStatus#PENDING} status and publishes
 * them to configured target sinks. See
 * {@link LineageJournalStore} "Projector Claim Protocol" for the
 * single-execution guarantee.
 *
 * <h3>Two-phase Durability</h3>
 *
 * <p>The two-phase approach (store → project) ensures durability:
 * if projection fails, the event remains in the journal with
 * {@link LineagePublishStatus#PENDING} and can be retried or replayed.
 *
 * <h3>Failure Policy (fail-open)</h3>
 * <p>If the journal store write fails, this emitter catches all
 * exceptions, logs an error, records to {@link LineageDeadLetterSink},
 * increments {@link #getFailureCount()}, and returns normally.
 * The parent business operation is never blocked.
 *
 * <p>Phase 2 will implement the actual CouchDB store and projection loop.
 */
public class JournaledLineageEmitter implements LineageEmitter {

    private static final Logger logger = LoggerFactory.getLogger(JournaledLineageEmitter.class);

    private final LineageJournalStore store;
    private final LineageConfig config;
    private final AtomicLong failureCount = new AtomicLong(0);

    public JournaledLineageEmitter(LineageJournalStore store, LineageConfig config) {
        this.store = store;
        this.config = config;
    }

    @Override
    public void emit(LineageEvent event) {
        if (event == null) {
            return;
        }
        try {
            // Phase 1: store the event (idempotency check by eventKey is done by the store)
            store.append(event);

            if (logger.isDebugEnabled()) {
                logger.debug("Journaled lineage event stored: eventKey={}, processType={}, repo={}, seq={}, targets={}",
                        event.eventKey(), event.processType(), event.repositoryId(),
                        event.sequenceNumber(), config.getTargets());
            }

            // Phase 2: asynchronously project to each target sink.
            // On projection success, updatePublishStatus(eventId, target, PUBLISHED).
            // On failure, status remains PENDING for retry by the purge/retry scheduler.
        } catch (Exception e) {
            // Fail-open: never block the business operation
            failureCount.incrementAndGet();
            logger.error("Failed to store lineage event (fail-open): eventKey={}, repo={}, error={}",
                    event.eventKey(), event.repositoryId(), e.getMessage(), e);

            // Persist to file-based dead-letter log so the event is not silently lost.
            // The dead-letter sink does not depend on CouchDB and never throws.
            LineageDeadLetterSink.record(event, e.getMessage());
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    /** Number of events that failed to store since this emitter was created. */
    public long getFailureCount() {
        return failureCount.get();
    }
}
