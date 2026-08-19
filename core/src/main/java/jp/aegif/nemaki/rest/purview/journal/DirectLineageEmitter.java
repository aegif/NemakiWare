package jp.aegif.nemaki.rest.purview.journal;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * returns immediately. The caller's thread is not blocked while the queue has room (CallerRunsPolicy publishes on the caller thread once it is full) by
 * Purview/Atlas/Dataplex latency or outages.
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
 * <h3>Failure Policy (fail-open)</h3>
 * <p>This emitter catches all exceptions, logs an error, records to
 * dead-letter, and returns normally. The parent business operation is
 * not blocked while the queue has room (CallerRunsPolicy publishes on the caller thread once it is full).
 */
public class DirectLineageEmitter implements LineageEmitter {

    private static final Logger logger = LoggerFactory.getLogger(DirectLineageEmitter.class);

    private final LineageConfig config;
    private final List<LineageTargetSink> sinks;
    private final ThreadPoolExecutor asyncWorker;
    private final AtomicLong failureCount = new AtomicLong(0);

    /** Backward-compatible constructor (no sinks — sinkless (backward compatibility) — retains no provenance). */
    public DirectLineageEmitter(LineageConfig config) {
        this(config, List.of());
    }

    public DirectLineageEmitter(LineageConfig config, List<LineageTargetSink> sinks) {
        this.config = config;
        this.sinks = sinks != null ? sinks : List.of();
        this.asyncWorker = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                Thread.ofVirtual().name("lineage-direct-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Report a SYNCHRONOUS enqueue failure. Direct mode normally publishes to sinks on a worker thread,
     * so a sink that fails later cannot reach this caller. Note the queue's CallerRunsPolicy:
     * under saturation publication runs on THIS thread, and such a failure is still swallowed
     * inside publishToSink — a narrower gap than "asynchronous" suggests, and stated as such
     * rather than hidden behind the word. But an enqueue that fails
     * here fails before the caller returns, and that one is reportable (external review, P1-1).
     */
    @Override
    public String emitReportingLoss(LineageFact fact) {
        List<String> losses = new java.util.ArrayList<>(1);
        // Save and restore rather than set/remove: a collaborator that re-enters this method
        // synchronously would otherwise remove the OUTER collector on its way out, and the
        // outer emission's own failure would then be reported as success (external review).
        List<String> previous = enqueueLoss.get();
        try {
            enqueueLoss.set(losses);
            emit(fact);
        } finally {
            if (previous == null) {
                enqueueLoss.remove();
            } else {
                enqueueLoss.set(previous);
            }
        }
        return losses.isEmpty() ? null : losses.get(0);
    }

    /** Present only while {@link #emitReportingLoss} is on the stack. */
    private static final ThreadLocal<List<String>> enqueueLoss = new ThreadLocal<>();

    private static void noteEnqueueLoss(String reason) {
        List<String> sink = enqueueLoss.get();
        if (sink != null && sink.isEmpty()) {
            sink.add(reason);
        }
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

            // Dispatch to each available target sink asynchronously
            int dispatched = 0;
            for (LineageTargetSink sink : sinks) {
                if (!sink.isAvailable()) {
                    continue;
                }
                asyncWorker.submit(() -> publishToSink(sink, event));
                dispatched++;
            }
            if (dispatched == 0) {
                // Nothing was handed anywhere. Direct mode keeps no journal, so an event with
                // no reachable sink exists nowhere at all — reporting success for it would hand
                // the caller an id that points at nothing (external review, P1-1).
                noteEnqueueLoss(sinks.isEmpty()
                        ? "no lineage target sink is configured"
                        : "every configured lineage target sink is unavailable");
            }
        } catch (Exception e) {
            // Fail-open: never block the business operation
            failureCount.incrementAndGet();
            logger.error("Failed to enqueue lineage event (fail-open): eventKey={}, repo={}, error={}",
                    event.eventKey(), event.repositoryId(), e.getMessage(), e);
            noteEnqueueLoss("enqueue failed, dead-lettered: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());

            // Persist to file-based dead-letter log so the event is not silently lost.
            LineageDeadLetterSink.record(event, e.getMessage());
        }
    }

    private void publishToSink(LineageTargetSink sink, LineageEvent event) {
        try {
            // The sink takes the version-neutral projection; the envelope stays here, because the
            // dead-letter path below needs something a LineageRecord cannot rebuild.
            LineageTargetSinkResult result = sink.publish(LineageRecord.fromV1(event));
            if (!result.success()) {
                failureCount.incrementAndGet();
                logger.warn("Direct publish failed to '{}': eventKey={}, error={}",
                        sink.targetName(), event.eventKey(), result.message());
                LineageDeadLetterSink.record(event, "direct-publish-failed:" + sink.targetName() + ":" + result.message());
            } else {
                logger.debug("Direct publish succeeded to '{}': eventKey={}, entities={}",
                        sink.targetName(), event.eventKey(), result.entityCount());
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            logger.error("Direct publish exception to '{}': eventKey={}, error={}",
                    sink.targetName(), event.eventKey(), e.getMessage(), e);
            LineageDeadLetterSink.record(event, "direct-publish-exception:" + sink.targetName() + ":" + e.getMessage());
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
