package jp.aegif.nemaki.rest.purview.journal;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct emitter used when {@code lineage.mode=direct}.
 * Enqueues events for asynchronous publish to configured target sinks.
 * No local storage.
 *
 * <h3>Emit / Publish Boundary</h3>
 *
 * <p>{@link #emit(LineageEvent)} performs <b>no external network I/O</b>.
 * It enqueues the event for asynchronous fire-and-forget publish and
 * returns immediately. The caller's thread is never blocked by
 * Purview/Atlas/Dataplex latency or outages.
 *
 * <p>Phase 2 will inject an async executor and actual publish sink
 * adapters (Purview, Atlas, Dataplex). Until then, {@code emit()} logs
 * and returns.
 *
 * <h3>Delivery Guarantee: at-most-once, best-effort</h3>
 *
 * <p>This emitter has <b>no local store</b> and <b>no automatic retry</b>.
 * Each event gets exactly one async publish attempt per target sink.
 * If the attempt fails, the event is:
 * <ol>
 *   <li>Written to the {@link LineageDeadLetterSink} (file-based log)</li>
 *   <li>Counted in {@link #getFailureCount()}</li>
 *   <li><b>Not retried automatically</b></li>
 * </ol>
 *
 * <p>Recovery requires operator intervention: extract events from the
 * dead-letter log and replay them via the admin API after the target
 * recovers.
 *
 * <p><b>If automatic retry or at-least-once delivery is required,
 * use {@code journaled} mode instead.</b>
 *
 * <h3>Failure Policy (fail-open)</h3>
 * <p>This emitter catches all exceptions, logs an error, records to
 * dead-letter, and returns normally. The parent business operation is
 * never blocked.
 */
public class DirectLineageEmitter implements LineageEmitter {

    private static final Logger logger = LoggerFactory.getLogger(DirectLineageEmitter.class);

    private final LineageConfig config;
    private final AtomicLong failureCount = new AtomicLong(0);

    public DirectLineageEmitter(LineageConfig config) {
        this.config = config;
    }

    @Override
    public void emit(LineageEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Direct emit lineage event: eventKey={}, processType={}, repo={}, seq={}",
                        event.eventKey(), event.processType(), event.repositoryId(),
                        event.sequenceNumber());
            }
            // Phase 2: enqueue for async publish to each target sink.
            // The async worker iterates config.getTargets() and dispatches.
            // No external network I/O occurs on the caller's thread.
            // Idempotency is enforced by eventKey at each sink adapter.
        } catch (Exception e) {
            // Fail-open: never block the business operation
            failureCount.incrementAndGet();
            logger.error("Failed to publish lineage event (fail-open): eventKey={}, repo={}, error={}",
                    event.eventKey(), event.repositoryId(), e.getMessage(), e);

            // Persist to file-based dead-letter log so the event is not silently lost.
            LineageDeadLetterSink.record(event, e.getMessage());
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    /** Number of events that failed to publish since this emitter was created. */
    public long getFailureCount() {
        return failureCount.get();
    }
}
