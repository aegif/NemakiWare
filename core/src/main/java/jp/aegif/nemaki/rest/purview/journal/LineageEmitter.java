package jp.aegif.nemaki.rest.purview.journal;

/**
 * Single entry point for the lineage emission pipeline.
 *
 * <p>All lineage-producing call sites emit through this interface.
 * The active implementation is selected by {@code lineage.mode}:
 * <ul>
 *   <li>{@code disabled} → {@link NoopLineageEmitter} — no-op, zero cost</li>
 *   <li>{@code direct}   → {@link DirectLineageEmitter} — publish to configured sinks only (no local storage)</li>
 *   <li>{@code journaled} → {@link JournaledLineageEmitter} — store in CouchDB journal, then project to sinks</li>
 * </ul>
 *
 * <h2>Emission Timing Contract</h2>
 *
 * <p>{@link #emit(LineageEvent)} must be called <b>synchronously at domain
 * operation completion</b> — i.e. after the business operation has committed
 * its primary side-effects (document created, archive moved, export written,
 * cloud sync finished). The call site is the service method that owns the
 * operation, <em>not</em> a background reconcile/scan process.
 *
 * <p>Concrete emit points:
 * <ul>
 *   <li><b>Archive:</b> after {@code archiveService.archive()} returns successfully</li>
 *   <li><b>Import:</b> after each object is successfully imported (or at batch completion)</li>
 *   <li><b>Export:</b> after the export ZIP/folder is fully written</li>
 *   <li><b>Cloud Sync:</b> after each upload/download completes successfully</li>
 * </ul>
 *
 * <p>Events synthesized from diffs or reconciliation scans are <em>not</em>
 * canonical lineage events; those belong to the existing Purview/Atlas
 * incremental-sync and full-sync pipelines, which operate independently
 * of the journal (see "Purview Sync Coexistence" below).
 *
 * <h2>Synchronous Emit / Asynchronous Publish</h2>
 *
 * <p><b>External network I/O must never run on the business operation's
 * thread.</b> The boundary is:
 *
 * <pre>
 *   domain op thread          background thread(s)
 *   ────────────────          ────────────────────
 *   emit(event)
 *     ├─ [journaled] store.append(event)  ← local CouchDB write, synchronous
 *     │     └─ return to caller immediately
 *     │                        projector picks up PENDING events
 *     │                          └─ publish to Purview/Atlas/Dataplex (network I/O)
 *     │
 *     └─ [direct] enqueue for async publish ← no blocking I/O on caller thread
 *           └─ return to caller immediately
 *                               async worker publishes (network I/O)
 * </pre>
 *
 * <p><b>Rule:</b> {@code emit()} must complete without any external
 * network call. The caller's response latency is determined solely by
 * the local operation (CouchDB write for journaled, in-memory enqueue
 * for direct). Purview/Atlas/Dataplex latency, timeouts, or outages
 * must <em>never</em> delay archive, import, export, or cloud sync
 * responses.
 *
 * <p>Per-mode detail:
 * <ul>
 *   <li><b>journaled:</b> {@code emit()} writes to local CouchDB
 *       (synchronous, typically &lt;10ms) and returns. A separate
 *       projector thread polls for PENDING events and publishes to
 *       external targets asynchronously.</li>
 *   <li><b>direct:</b> {@code emit()} enqueues the event for
 *       asynchronous fire-and-forget publish and returns immediately.
 *       If the async worker fails, the event goes to dead-letter
 *       (no retry).</li>
 *   <li><b>disabled:</b> {@code emit()} is a no-op.</li>
 * </ul>
 *
 * <h2>Failure Policy (fail-open)</h2>
 *
 * <p>Implementations must <b>never block or fail</b> the parent business
 * operation. ECM availability takes strict priority over lineage
 * durability.
 *
 * <p>On failure, the event is written to the file-based dead-letter log
 * ({@link LineageDeadLetterSink}) regardless of mode. This ensures no
 * event is silently lost.
 *
 * <h3>Per-mode failure semantics</h3>
 * <table>
 *   <tr><th>Mode</th><th>Failure point</th><th>Delivery guarantee</th><th>Recovery path</th></tr>
 *   <tr>
 *     <td>{@code journaled}</td>
 *     <td>CouchDB store write</td>
 *     <td><b>At-least-once</b> — events are durably stored; projection
 *         retries on sink failure until PUBLISHED.</td>
 *     <td>If the store write itself fails: dead-letter log. Operator
 *         replays from the log after the store recovers. Idempotency
 *         by eventKey prevents duplicates.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code direct}</td>
 *     <td>Target sink publish</td>
 *     <td><b>At-most-once, best-effort</b> — no local store, no
 *         automatic retry. If the sink is down, the event is lost
 *         from the live pipeline.</td>
 *     <td>Dead-letter log only. Operator must manually extract and
 *         replay. <b>If automatic retry or at-least-once delivery is
 *         required, use {@code journaled} mode instead.</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code disabled}</td>
 *     <td>n/a</td>
 *     <td>Events silently discarded.</td>
 *     <td>n/a</td>
 *   </tr>
 * </table>
 *
 * <p><b>Recommendation:</b> production deployments that require lineage
 * durability should use {@code journaled} mode. {@code direct} mode is
 * intended for development, lightweight deployments, or scenarios where
 * occasional event loss is acceptable.
 *
 * <h2>Purview Sync Coexistence — System of Record Boundary</h2>
 *
 * <p>The existing Purview incremental-sync and full-sync services are
 * independent reconciliation mechanisms that operate on CMIS change-log
 * cursors. They are <em>not</em> replaced by the journal.
 *
 * <p><b>System of record rule:</b> for all operations in
 * {@link LineageProcessType}, this journal pipeline is the <b>single
 * system of record</b> for lineage emission. The Purview sync pathway
 * must <b>not</b> independently emit lineage for these operations.
 * If Purview sync needs the resulting lineage data in the future,
 * it must consume it from the journal's projection layer.
 *
 * <p>Data flow is strictly one-directional:
 * <pre>
 *   domain op → LineageEmitter → journal store → projection → Purview/Atlas
 * </pre>
 * Never: {@code domain op → journal} AND {@code domain op → Purview sync}
 * for the same {@link LineageProcessType} operation.
 *
 * <p>Responsibility split:
 * <ul>
 *   <li><b>Journal (this pipeline):</b> event-driven lineage capture for
 *       archive/import/export/cloud-sync operations (all
 *       {@link LineageProcessType} values) — records <em>what happened</em>
 *       in near-real-time. <b>Owns emission for these operations.</b></li>
 *   <li><b>Purview Sync:</b> cursor-based catch-up reconciliation for
 *       entity metadata (types, containment, properties) — ensures
 *       completeness and repairs drift. <b>Does not produce LineageEvents.</b></li>
 * </ul>
 *
 * <p>The two pathways do not overlap: sync does not consume journal events,
 * and the journal does not trigger sync. Both honour the same eventKey /
 * qualifiedName namespace to avoid conflicting entity identities.
 *
 * <h2>Access Control — Admin-only, No UI</h2>
 *
 * <p>Journal events may contain sensitive information (document names,
 * folder paths). All journal REST endpoints and replay operations are
 * restricted to <b>admin users only</b>. Events are not exposed in the
 * regular UI and are not accessible via standard CMIS APIs.
 *
 * <p><b>Initial release policy:</b> the journal is an <em>internal API
 * only</em>. No management UI (event list, replay screen, dead-letter
 * viewer) is provided. The Settings tab exposes only configuration
 * (mode, targets, retention) — not event data. Direct REST access is
 * available only for admin troubleshooting and will be versioned as an
 * internal/unstable API until a future release promotes it to public.
 * This prevents premature adoption and avoids locking the event schema
 * before it stabilises.
 *
 * <h2>Forward-only — No Backfill (by design)</h2>
 *
 * <p><b>This is a specification, not a limitation.</b> The journal
 * records lineage only for operations that occur <em>after</em> the
 * journal is enabled. Pre-existing documents, archives, and cloud-synced
 * files are intentionally <b>not</b> retroactively registered. The
 * lineage graph begins at the enablement date; operations before that
 * date have no journal entries. This is expected and correct.
 *
 * <p>Rationale:
 * <ul>
 *   <li>Synthetic backfill events would violate the "canonical events
 *       only at domain operation completion" rule.</li>
 *   <li>Backfill would require scanning the entire repository, which is
 *       expensive and error-prone for large installations.</li>
 *   <li>The existing Purview full-sync already provides a reconciliation
 *       mechanism for entity metadata; backfilling lineage would duplicate
 *       that effort.</li>
 * </ul>
 *
 * <p><b>Future extension point:</b> a dedicated
 * {@code BASELINE_SNAPSHOT} process type could be added to
 * {@link LineageProcessType} for a one-time inventory capture if
 * customers require a complete initial graph. This would be an
 * explicit admin action (not automatic), and the resulting events
 * would be clearly marked as baseline (not from a real operation).
 * Until that extension is implemented, the journal is strictly
 * forward-only.
 *
 * <h2>Backlog Pressure and Auto-discard (journaled mode)</h2>
 *
 * <p>In journaled mode, target outages cause non-terminal events to
 * accumulate. Without bounds, this violates the "zero cost for non-users"
 * design goal — even disabled/low-traffic instances would pay CouchDB
 * storage overhead if a misconfigured target silently fails.
 *
 * <p>The projector enforces four discard thresholds (configured via
 * {@link LineageConfig}):
 * <ul>
 *   <li><b>Max retry count</b> ({@code lineage.backlog.max-retry-count},
 *       default 5): per event-target failure counter.</li>
 *   <li><b>Max retry age</b> ({@code lineage.backlog.max-retry-age-hours},
 *       default 72h): elapsed time since {@code occurredAt}.</li>
 *   <li><b>Max backlog docs</b> ({@code lineage.backlog.max-docs},
 *       default 10000): total non-terminal event count.</li>
 *   <li><b>Max backlog size</b> ({@code lineage.backlog.max-size-mb},
 *       default 100MB): estimated storage footprint.</li>
 * </ul>
 *
 * <p>When any threshold is breached, the projector transitions the
 * affected event-target pairs to {@link LineagePublishStatus#DISCARDED}
 * and writes them to the dead-letter log. This guarantees that:
 * <ol>
 *   <li>Storage growth is bounded by operator-controlled limits.</li>
 *   <li>No event is silently lost — all discards are dead-lettered.</li>
 *   <li>Purge can proceed (DISCARDED is a terminal state).</li>
 * </ol>
 *
 * <p>{@code direct} mode has no backlog by design (at-most-once,
 * no local storage). {@code disabled} mode has no events.
 *
 * <h2>Idempotency Contract</h2>
 *
 * <p>Implementations must honour {@link LineageEvent#eventKey()}. If an
 * event with the same eventKey has already been successfully processed
 * for a given target, the duplicate must be silently skipped (no
 * duplicate process/relationship created on Purview/Atlas/Dataplex).
 */
public interface LineageEmitter {

    /**
     * Emit a lineage event through the pipeline.
     *
     * <p><b>Timing:</b> Call this synchronously after the domain operation
     * has committed (see class-level javadoc for specific call sites).
     *
     * <p><b>No external network I/O:</b> This method must complete
     * without calling any external service (Purview, Atlas, Dataplex).
     * The caller's thread is never blocked by external latency. See
     * class-level "Synchronous Emit / Asynchronous Publish" for detail.
     *
     * <p><b>Failure:</b> This method never throws. If the local
     * operation fails (CouchDB write or enqueue), the error is logged,
     * the event is sent to dead-letter, and the call returns normally
     * (fail-open).
     *
     * @param event the lineage event to emit (must not be null)
     */
    void emit(LineageEvent event);

    /**
     * Emit a version-free business fact.
     *
     * <p>This is the seam the A-2 Slice-4 write flip turns on: today it projects the fact to v1
     * — unconditionally, via {@link LineageFact#toV1Event()}, whose strings the producer supplied
     * verbatim — and hands it to {@link #emit(LineageEvent)}. After the flip the implementation
     * chooses the v2 mapping instead; the producers do not change again. The default is
     * deliberately final-in-spirit: no implementation overrides it before the fenced flip, and
     * the test suite pins that journaled mode reaches only the v1 append.
     *
     * <p>Same failure contract as {@link #emit(LineageEvent)}: never throws, never blocks the
     * business operation.
     *
     * @param fact the business fact (must not be null)
     */
    default void emit(LineageFact fact) {
        if (fact == null) {
            return;
        }
        emit(fact.toV1Event());
    }

    /**
     * Returns {@code true} if this emitter will actually process events
     * (i.e. mode is not {@code disabled}).
     */
    /**
     * Emit, and say whether the fact was LOST on the way.
     *
     * <p>{@link #emit(LineageFact)} is fail-open by design — it must never fail the business
     * operation — so implementations catch their own failures and return normally. That is
     * correct for the caller's transaction and wrong for the caller's evidence: a document ends
     * up stored with no provenance while the import reports success. This method keeps the
     * fail-open behaviour and adds the missing half, the truth about what happened.
     *
     * @return null when the fact was accepted, or a short reason when it was dropped,
     *         dead-lettered or otherwise not recorded
     */
    default String emitReportingLoss(LineageFact fact) {
        emit(fact);
        return null;
    }

    boolean isActive();
}
