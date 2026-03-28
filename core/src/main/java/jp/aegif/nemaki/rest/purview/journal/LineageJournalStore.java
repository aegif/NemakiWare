package jp.aegif.nemaki.rest.purview.journal;

import java.time.Instant;
import java.util.List;

/**
 * Persistence interface for lineage journal events.
 *
 * <h2>CouchDB Storage Model (Phase 2 implementation)</h2>
 *
 * <h3>Document Key</h3>
 * <p>Each event is stored as one CouchDB document with key
 * {@code lineage:{eventId}} and a {@code type} field of {@code "lineage_event"}.
 * The 1-event-per-document model keeps writes simple and conflict-free.
 *
 * <h3>Required CouchDB Views</h3>
 * <ul>
 *   <li><b>{@code by_repository_and_time}</b> — key: {@code [repositoryId, occurredAt]}.
 *       Used for time-range queries ({@link #findByRepositoryId}) and
 *       purge ({@link #purgeOlderThan}). Purge issues a batch delete of all
 *       documents where {@code occurredAt < cutoff}.</li>
 *   <li><b>{@code by_event_key}</b> — key: {@code eventKey}.
 *       Used for idempotency check on {@link #append}: if a document with the
 *       same eventKey already exists, the append is silently skipped.</li>
 *   <li><b>{@code by_target_status}</b> — key: {@code [target, status]}.
 *       Used by the projection loop to find events with
 *       {@link LineagePublishStatus#PENDING} for a given target.</li>
 * </ul>
 *
 * <h3>Sequence Assignment — DB-side Compare-and-Set</h3>
 *
 * <p>Each repository has a dedicated <b>sequence counter document</b>
 * with key {@code lineage_seq:{repositoryId}} and a single numeric field
 * {@code seq}. The {@link #append} implementation must:
 *
 * <ol>
 *   <li>Read the current counter document (including its {@code _rev}).</li>
 *   <li>Increment the {@code seq} value by 1.</li>
 *   <li>Write the counter document back with the original {@code _rev}
 *       (CouchDB optimistic locking). If CouchDB returns {@code 409 Conflict},
 *       retry from step 1 (another node won the race).</li>
 *   <li>Write the event document with the assigned {@code sequenceNumber}.</li>
 * </ol>
 *
 * <p><b>This is the only valid strategy.</b> In-memory incrementing is
 * <em>not safe</em> in a multi-node / multi-JVM deployment because each
 * JVM would maintain its own counter and produce duplicate sequences.
 * The CouchDB {@code _rev}-based compare-and-set guarantees a single
 * writer per sequence value across all nodes. The counter document and
 * the event document are <em>not</em> in the same atomic transaction
 * (CouchDB has no multi-document ACID), so a gap in sequence is possible
 * if the event write fails after the counter write succeeds. Projectors
 * must tolerate sequence gaps but must never process a higher sequence
 * before a lower one (i.e. in-order, gap-tolerant).
 *
 * <p><b>Counter initialization:</b> On first append to a repository,
 * if the counter document does not exist, create it with {@code seq: 1}.
 * Use CouchDB's {@code _rev} absence / {@code 404} as the "create" signal.
 *
 * <h3>Purge Strategy — Safety Conditions</h3>
 *
 * <p><b>An event is eligible for purge only when:</b>
 * <ol>
 *   <li>{@code occurredAt} is older than the retention cutoff, AND</li>
 *   <li><b>All</b> target entries in {@code publishStatusByTarget} are
 *       in a <b>terminal state</b> ({@link LineagePublishStatus#isTerminal()}:
 *       PUBLISHED, SKIPPED, or DISCARDED).</li>
 * </ol>
 *
 * <p>Events with any target in PENDING, PROJECTING, or FAILED are
 * <b>never purged</b>, regardless of age. This prevents losing
 * undelivered or in-flight events.
 *
 * <p>Implementation: query {@code by_repository_and_time} for candidates
 * older than the cutoff. For each candidate, check that all target
 * statuses are terminal before including in the bulk delete batch.
 * Batch size is capped at 1000 documents per request.
 *
 * <h3>Projector Claim Protocol (single-execution guarantee)</h3>
 *
 * <p>Multiple nodes may run projector threads concurrently. To prevent
 * duplicate publish to the same target, the projector must <b>claim</b>
 * an event before publishing:
 *
 * <ol>
 *   <li>Query {@code by_target_status} for
 *       {@code [target, "PENDING"]} (or {@code "FAILED"} for retry).</li>
 *   <li>For each candidate event, attempt CAS transition:
 *       {@code PENDING → PROJECTING} via
 *       {@link #updatePublishStatus(String, String, LineagePublishStatus)}.
 *       The update uses the document's current {@code _rev}.</li>
 *   <li>If CouchDB returns {@code 409 Conflict}: another node claimed
 *       first — skip this event.</li>
 *   <li>If update succeeds (returns 1): this node owns the event.
 *       Publish to the target sink.</li>
 *   <li>On publish success: update status to {@code PUBLISHED}.</li>
 *   <li>On publish failure: update status to {@code FAILED}
 *       (eligible for retry on next projector pass).</li>
 * </ol>
 *
 * <p><b>Stale PROJECTING detection:</b> if a projector crashes after
 * claiming but before completing, the event stays PROJECTING
 * indefinitely. A reaper/watchdog (e.g. scheduled task) must detect
 * events in PROJECTING state for longer than a configurable threshold
 * (default: 5 minutes) and reset them to FAILED for retry.
 *
 * <h3>Idempotency</h3>
 * <p>{@link #append} must check {@code by_event_key} before writing.
 * If a document with the same {@code eventKey} exists, the call returns
 * silently (no duplicate). {@link #updatePublishStatus} uses optimistic
 * concurrency via CouchDB {@code _rev} — conflict on concurrent update
 * triggers a retry (read-modify-write).
 *
 * <h3>Database Provisioning Guard</h3>
 *
 * <p>The journal CouchDB database ({@code nemaki_lineage} or similar)
 * must <b>only</b> be created when <em>all</em> of the following
 * conditions are met:
 *
 * <ol>
 *   <li>{@link LineageConfig#getMode()} returns {@link LineageMode#JOURNALED}
 *       (or at least one repository override resolves to JOURNALED)</li>
 *   <li>{@link #isActive()} returns {@code true} (implementation-level
 *       readiness check: CouchDB reachable, credentials valid)</li>
 * </ol>
 *
 * <p>If the mode is {@code disabled} or {@code direct}, no journal
 * database is created, no views are deployed, and no disk space is
 * consumed. The {@link NoopLineageJournalStore} is used instead
 * and performs zero I/O.
 *
 * <p><b>Setting mis-configuration:</b> if an operator sets
 * {@code mode=journaled} but CouchDB is unreachable, the
 * implementation must <b>not</b> create an empty database on a
 * fallback node or silently degrade. It should log a clear error
 * and report {@code isActive()=false} until the database is
 * successfully provisioned. Events emitted during this window are
 * handled by the fail-open policy (dead-letter).
 *
 * <h3>Backlog Management — Bounded Growth</h3>
 *
 * <p>In journaled mode, target outages or misconfigurations can cause
 * non-terminal events (PENDING, FAILED) to accumulate indefinitely.
 * To prevent unbounded storage growth, the system enforces multiple
 * discard thresholds (see {@link LineageConfig} for settings):
 *
 * <h4>Auto-discard conditions (any one triggers DISCARDED)</h4>
 * <ol>
 *   <li><b>Retry count exceeded:</b>
 *       {@code lineage.backlog.max-retry-count} (default: 5).
 *       A projector-maintained per-(eventId, target) failure counter
 *       exceeds this value → transition to DISCARDED.</li>
 *   <li><b>Retry age exceeded:</b>
 *       {@code lineage.backlog.max-retry-age-hours} (default: 72h).
 *       {@code occurredAt} is older than this threshold and the target
 *       is still non-terminal → transition to DISCARDED.</li>
 *   <li><b>Backlog document count exceeded:</b>
 *       {@code lineage.backlog.max-docs} (default: 10000).
 *       Total non-terminal events across all targets exceeds this
 *       limit → oldest events (by occurredAt) are auto-discarded
 *       first.</li>
 *   <li><b>Backlog size exceeded:</b>
 *       {@code lineage.backlog.max-size-mb} (default: 100MB).
 *       Estimated total size of non-terminal events exceeds this
 *       limit → oldest events are auto-discarded first.</li>
 * </ol>
 *
 * <p><b>Auto-discard is logged and dead-lettered.</b> Before
 * transitioning to DISCARDED, the projector writes the event to the
 * dead-letter log ({@link LineageDeadLetterSink}) with reason
 * {@code "auto-discard: {condition}"}. This ensures no event is
 * silently lost — operators can replay from dead-letter after
 * restoring the target.
 *
 * <h4>Operator manual discard</h4>
 * <p>Administrators can force-discard individual events via
 * {@link #discardEvent(String, String)}. This is the "operator gives
 * up" path — useful for events that are known to be undeliverable
 * (e.g. target decommissioned). The discard is also dead-lettered.
 *
 * <h4>Monitoring</h4>
 * <p>{@link #countNonTerminalByTarget(String)} returns the number of
 * non-terminal events for a given target. This can be exposed via a
 * health/metrics endpoint for alerting when the backlog approaches
 * the configured threshold.
 *
 * <h3>Access Control</h3>
 * <p>All read operations ({@link #findByRepositoryId},
 * {@link #findByProcessType}) and write operations are restricted to
 * <b>admin users only</b>. Any REST controller exposing journal data
 * must enforce admin authentication.
 *
 * <p><b>Initial release:</b> this is an <em>internal API</em>.
 * No management UI for event browsing or replay is provided.
 * The API is unstable and may change until a future release promotes
 * it to public. See {@link LineageEmitter} for the full policy.
 */
public interface LineageJournalStore {

    /**
     * Appends an event to the journal.
     *
     * <p><b>Idempotency:</b> silently skips if an event with the same
     * {@link LineageEvent#eventKey()} already exists.
     *
     * <p><b>Sequence assignment (DB-side):</b> the store assigns a
     * monotonically increasing {@link LineageEvent#sequenceNumber()} scoped
     * to the event's {@code repositoryId}. The caller's
     * {@code sequenceNumber} value is <b>ignored</b> — the store overwrites
     * it with the next value from the repository's sequence counter
     * document ({@code lineage_seq:{repositoryId}}). This ensures a single
     * source of truth for ordering across all JVM nodes. See class-level
     * javadoc "Sequence Assignment — DB-side Compare-and-Set" for the
     * full protocol.
     *
     * <p>Projectors must process events in sequence order within each
     * repository. Sequence gaps are tolerated but out-of-order processing
     * is not.
     */
    void append(LineageEvent event);

    void appendAll(List<LineageEvent> events);

    List<LineageEvent> findByRepositoryId(String repositoryId, int limit);

    List<LineageEvent> findByProcessType(String repositoryId, LineageProcessType processType, int limit);

    /**
     * Updates the publish status for a specific target on an event.
     *
     * <p>Uses CouchDB {@code _rev} for optimistic concurrency. If a
     * concurrent update wins (409 Conflict), this call returns 0.
     * The caller must treat 0 as "claim lost" and skip the event.
     *
     * <p>Valid transitions (see {@link LineagePublishStatus} state machine):
     * <ul>
     *   <li>{@code PENDING → PROJECTING} (claim)</li>
     *   <li>{@code FAILED → PROJECTING} (retry claim)</li>
     *   <li>{@code PROJECTING → PUBLISHED} (success)</li>
     *   <li>{@code PROJECTING → FAILED} (publish error)</li>
     *   <li>{@code PROJECTING → FAILED} (stale reaper reset)</li>
     *   <li>{@code FAILED → DISCARDED} (operator gives up)</li>
     *   <li>{@code PENDING → SKIPPED} (target adapter filter)</li>
     * </ul>
     *
     * @param eventId the event document ID
     * @param target  the target sink name (e.g. "purview")
     * @param status  the new status
     * @return 1 if updated, 0 if event not found or conflict
     */
    int updatePublishStatus(String eventId, String target, LineagePublishStatus status);

    /**
     * Purges events older than the cutoff timestamp.
     *
     * <p><b>Safety:</b> only purges events where <em>all</em> target
     * statuses are terminal (PUBLISHED, SKIPPED, or DISCARDED).
     * Events with any target in PENDING, PROJECTING, or FAILED are
     * skipped regardless of age. See class-level javadoc "Purge Strategy"
     * for details.
     *
     * <p>Deletes in batches of up to 1000 documents per request.
     *
     * @param cutoff events with {@code occurredAt} before this instant
     *               are candidates for purge (subject to terminal-status check)
     * @return the number of documents actually purged
     */
    int purgeOlderThan(Instant cutoff);

    /**
     * Force-discards a specific event-target pair.
     *
     * <p>Transitions the target's publish status to
     * {@link LineagePublishStatus#DISCARDED}. This is the operator
     * "give up" path for events that are known to be undeliverable.
     *
     * <p>Valid source states: PENDING, FAILED. Attempting to discard
     * an event in PROJECTING, PUBLISHED, SKIPPED, or already DISCARDED
     * state returns 0 (no-op).
     *
     * <p><b>Dead-letter:</b> callers should write the event to
     * {@link LineageDeadLetterSink} before calling this method.
     *
     * @param eventId the event document ID
     * @param target  the target sink name (e.g. "purview")
     * @return 1 if discarded, 0 if event not found, conflict, or invalid state
     */
    int discardEvent(String eventId, String target);

    /**
     * Counts events with at least one non-terminal target status for
     * the given target sink.
     *
     * <p>Non-terminal statuses: PENDING, PROJECTING, FAILED.
     * Used for backlog monitoring and threshold-based auto-discard.
     *
     * @param target the target sink name (e.g. "purview")
     * @return the count of non-terminal events for this target
     */
    long countNonTerminalByTarget(String target);

    boolean isActive();
}
