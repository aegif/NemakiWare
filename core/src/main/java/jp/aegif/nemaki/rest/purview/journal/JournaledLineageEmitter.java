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
    private final LineageBarrierReader barrierReader;
    private final LineageSpoolMachinery spoolMachinery;
    private final LineageMetrics metrics;
    private final AtomicLong failureCount = new AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean warnedUnwired =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public JournaledLineageEmitter(LineageJournalStore store, LineageConfig config) {
        this(store, config, null, null, null);
    }

    public JournaledLineageEmitter(LineageJournalStore store, LineageConfig config,
                                   LineageBarrierReader barrierReader,
                                   LineageSpoolMachinery spoolMachinery,
                                   LineageMetrics metrics) {
        this.store = store;
        this.config = config;
        this.barrierReader = barrierReader;
        this.spoolMachinery = spoolMachinery;
        this.metrics = metrics;
    }

    /**
     * §6-a's write seam (A-2 Slice 4a): where a version-free business fact goes.
     *
     * <p>4a ships the whole routing while the flag still says 1, which is what makes 4b a
     * guarded CAS on one document rather than another deploy.
     *
     * <table>
     *   <tr><th>barrier</th><th>action</th></tr>
     *   <tr><td>reader absent</td><td>v1 append — the pre-4a construction seam</td></tr>
     *   <tr><td>{@code Pristine}, {@code Present(1)}</td><td>v1 append, byte-identical</td></tr>
     *   <tr><td>{@code Present(2)}</td><td>spool the fact</td></tr>
     *   <tr><td>{@code Indeterminate}</td><td>spool the fact</td></tr>
     * </table>
     *
     * <p>v2 is never appended from here. Chunking, the decision document, digest-exact
     * convergence and the K-row ACK all live in the materializer, so the emit path hands it a
     * fact and lets the scanner converge it — which is also why an unreadable flag spools
     * rather than guessing a version it cannot know.
     *
     * <p><b>A missing spool is not a licence to write v1.</b> The barrier said this fact does
     * not belong on the v1 path; "we could not spool it" does not change that, so it is
     * dropped and reported. Falling back would cross the fence exactly when the fence matters.
     */
    @Override
    public void emit(LineageFact fact) {
        if (fact == null) {
            return;
        }
        if (barrierReader == null) {
            if (warnedUnwired.compareAndSet(false, true)) {
                logger.warn("No §6-a barrier reader is wired — journaling v1 unconditionally");
            }
            emit(fact.toV1Event());
            return;
        }
        LineageBarrierReader.BarrierView view = barrierReader.view();
        if (view instanceof LineageBarrierReader.BarrierView.Pristine) {
            emit(fact.toV1Event());
            return;
        }
        if (view instanceof LineageBarrierReader.BarrierView.Present present
                && present.barrier().writeSchemaVersion() == 1) {
            emit(fact.toV1Event());
            return;
        }
        String reason = view instanceof LineageBarrierReader.BarrierView.Indeterminate
                ? "flag_unreadable_and_spool_failed" : "write_v2_and_spool_failed";
        spool(fact, reason);
    }

    private void spool(LineageFact fact, String dropReason) {
        java.util.Optional<LineageFactSpool> spool =
                spoolMachinery == null ? java.util.Optional.empty() : spoolMachinery.spool();
        if (spool.isEmpty()) {
            drop(fact, "spool_machinery_absent", "no spool is configured on this node");
            return;
        }
        try {
            LineageFactSpool.AppendOutcome outcome =
                    spool.get().append(LineageSpoolPayloadV1.of(fact));
            if (outcome == LineageFactSpool.AppendOutcome.APPENDED
                    || outcome == LineageFactSpool.AppendOutcome.IDEMPOTENT) {
                return;
            }
            drop(fact, dropReason, "the spool refused the record (" + outcome + ")");
        } catch (RuntimeException e) {
            drop(fact, dropReason, e.toString());
        }
    }

    private void drop(LineageFact fact, String reason, String detail) {
        failureCount.incrementAndGet();
        if (metrics != null) {
            metrics.recordEmitDropped(reason);
        }
        // Unconditional: the drop must be visible even where no metrics bean is wired.
        logger.error("Lineage fact DROPPED (reason={}): repo={}, process={}, operation={} — {}."
                + " It is not recoverable from here.", reason, fact.repositoryId(),
                fact.processType(), fact.operationId(), detail);
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
