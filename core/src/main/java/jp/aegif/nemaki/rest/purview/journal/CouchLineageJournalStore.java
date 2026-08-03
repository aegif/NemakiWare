package jp.aegif.nemaki.rest.purview.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DatabaseInformation;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CouchDB-backed implementation of {@link LineageJournalStore}.
 *
 * <p>Events are stored in a dedicated {@code nemaki_lineage} database
 * (not a CMIS repository database). The database and its design documents
 * are provisioned lazily on first {@link #append} call when the lineage
 * mode is {@link LineageMode#JOURNALED}.
 */
@Service
public class CouchLineageJournalStore implements LineageJournalStore, LineageSequencingStore,
        LineageV2TransitionStore, LineageV2ReplayStore, LineageMaterializationStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchLineageJournalStore.class);

    static final String DB_NAME = "nemaki_lineage";
    private static final String SEQ_PREFIX = "lineage_seq:";
    private static final String DESIGN_DOC = "lineage";
    private static final int MAX_CAS_RETRIES = 5;
    private static final int PURGE_BATCH_SIZE = 1000;

    @Autowired
    private CloudantClientPool connectorPool;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    // ObjectProvider breaks the construction cycle (readiness itself autowires this store);
    // resolved lazily at each purge run, never at startup.
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<LineageDrestReadiness>
            drestReadinessProvider;

    @Autowired
    @Qualifier("couchdbObjectMapper")
    private ObjectMapper objectMapper;

    private volatile CloudantClientWrapper lineageClient;
    private final AtomicBoolean dbProvisioned = new AtomicBoolean(false);

    // ---------------------------------------------------------------
    // Database provisioning (lazy)
    // ---------------------------------------------------------------

    /**
     * Ensures the nemaki_lineage database and design documents exist.
     * Called lazily on first write operation.
     */
    /**
     * Integration-test seam, package-visible: provisions against a directly supplied Cloudant
     * client and database name, bypassing the Spring connector pool. The real-CouchDB IT
     * (increment D's gate) is the one caller; production wiring never reaches this.
     */
    static CouchLineageJournalStore forDirectClient(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            ObjectMapper objectMapper) {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        store.objectMapper = objectMapper;
        store.lineageClient = new CloudantClientWrapper(cloudant, dbName, objectMapper);
        try {
            try {
                cloudant.getDatabaseInformation(new GetDatabaseInformationOptions.Builder()
                        .db(dbName).build()).execute();
            } catch (NotFoundException e) {
                cloudant.putDatabase(new PutDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            }
            store.deployViews();
            store.dbProvisioned.set(true);
        } catch (Exception e) {
            throw new RuntimeException("direct-client provisioning failed for " + dbName, e);
        }
        return store;
    }

    private void ensureDatabase() {
        if (dbProvisioned.get()) {
            return;
        }
        synchronized (this) {
            if (dbProvisioned.get()) {
                return;
            }
            try {
                CloudantClientWrapper anyRepoClient = connectorPool.getClient("nemaki_conf");
                Cloudant cloudant = anyRepoClient.getClient();
                lineageClient = new CloudantClientWrapper(cloudant, DB_NAME, objectMapper);

                // Create database if it doesn't exist
                try {
                    GetDatabaseInformationOptions infoOpts = new GetDatabaseInformationOptions.Builder()
                            .db(DB_NAME).build();
                    cloudant.getDatabaseInformation(infoOpts).execute();
                    logger.info("Lineage journal database '{}' already exists", DB_NAME);
                } catch (NotFoundException e) {
                    PutDatabaseOptions createOpts = new PutDatabaseOptions.Builder()
                            .db(DB_NAME).build();
                    cloudant.putDatabase(createOpts).execute();
                    logger.info("Created lineage journal database '{}'", DB_NAME);
                }

                // Deploy design document views
                deployViews();

                dbProvisioned.set(true);
                logger.info("Lineage journal store provisioned successfully");
            } catch (Exception e) {
                logger.error("Failed to provision lineage journal database: {}", e.getMessage(), e);
                throw new RuntimeException("Lineage journal DB provisioning failed", e);
            }
        }
    }

    /**
     * Returns the lineage database client, ensuring the DB is provisioned.
     * Package-private so that sibling stores (dead-letter, cursor) can reuse it.
     */
    CloudantClientWrapper getLineageClient() {
        if (lineageClient == null) {
            ensureDatabase();
        }
        return lineageClient;
    }

    /**
     * Ensures the lineage client is available for read operations.
     *
     * <p>After a JVM restart, {@link #dbProvisioned} is {@code false} even
     * though the {@code nemaki_lineage} database still exists from a previous
     * run. This method discovers the existing database without performing full
     * provisioning (no view deployment, no DB creation), allowing read-only
     * API calls (e.g. {@code /events}, {@code /stats}) to succeed immediately
     * without waiting for the first write.
     *
     * @return {@code true} if the lineage client is now available
     */
    private boolean ensureClientForRead() {
        if (lineageClient != null) {
            return true;
        }
        synchronized (this) {
            if (lineageClient != null) {
                return true;
            }
            try {
                CloudantClientWrapper anyRepoClient = connectorPool.getClient("nemaki_conf");
                Cloudant cloudant = anyRepoClient.getClient();
                GetDatabaseInformationOptions infoOpts = new GetDatabaseInformationOptions.Builder()
                        .db(DB_NAME).build();
                cloudant.getDatabaseInformation(infoOpts).execute();
                // DB exists — set up client for reads and ensure views are deployed
                lineageClient = new CloudantClientWrapper(cloudant, DB_NAME, objectMapper);
                deployViews();
                dbProvisioned.set(true);
                logger.info("Discovered existing lineage journal database '{}' for read access", DB_NAME);
                return true;
            } catch (NotFoundException e) {
                // DB does not exist — no data to read
                return false;
            } catch (Exception e) {
                logger.debug("Lineage DB not available for read: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * The type predicate every event view starts with.
     *
     * <p>Both document types, deliberately: v2 rows carry {@code lineage_event_v2} so that OLD
     * binaries' views — which select on {@code lineage_event} alone — structurally cannot see
     * them (§6-a v2.3.14). The price of that protection is paid here: every view a NEW binary
     * queries must cover both types, and one that does not makes v2 silently invisible. That is
     * why the predicate is one shared constant and why {@code LineageJournalViewCoverageTest}
     * executes every map function against a synthetic document of each version rather than
     * pattern-matching the source.
     */
    private static final String EVENT_TYPES =
            "(doc.type === 'lineage_event' || doc.type === 'lineage_event_v2')";

    /** A CouchDB view: the map function source, and the reduce built-in or {@code null}. */
    record ViewDefinition(String map, String reduce) {
    }

    /**
     * Every view of the {@code lineage} design document, in deployment order.
     *
     * <p>Package-visible so the coverage test iterates the real definitions — a list kept beside
     * the deployment code would be a second list to fall behind.
     */
    static final Map<String, ViewDefinition> VIEWS = buildViews();

    private static Map<String, ViewDefinition> buildViews() {
        Map<String, ViewDefinition> views = new LinkedHashMap<>();

        // by_event_key — v1 append idempotency. Deliberately v1-only: a v2 row has no eventKey
        // (its idempotency is the deliveryId-derived _id itself), and substituting processKey
        // here would silently change what "same event" means for this view's one caller.
        views.put("by_event_key", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.eventKey) { emit(doc.eventKey, null); } }",
                null));

        // by_repository_and_time — time-range queries and purge
        views.put("by_repository_and_time", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + ") { emit([doc.repositoryId, doc.occurredAt], null); } }",
                null));

        // by_target_status — v1 projector claim and backlog monitoring. v1-ONLY (D-rest-2):
        // the SEQUENCED-allowlist half-measure is superseded by schema selection — every v1
        // status/count/drain surface reads this view, and isolation by selection is the only
        // isolation that also protects OLD binaries, whose code queries this view name and
        // whose downstream (3-arg CAS, drains) has no v2 guards.
        views.put("by_target_status", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.publishStatusByTarget) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { emit([k, t[k]], null); } } } }",
                "_count"));

        // by_process_type — countByProcessType stats and listing
        views.put("by_process_type", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.processType) { emit(doc.processType, null); } }",
                "_count"));

        // by_occurred_at — cross-repository time ordering for findAll and purge. v1-ONLY:
        // purge DELETES; an old binary must be physically unable to purge v2 rows. The new
        // binary purges v2 through v2_by_occurred_at, and only when D-rest is enabled.
        views.put("by_occurred_at", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event') { emit(doc.occurredAt, null); } }",
                null));

        // by_repository_and_process_type — repository+processType composite filter
        views.put("by_repository_and_process_type", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.repositoryId && doc.processType) { " +
                        "emit([doc.repositoryId, doc.processType], null); } }",
                null));

        // dead_letter_by_time — dead-letter events ordered by time
        views.put("dead_letter_by_time", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_dead_letter') { emit(doc.recordedAt, null); } }",
                null));

        // dead_letter_by_replayed — dead-letter events grouped by replayed status
        views.put("dead_letter_by_replayed", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_dead_letter') { emit([doc.replayed || false, doc.recordedAt], null); } }",
                "_count"));

        // by_target_status_time — target+status+occurredAt for oldest-first queries. v1-ONLY:
        // this view feeds the destructive age/overflow drains, which are v1 policy.
        views.put("by_target_status_time", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.publishStatusByTarget && doc.occurredAt) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { emit([k, t[k], doc.occurredAt], null); } } } }",
                null));

        // by_repository_and_sequence — projection cursor sequence ordering. v1-ONLY: an old
        // binary's ordered walk reads this view and claims via the token-less 3-arg CAS; a
        // finalized v2 row must never appear in it. The new binary's router MERGES this stream
        // with v2_by_repository_and_sequence by sequence (unique per repo — one counter).
        views.put("by_repository_and_sequence", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.repositoryId && doc.sequenceNumber) { " +
                        "emit([doc.repositoryId, doc.sequenceNumber], null); } }",
                null));

        // non_terminal_by_target_repo — distinct repos with non-terminal v1 events per target.
        // v1-ONLY: feeds v1 repository discovery and the backlog counters that drive the
        // destructive v1 drain; v2 discovery has its own view below.
        views.put("non_terminal_by_target_repo", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.publishStatusByTarget && doc.repositoryId) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { " +
                        "var s = t[k]; if (s === 'PENDING' || s === 'FAILED' || s === 'PROJECTING') { emit([k, doc.repositoryId], null); } } } } }",
                "_count"));

        // v2_sequencer_backlog — the fenced sequencer's claim scan (§8-a v2): UNSEQUENCED rows
        // in deterministic claim order [repositoryId, occurredAt, _id]. v2-only by definition;
        // the reduce feeds the backlog-cap metric.
        views.put("v2_sequencer_backlog", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.state === 'UNSEQUENCED') { "
                        + "emit([doc.repositoryId, doc.occurredAt, doc._id], null); } }",
                "_count"));

        // v2_sequencer_in_flight — SEQUENCING rows for the reclaim scan. A row only leaves this
        // view through finalize (SEQUENCED) or reclaim-then-finalize by a newer generation.
        views.put("v2_sequencer_in_flight", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.state === 'SEQUENCING') { "
                        + "emit([doc.repositoryId, doc.occurredAt, doc._id], null); } }",
                "_count"));

        // sequence_watermark — the rewind check's high-watermark (_stats.max per repo),
        // covering BOTH event versions and the projection cursors (v2.3.18 ③): the counter
        // is shared with v1, so a v1 sequence of 100 with a rewound counter must fail the
        // check even before any v2 row exists.
        views.put("sequence_watermark", new ViewDefinition(
                "function(doc) { "
                        + "if (doc.type === 'lineage_event' && doc.sequenceNumber) {"
                        + " emit(doc.repositoryId, doc.sequenceNumber); } "
                        + "if (doc.type === 'lineage_event_v2' && doc.state === 'SEQUENCED'"
                        + " && doc.sequenceNumber) { emit(doc.repositoryId, doc.sequenceNumber); } "
                        + "if (doc.type === 'projection_cursor' && doc.repositoryId"
                        + " && doc.lastProcessedSequence) {"
                        + " emit(doc.repositoryId, doc.lastProcessedSequence); } }",
                "_stats"));

        // projecting_by_claimed_at — PROJECTING events ordered by claimedAt for stale reaping.
        // Key: [target, claimedAt], where claimedAt falls back to occurredAt for pre-upgrade
        // events, so oldest-claimed is always found regardless of the sample window size.
        // v1-ONLY: the v1 reaper resets PROJECTING via the token-less 3-arg CAS; a leased v2
        // claim must be physically invisible to it (the v2 reaper is token-fenced, below).
        views.put("projecting_by_claimed_at", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event' && doc.publishStatusByTarget) { " +
                        "var t = doc.publishStatusByTarget; var c = doc.claimedAtByTarget || {}; " +
                        "for (var k in t) { if (t.hasOwnProperty(k) && t[k] === 'PROJECTING') { " +
                        "var ts = c[k] || doc.occurredAt || ''; emit([k, ts], null); } } } }",
                null));

        // by_process_type_time — processType + occurredAt for time-ordered filtered listings
        views.put("by_process_type_time", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.processType && doc.occurredAt) { " +
                        "emit([doc.processType, doc.occurredAt], null); } }",
                null));

        // by_repo_process_type_time — repositoryId + processType + occurredAt per-repo listings
        views.put("by_repo_process_type_time", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.repositoryId && doc.processType && doc.occurredAt) { " +
                        "emit([doc.repositoryId, doc.processType, doc.occurredAt], null); } }",
                null));

        // ---- §8-b v2 projection views (D-rest-2). v2-only by definition; old binaries never
        // query these names. All v2 timestamps are epoch millis — numeric keys sort exactly.

        // v2_by_repository_and_sequence — the v2 half of the ordered stream: SEQUENCED rows
        // only (state is explicit; sequenceNumber alone is not deliverability).
        views.put("v2_by_repository_and_sequence", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.state === 'SEQUENCED'"
                        + " && doc.repositoryId && doc.sequenceNumber) { "
                        + "emit([doc.repositoryId, doc.sequenceNumber], null); } }",
                null));

        // v2_by_occurred_at — the v2 half of retention purge, queried only when D-rest is
        // enabled (inertness: a disabled system touches no v2 row, purge included).
        views.put("v2_by_occurred_at", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2') { emit(doc.occurredAt, null); } }",
                null));

        // v2_non_terminal_by_target_repo — v2 repository discovery for the ordered router.
        // VERIFYING is non-terminal here; the destructive v1 drains never read this view.
        views.put("v2_non_terminal_by_target_repo", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.state === 'SEQUENCED'"
                        + " && doc.publishStatusByTarget && doc.repositoryId) { "
                        + "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { "
                        + "var s = t[k]; if (s === 'PENDING' || s === 'FAILED' || s === 'PROJECTING' || s === 'VERIFYING') { "
                        + "emit([k, doc.repositoryId], null); } } } } }",
                "_count"));

        // v2_claims_by_expiry — the token-fenced reaper's scan: live claims ordered by lease
        // expiry (epoch millis). The view row is a hint; the reaper rereads before every CAS.
        views.put("v2_claims_by_expiry", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.publishStatusByTarget"
                        + " && doc.v2ClaimByTarget) { "
                        + "var t = doc.publishStatusByTarget; var c = doc.v2ClaimByTarget; "
                        + "for (var k in t) { if (t.hasOwnProperty(k) && (t[k] === 'PROJECTING' || t[k] === 'VERIFYING')"
                        + " && c[k] && typeof c[k].leaseExpiresAtMs === 'number') { "
                        + "emit([k, c[k].leaseExpiresAtMs], null); } } } }",
                "_count"));

        // v2_verifying_by_since — lineage.verifying.count{target} and oldest-age (§8-b v2.2
        // metrics): VERIFYING rows ordered by when verification began.
        views.put("v2_verifying_by_since", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.publishStatusByTarget"
                        + " && doc.v2ClaimByTarget) { "
                        + "var t = doc.publishStatusByTarget; var c = doc.v2ClaimByTarget; "
                        + "for (var k in t) { if (t.hasOwnProperty(k) && t[k] === 'VERIFYING'"
                        + " && c[k] && typeof c[k].verifyingSinceMs === 'number') { "
                        + "emit([k, c[k].verifyingSinceMs], null); } } } }",
                "_count"));

        // v2_sequenced_repositories — v2.3.22 C2: per-target repository discovery that also
        // covers rows whose every status is TERMINAL. The ordered walk's other discovery
        // sources are cursors and NON-terminal rows, so a repository whose only sequenced v2
        // row was classified terminal at creation would never be visited and its cursor would
        // never advance past it. Target-qualified, so a repository never becomes visible to a
        // target it does not owe.
        views.put("v2_sequenced_repositories", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.state === 'SEQUENCED'"
                        + " && doc.repositoryId && doc.publishStatusByTarget) { "
                        + "var t = doc.publishStatusByTarget; for (var k in t) { "
                        + "if (t.hasOwnProperty(k)) { emit([k, doc.repositoryId], null); } } } }",
                "_count"));

        // v2_replay_requests_unacked — §8-d crash recovery scan (D-rest-3): unacked replay
        // requests ordered by last update. v2-only; old binaries never query this name.
        views.put("v2_replay_requests_unacked", new ViewDefinition(
                "function(doc) { if (doc.type === 'lineage_event_v2' && doc.v2ReplayRequestsByTarget) { "
                        + "var r = doc.v2ReplayRequestsByTarget; for (var k in r) { if (r.hasOwnProperty(k)"
                        + " && r[k] && (r[k].state === 'REQUESTED' || r[k].state === 'CREATED')"
                        + " && typeof r[k].updatedAtMs === 'number') { "
                        + "emit([r[k].updatedAtMs, k], null); } } } }",
                "_count"));

        return java.util.Collections.unmodifiableMap(views);
    }

    /**
     * Deploy CouchDB views for the lineage design document.
     */
    private void deployViews() {
        CloudantClientWrapper client = lineageClient;
        for (Map.Entry<String, ViewDefinition> view : VIEWS.entrySet()) {
            client.createOrUpdateView(DESIGN_DOC, view.getKey(),
                    view.getValue().map(), view.getValue().reduce());
        }
        logger.info("Lineage journal views deployed to design document '{}'", DESIGN_DOC);
    }

    // ---------------------------------------------------------------
    // LineageJournalStore implementation
    // ---------------------------------------------------------------

    @Override
    public void append(LineageEvent event) {
        ensureDatabase();

        // Idempotency check: skip if eventKey already exists
        if (eventKeyExists(event.eventKey())) {
            logger.debug("Lineage event with eventKey '{}' already exists, skipping", event.eventKey());
            return;
        }

        // Assign sequence number via CAS loop
        long seq = assignSequenceNumber(event.repositoryId());

        // Build CouchDB document with the assigned sequence
        LineageEvent seqEvent = new LineageEvent(
                event.schemaVersion(), event.eventId(), event.eventKey(),
                seq, event.occurredAt(), event.repositoryId(),
                event.processType(), event.inputs(), event.outputs(),
                event.runId(), event.correlationId(), event.version(),
                event.snapshotAttributes(), event.publishStatusByTarget()
        );
        CouchLineageEvent doc = new CouchLineageEvent(seqEvent);

        getLineageClient().create(doc.toMap());
        logger.debug("Appended lineage event: id={}, seq={}, eventKey={}", doc.getId(), seq, event.eventKey());
    }

    @Override
    public void appendAll(List<LineageEvent> events) {
        for (LineageEvent event : events) {
            append(event);
        }
    }

    @Override
    public void appendV2(LineageEventV2 event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event.sequenceNumber() != 0) {
            // v2.3.18 ②: every newly created UNSEQUENCED row has sequence 0 — the fenced
            // finalizer is the only writer of a real sequence, in the same CAS as SEQUENCED.
            // Accepting a pre-sequenced event here would create a row the strict envelope
            // permanently refuses.
            throw new IllegalArgumentException("appendV2 requires sequenceNumber 0, got "
                    + event.sequenceNumber() + " — sequences are assigned by the fenced"
                    + " sequencer, never at append");
        }
        for (var e : event.publishStatusByTarget().entrySet()) {
            if (e.getValue() != LineagePublishStatus.PENDING) {
                // F5 (v2.3.19): the only initial status this slice writes is PENDING. The
                // creation-time classifications of the frozen table ((v1 legacy)/(oversize)
                // →UNRESOLVED, (cross-repo)→REJECTED) land WITH their producers, which must
                // write the durable reason shape the strict decoder requires — writing the
                // status without its reason would create a row every read permanently
                // refuses.
                throw new IllegalArgumentException("appendV2 initial status for target '"
                        + e.getKey() + "' must be PENDING, got " + e.getValue()
                        + " — creation-time classifications require their reason shapes"
                        + " (not implemented in this slice)");
            }
        }
        ensureDatabase();

        // §7's store-layer enforcement here IS the parameter type: every LineageEventV2 passed
        // the repository-scope, artifact-operation, shape and digest checks in its canonical
        // constructor, and records admit no other construction path. Re-running the validators
        // on an object that cannot exist unvalidated would be unreachable code wearing a safety
        // vest. The injection path this method cannot see — a raw document written straight to
        // CouchDB — bypasses appendV2 entirely, and is what the decode-side re-verification
        // catches on every read.

        String documentId = CouchLineageEventV2.documentId(event.deliveryId());
        Map<String, Object> doc = CouchLineageEventV2.toMap(event);
        // §8-a stores the row explicitly unsequenced. Lifecycle state lives at the store layer —
        // beside claimedAtByTarget and retryCountByTarget — not in the codec: it is what the
        // fenced sequencer (D-rest) mutates, not part of the event.
        doc.put("state", "UNSEQUENCED");

        // One retry for the conflict→vanished race: the conflicting document can be deleted
        // between the failed create and our read. Absence is neither idempotent success nor a
        // collision — it means the world changed under us, and the create is simply tried again.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (createIfAbsent(documentId, doc)) {
                logger.debug("Appended v2 lineage event: id={}, deliveryId={}", documentId,
                        event.deliveryId());
                return;
            }
            // Strict on purpose (v2.3.18): the wrapper's forgiving get() returns null for
            // outages too, and an outage after a real 409 is not "the occupant vanished".
            Map<String, Object> existing = readRawStrict(documentId);
            if (existing == null) {
                continue;
            }
            // v2.3.18 ②: the stored digest STRING is never trusted — the existing row is
            // decoded through the strict path (which recomputes identity and digest from the
            // stored content) and compared on the recomputed values. A row that cannot decode
            // is not an idempotent earlier attempt; it is a corrupt occupant of our key.
            LineageEventV2 decoded;
            try {
                decoded = CouchLineageEventV2.fromMap(existing);
            } catch (RuntimeException corrupt) {
                throw new LineageIntegrityException(event.deliveryId(),
                        event.creationPayloadDigest(),
                        existing.get("creationPayloadDigest") instanceof String d ? d : null);
            }
            boolean sameRecord = decoded.deliveryId().equals(event.deliveryId())
                    && decoded.creationPayloadDigest().equals(event.creationPayloadDigest());
            if (sameRecord) {
                // A retry landing after its first attempt succeeded. §3: 409 + exact digest
                // match is the only conflict that counts as success.
                logger.debug("v2 append found its own earlier attempt: deliveryId={}",
                        event.deliveryId());
                return;
            }
            throw new LineageIntegrityException(event.deliveryId(),
                    event.creationPayloadDigest(), decoded.creationPayloadDigest());
        }
        throw new IllegalStateException("v2 append for '" + event.deliveryId() + "' conflicted"
                + " and the conflicting document vanished before it could be read, twice —"
                + " transient storage contention; the caller's fail-open policy applies");
    }

    /**
     * Create-if-absent with a detectable conflict.
     *
     * <p>Not {@link CloudantClientWrapper#create(String, Map)}: both wrapper create methods
     * swallow every exception and return {@code null}, which makes a 409 indistinguishable from
     * an outage — and this method's whole job is that distinction. The raw SDK call is used so
     * {@code ConflictException} surfaces as itself and every other failure propagates.
     *
     * @return {@code true} if this call created the document; {@code false} on conflict
     */
    private boolean createIfAbsent(String documentId, Map<String, Object> properties) {
        com.ibm.cloud.cloudant.v1.model.Document doc =
                new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId(documentId);
        Map<String, Object> withoutMeta = new HashMap<>(properties);
        withoutMeta.remove("_id");
        withoutMeta.remove("_rev");
        doc.setProperties(withoutMeta);
        try {
            getLineageClient().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId(documentId)
                            .document(doc)
                            .build())
                    .execute();
            return true;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
            return false;
        } catch (RuntimeException e) {
            // CouchDB's size verdict is deterministic and must reach the caller as such on
            // EVERY v2 write path, not only the classified one (round-2 R1): otherwise an
            // ordinary chunk that the backend refuses is retried forever.
            if (isDocumentTooLarge(e)) {
                throw new LineageMaterializationStore.DocumentTooLargeException(
                        "CouchDB refused the document for its size: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRaw(String documentId) {
        return (Map<String, Object>) getLineageClient().get(Map.class, documentId, null);
    }

    @Override
    public List<LineageJournalRow> findByRepositoryId(String repositoryId, int limit, int offset) {
        if (!ensureClientForRead()) {
            return List.of();
        }

        Map<String, Object> params = new HashMap<>();
        // descending=true for newest-first within this repository
        params.put("startkey", List.of(repositoryId, "\ufff0"));
        params.put("endkey", List.of(repositoryId, ""));
        params.put("limit", limit);
        if (offset > 0) {
            params.put("skip", offset);
        }
        params.put("include_docs", true);
        params.put("descending", true);

        return queryRowsFromView("by_repository_and_time", params);
    }

    @Override
    public List<LineageJournalRow> findByProcessType(String repositoryId, LineageProcessType processType, int limit, int offset) {
        if (!ensureClientForRead()) {
            return List.of();
        }
        // Use by_repo_process_type_time view: key [repositoryId, processType, occurredAt]
        // descending=true gives newest-first ordering, consistent with unfiltered listings
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of(repositoryId, processType.name(), "\ufff0"));
        params.put("endkey", List.of(repositoryId, processType.name(), ""));
        params.put("descending", true);
        params.put("limit", limit);
        if (offset > 0) {
            params.put("skip", offset);
        }
        params.put("include_docs", true);
        return queryRowsFromView("by_repo_process_type_time", params);
    }

    @Override
    public List<LineageJournalRow> findByProcessType(LineageProcessType processType, int limit, int offset) {
        if (!ensureClientForRead()) {
            return List.of();
        }

        // Use by_process_type_time view: key [processType, occurredAt]
        // descending=true gives newest-first ordering, consistent with unfiltered listings
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of(processType.name(), "\ufff0"));
        params.put("endkey", List.of(processType.name(), ""));
        params.put("descending", true);
        params.put("limit", limit);
        if (offset > 0) {
            params.put("skip", offset);
        }
        params.put("include_docs", true);

        return queryRowsFromView("by_process_type_time", params);
    }

    @Override
    public int updatePublishStatus(String recordId, String target, LineagePublishStatus status) {
        if (recordId == null || recordId.isBlank()) {
            return 0;
        }
        if (!dbProvisioned.get()) {
            return 0;
        }

        try {
            String docId = CouchLineageEvent.journalDocumentId(recordId);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
                logger.debug("Row not found for updatePublishStatus: {}", recordId);
                return 0;
            }
            if ("lineage_event_v2".equals(doc.get("type"))) {
                // §8-b hard fence: v2 lifecycles move only through the token-fenced
                // LineageV2TransitionStore. The v1-only views make this unreachable from the
                // v1 loop; the guard is for any other caller that resolves a recordId blind.
                logger.error("updatePublishStatus refused for v2 row {} — v2 transitions are"
                        + " token-fenced (LineageV2TransitionStore)", recordId);
                return 0;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> statusMap = (Map<String, String>) doc.get("publishStatusByTarget");
            if (statusMap == null) {
                statusMap = new LinkedHashMap<>();
            } else {
                statusMap = new LinkedHashMap<>(statusMap);
            }
            statusMap.put(target, status.name());
            doc.put("publishStatusByTarget", statusMap);

            // Record claim timestamp on PROJECTING transition (for stale reaper)
            if (status == LineagePublishStatus.PROJECTING) {
                @SuppressWarnings("unchecked")
                Map<String, String> claimedAtMap = (Map<String, String>) doc.get("claimedAtByTarget");
                if (claimedAtMap == null) {
                    claimedAtMap = new LinkedHashMap<>();
                } else {
                    claimedAtMap = new LinkedHashMap<>(claimedAtMap);
                }
                claimedAtMap.put(target, Instant.now().toString());
                doc.put("claimedAtByTarget", claimedAtMap);
            }

            // Increment retry count on FAILED transition
            if (status == LineagePublishStatus.FAILED) {
                @SuppressWarnings("unchecked")
                Map<String, Object> retryCounts = (Map<String, Object>) doc.getOrDefault("retryCountByTarget", new LinkedHashMap<>());
                retryCounts = new LinkedHashMap<>(retryCounts);
                int current = retryCounts.containsKey(target) ? ((Number) retryCounts.get(target)).intValue() : 0;
                retryCounts.put(target, current + 1);
                doc.put("retryCountByTarget", retryCounts);
            }

            var result = getLineageClient().update(doc);
            if (result != null && result.isOk()) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            // 409 Conflict or other error
            logger.debug("Failed to update publish status for record {}: {}", recordId, e.getMessage());
            return 0;
        }
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        if (!dbProvisioned.get()) {
            return 0;
        }

        String cutoffStr = cutoff.toString();
        int totalPurged = 0;

        // Use by_occurred_at view (key = occurredAt) for accurate time-bounded purge.
        // The previous by_repository_and_time approach with composite key
        // [repositoryId, occurredAt] did not properly constrain time across
        // repositories because CouchDB evaluates array keys element-by-element.
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", "");
        params.put("endkey", cutoffStr);
        params.put("limit", PURGE_BATCH_SIZE);
        params.put("include_docs", true);

        List<LineageJournalRow> candidates = new ArrayList<>(
                queryRowsFromView("by_occurred_at", params));

        List<String[]> toDelete = new ArrayList<>(); // [id, rev] pairs
        for (LineageJournalRow row : candidates) {
            // An undecodable row is never purged: whether it is terminal cannot be judged, and
            // its stored document is the only evidence of what it was. Deleting the
            // unclassifiable is how evidence disappears politely.
            if (!(row instanceof LineageJournalRow.Decoded decoded)) {
                LineageJournalRow.Undecodable u = (LineageJournalRow.Undecodable) row;
                logger.warn("Purge skipping undecodable journal row {}: {}", u.documentId(), u.reason());
                continue;
            }
            LineageRecord record = decoded.entry().record();
            if (allTargetsPurgeEligible(record)) {
                String docId = CouchLineageEvent.journalDocumentId(record.recordId());
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
                if (doc != null) {
                    toDelete.add(new String[]{(String) doc.get("_id"), (String) doc.get("_rev")});
                }
            }
        }

        // The v2 half of purge (B1/F3): gated by the AGGREGATE readiness verdict — the same
        // single gate every other D-rest driver consults — and decoded through the STRICT v2
        // envelope, so a malformed row whose status map merely claims PUBLISHED is never
        // deleted. A disabled or unready system touches no v2 row; old binaries never query
        // this view name at all.
        LineageDrestReadiness readiness = drestReadinessProvider == null ? null
                : drestReadinessProvider.getIfAvailable();
        if (readiness != null && readiness.evaluate().ready()) {
            try {
                ViewResult v2Result = getLineageClient().getClient().postView(
                        new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                                .db(getLineageClient().getDatabaseName())
                                .ddoc(DESIGN_DOC)
                                .view("v2_by_occurred_at")
                                .endKey(cutoffStr)
                                .reduce(false)
                                .limit((long) PURGE_BATCH_SIZE)
                                .build())
                        .execute().getResult();
                if (v2Result != null && v2Result.getRows() != null) {
                    for (ViewResultRow row : v2Result.getRows()) {
                        String docId = row.getId();
                        try {
                            Map<String, Object> raw = readRawStrict(docId);
                            if (raw == null) {
                                continue;
                            }
                            LineageJournalRowV2 typed = decodeV2Strict(raw);
                            Map<String, LineageTargetLifecycle> lifecycles =
                                    typed.targetLifecycles();
                            // An empty lifecycle map means nothing was ever delivered —
                            // conservative: not purge-eligible (unlike v1's empty-map rule).
                            // §8-d (D-rest-3): a REQUESTED/CREATED request still owes a
                            // compensation the deterministic reconstruction derives from THIS
                            // row, and FAILED is the durable collision diagnosis — none may
                            // purge. ACKED alone does not block: the compensation row exists
                            // durably and has its own lifecycle.
                            boolean replayBlocks = typed.replayRequests().values().stream()
                                    .anyMatch(r -> r.state()
                                            != LineageReplayRequest.State.ACKED);
                            boolean eligible = !replayBlocks && !lifecycles.isEmpty()
                                    && lifecycles.values().stream().allMatch(
                                            lc -> lc.status().isPurgeEligible());
                            if (eligible) {
                                toDelete.add(new String[]{(String) raw.get("_id"),
                                        (String) raw.get("_rev")});
                            }
                        } catch (SequencingStorageException e) {
                            logger.warn("Purge skipping undecodable/unreadable v2 row {}: {}",
                                    docId, e.getMessage());
                        }
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("v2 purge scan failed (skipped this run): {}", e.getMessage());
            }
        }

        // Batch delete
        for (String[] idRev : toDelete) {
            try {
                getLineageClient().delete(idRev[0], idRev[1]);
                totalPurged++;
            } catch (Exception e) {
                logger.warn("Failed to purge lineage event {}: {}", idRev[0], e.getMessage());
            }
        }

        return totalPurged;
    }

    @Override
    public int discardEvent(String recordId, String target) {
        if (!dbProvisioned.get() || recordId == null || recordId.isBlank()) {
            return 0;
        }

        try {
            String docId = CouchLineageEvent.journalDocumentId(recordId);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
                return 0;
            }
            if ("lineage_event_v2".equals(doc.get("type"))) {
                // Same fence as updatePublishStatus: v2 discard additionally stays refused
                // until the spool-backed dead-letter capture is wired (v2.3.18 ⑧ + §9).
                logger.error("discardEvent refused for v2 row {} — v2 transitions are"
                        + " token-fenced (LineageV2TransitionStore)", recordId);
                return 0;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> statusMap = (Map<String, String>) doc.get("publishStatusByTarget");
            if (statusMap == null) {
                return 0;
            }

            String currentStatus = statusMap.get(target);
            if (currentStatus == null) {
                return 0;
            }

            // Only allow discard from PENDING or FAILED
            LineagePublishStatus current;
            try {
                current = LineagePublishStatus.valueOf(currentStatus);
            } catch (IllegalArgumentException e) {
                return 0;
            }
            if (current != LineagePublishStatus.PENDING && current != LineagePublishStatus.FAILED) {
                return 0;
            }

            statusMap = new LinkedHashMap<>(statusMap);
            statusMap.put(target, LineagePublishStatus.DISCARDED.name());
            doc.put("publishStatusByTarget", statusMap);

            var result = getLineageClient().update(doc);
            if (result != null && result.isOk()) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            logger.debug("Failed to discard row {}: {}", recordId, e.getMessage());
            return 0;
        }
    }

    @Override
    public long countNonTerminalByTarget(String target) {
        if (!ensureClientForRead()) {
            return 0;
        }

        // The v1 non-terminal set, EXPLICITLY (D-rest-2): this count feeds the destructive v1
        // drains, and its view is v1-only — iterating values() would silently admit each new
        // v2 state (VERIFYING, WAITING_FOR_CATALOG) into v1 policy arithmetic. v2 backlog is
        // counted by the v2 views, never here.
        long count = 0;
        for (LineagePublishStatus status : List.of(LineagePublishStatus.PENDING,
                LineagePublishStatus.PROJECTING, LineagePublishStatus.FAILED)) {
            count += queryTargetStatusCount(target, status.name());
        }
        return count;
    }


    @Override
    public List<LineageJournalRow> findAll(int limit, int offset) {
        if (!ensureClientForRead()) {
            return List.of();
        }

        int cappedLimit = Math.min(Math.max(limit, 1), 200);

        // by_occurred_at is v1-ONLY since the D-rest-2 schema split (purge isolation), so this
        // READ-ONLY listing merges it with v2_by_occurred_at in memory: fetch offset+limit from
        // EACH side from position 0 (a per-view skip would misdistribute the offset between
        // schemas), merge by occurredAt, then apply offset+limit to the merged order.
        // descending=true → newest first.
        int cappedOffset = boundedListingOffset(offset);
        int fetch = cappedOffset + cappedLimit; // both bounded: no overflow, bounded memory
        Map<String, Object> v1Params = new HashMap<>();
        v1Params.put("limit", fetch);
        v1Params.put("include_docs", true);
        v1Params.put("descending", true);
        List<LineageJournalRow> merged = mergeByOccurredAt(
                queryRowsFromView("by_occurred_at", v1Params),
                queryRowsFromView("v2_by_occurred_at", new HashMap<>(v1Params)),
                true);
        return sliceRows(merged, cappedOffset, cappedLimit);
    }

    /**
     * The merged listings fetch offset+limit rows PER SIDE, so the offset must be bounded —
     * an unbounded admin offset would be a client-controlled allocation (and could overflow).
     * 10k × 2 sides is the documented deep-pagination ceiling for these admin listings.
     */
    static final int MAX_LISTING_OFFSET = 10_000;

    private static int boundedListingOffset(int offset) {
        return Math.min(Math.max(offset, 0), MAX_LISTING_OFFSET);
    }

    /** Merge two occurredAt-ordered row lists, preserving each side's order. */
    private static List<LineageJournalRow> mergeByOccurredAt(List<LineageJournalRow> a,
            List<LineageJournalRow> b, boolean descending) {
        List<LineageJournalRow> merged = new ArrayList<>(a.size() + b.size());
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            String ka = occurredAtOf(a.get(i));
            String kb = occurredAtOf(b.get(j));
            int cmp = ka.compareTo(kb);
            boolean takeA = descending ? cmp >= 0 : cmp <= 0;
            merged.add(takeA ? a.get(i++) : b.get(j++));
        }
        while (i < a.size()) merged.add(a.get(i++));
        while (j < b.size()) merged.add(b.get(j++));
        return merged;
    }

    private static String occurredAtOf(LineageJournalRow row) {
        if (row instanceof LineageJournalRow.Decoded decoded) {
            String at = decoded.entry().record().occurredAt();
            return at == null ? "" : at;
        }
        return ""; // undecodable rows sort deterministically at the edge and stay visible
    }

    private static List<LineageJournalRow> sliceRows(List<LineageJournalRow> rows, int offset,
            int limit) {
        if (offset >= rows.size()) {
            return List.of();
        }
        return List.copyOf(rows.subList(offset, Math.min(rows.size(), offset + limit)));
    }

    @Override
    public LineageJournalRow findByRecordId(String recordId) {
        if (!ensureClientForRead() || recordId == null || recordId.isEmpty()) {
            return null;
        }

        try {
            String docId = CouchLineageEvent.journalDocumentId(recordId);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
                return null;
            }
            return LineageEventCodec.decodeRow(doc);
        } catch (Exception e) {
            logger.debug("Error finding lineage row by record id {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<LineageProcessType, Long> countByProcessType() {
        if (!ensureClientForRead()) {
            return Map.of();
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("reduce", true);
            params.put("group", true);

            ViewResult result = getLineageClient().queryView(DESIGN_DOC, "by_process_type", params);
            if (result == null || result.getRows() == null) {
                return Map.of();
            }

            Map<LineageProcessType, Long> counts = new LinkedHashMap<>();
            for (ViewResultRow row : result.getRows()) {
                Object key = row.getKey();
                Object value = row.getValue();
                if (key instanceof String keyStr && value instanceof Number num) {
                    try {
                        LineageProcessType pt = LineageProcessType.valueOf(keyStr);
                        counts.put(pt, num.longValue());
                    } catch (IllegalArgumentException ignored) {
                        // Unknown process type — skip
                    }
                }
            }
            return counts;
        } catch (Exception e) {
            logger.error("Error querying countByProcessType: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    @Override
    public List<LineageJournalRow> findByTargetAndStatus(String target, LineagePublishStatus status, int limit) {
        if (!ensureClientForRead()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        List<String> key = List.of(target, status.name());
        params.put("startkey", key);
        params.put("endkey", key);
        params.put("reduce", false);
        params.put("limit", limit);
        params.put("include_docs", true);
        return queryRowsFromView("by_target_status", params);
    }

    @Override
    public List<LineageJournalRow> findByTargetAndStatusOldestFirst(String target, LineagePublishStatus status, int limit) {
        if (!ensureClientForRead()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        // by_target_status_time key: [target, status, occurredAt] — ascending = oldest first
        params.put("startkey", List.of(target, status.name(), ""));
        params.put("endkey", List.of(target, status.name(), "\ufff0"));
        params.put("limit", limit);
        params.put("include_docs", true);
        return queryRowsFromView("by_target_status_time", params);
    }

    @Override
    public int reapStaleProjecting(String target, int staleMinutes) {
        if (!ensureClientForRead()) {
            return 0;
        }
        Instant staleCutoff = Instant.now().minus(Duration.ofMinutes(staleMinutes));
        String staleCutoffStr = staleCutoff.toString();

        // Use the projecting_by_claimed_at view to query PROJECTING events
        // whose claimedAt (or occurredAt fallback) is <= staleCutoff.
        // The view key is [target, claimedAt], so the range query returns
        // only events that are actually stale — no window limitation.
        int totalReaped = 0;
        int batchSize = 200;
        int maxIterations = 50; // safeguard: at most 10,000 stale events
        for (int i = 0; i < maxIterations; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("startkey", List.of(target, ""));
            params.put("endkey", List.of(target, staleCutoffStr));
            params.put("limit", batchSize);
            params.put("include_docs", true);

            List<LineageJournalRow> staleProjecting = queryRowsFromView("projecting_by_claimed_at", params);
            if (staleProjecting.isEmpty()) break;

            int batchReaped = 0;
            for (LineageJournalRow row : staleProjecting) {
                // The status flip mutates the raw document and needs no decode, so a stale claim
                // is released even on a row that cannot decode — otherwise a corrupt row that
                // died mid-claim would hold PROJECTING forever.
                String recordId = switch (row) {
                    case LineageJournalRow.Decoded decoded -> decoded.entry().record().recordId();
                    case LineageJournalRow.Undecodable u -> u.documentId() != null
                            && u.documentId().startsWith(CouchLineageEvent.ID_PREFIX)
                            ? u.documentId().substring(CouchLineageEvent.ID_PREFIX.length()) : null;
                };
                if (recordId == null) {
                    continue;
                }
                try {
                    int reset = updatePublishStatus(recordId, target, LineagePublishStatus.FAILED);
                    if (reset > 0) {
                        logger.info("Reset stale PROJECTING row to FAILED: recordId={}, target={}",
                                recordId, target);
                        batchReaped++;
                    }
                } catch (Exception e) {
                    logger.debug("Error resetting stale row {}: {}", recordId, e.getMessage());
                }
            }
            totalReaped += batchReaped;

            // Stop iterating when no progress was made in this batch
            if (batchReaped == 0) break;
        }
        return totalReaped;
    }

    @Override
    public int getRetryCount(String recordId, String target) {
        if (!ensureClientForRead() || recordId == null || recordId.isBlank()) {
            return 0;
        }
        try {
            String docId = CouchLineageEvent.journalDocumentId(recordId);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) return 0;

            @SuppressWarnings("unchecked")
            Map<String, Object> retryCounts = (Map<String, Object>) doc.get("retryCountByTarget");
            if (retryCounts == null) return 0;

            Object count = retryCounts.get(target);
            return (count instanceof Number) ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            logger.debug("Error reading retry count for record {}: {}", recordId, e.getMessage());
            return 0;
        }
    }



    @Override
    public List<LineageJournalRow> findByDateRange(String start, String end, int limit, int offset) {
        if (!ensureClientForRead()) return List.of();
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        // Same read-only merge as findAll (the view split is for purge/old-binary isolation;
        // listings remain dual-schema). Ascending here.
        int cappedOffset = boundedListingOffset(offset);
        int fetch = cappedOffset + cappedLimit;
        List<LineageJournalRow> merged = mergeByOccurredAt(
                queryRowsFromView("by_occurred_at", start, end, false, fetch, 0),
                queryRowsFromView("v2_by_occurred_at", start, end, false, fetch, 0),
                false);
        return sliceRows(merged, cappedOffset, cappedLimit);
    }

    @Override
    public List<LineageJournalRow> findByRepositoryAndSequenceRange(String repositoryId, long fromSequence, int limit) {
        if (!ensureClientForRead()) return List.of();
        int cappedLimit = Math.min(Math.max(limit, 1), 200);

        // by_repository_and_sequence view key = [repositoryId, sequenceNumber]
        // Query: startkey=[repoId, fromSequence+1], endkey=[repoId, {}], ascending
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of(repositoryId, fromSequence + 1));
        params.put("endkey", List.of(repositoryId, new LinkedHashMap<>())); // {} sorts after all numbers
        params.put("limit", cappedLimit);
        params.put("include_docs", true);
        params.put("reduce", false);
        return queryRowsFromView("by_repository_and_sequence", params);
    }

    @Override
    public long getEstimatedNonTerminalSizeBytes(String target) {
        if (!isActive()) return 0;
        try {
            DatabaseInformation dbInfo = getLineageClient().getDatabaseInfo();
            if (dbInfo != null && dbInfo.getSizes() != null) {
                long activeSize = dbInfo.getSizes().getActive();
                long totalDocs = dbInfo.getDocCount();
                if (totalDocs == 0) return 0;
                long nonTerminal = countNonTerminalByTarget(target);
                return (long) ((double) nonTerminal / totalDocs * activeSize);
            }
        } catch (Exception e) {
            logger.debug("Failed to get database info for size estimation, falling back to count-based", e);
        }
        // Fallback: count × estimated average size
        return countNonTerminalByTarget(target) * 2048;
    }

    @Override
    public List<String> findDistinctNonTerminalRepositoryIds(String target) {
        if (!ensureClientForRead()) {
            return List.of();
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("startkey", List.of(target, ""));
            params.put("endkey", List.of(target, "\ufff0"));
            params.put("reduce", true);
            params.put("group", true);

            ViewResult result = getLineageClient().queryView(DESIGN_DOC, "non_terminal_by_target_repo", params);
            if (result == null || result.getRows() == null) {
                return List.of();
            }

            List<String> repositoryIds = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                Object key = row.getKey();
                if (key instanceof List<?> keyList && keyList.size() >= 2) {
                    String repoId = String.valueOf(keyList.get(1));
                    if (!repositoryIds.contains(repoId)) {
                        repositoryIds.add(repoId);
                    }
                }
            }
            return repositoryIds;
        } catch (Exception e) {
            logger.debug("Error querying distinct non-terminal repository IDs for target {}: {}", target, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isActive() {
        // Active = journal DB exists and is reachable, regardless of global mode.
        // This handles the case where only specific repos have journaled overrides.
        if (dbProvisioned.get()) {
            return true;
        }
        // Try lazy discovery (same as ensureClientForRead)
        return ensureClientForRead();
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Check if an event with the given eventKey already exists.
     */
    private boolean eventKeyExists(String eventKey) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("key", eventKey);
            params.put("limit", 1);
            params.put("include_docs", false);

            ViewResult result = getLineageClient().queryView(DESIGN_DOC, "by_event_key", params);
            return result != null && result.getRows() != null && !result.getRows().isEmpty();
        } catch (Exception e) {
            logger.warn("Error checking eventKey existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Assigns a monotonically increasing sequence number for the given repository
     * using CouchDB compare-and-set on a sequence counter document.
     */
    private long assignSequenceNumber(String repositoryId) {
        String seqDocId = SEQ_PREFIX + repositoryId;
        CloudantClientWrapper client = getLineageClient();

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> seqDoc = (Map<String, Object>) client.get(Map.class, seqDocId, null);

                if (seqDoc == null) {
                    // Counter doesn't exist yet — create with seq=1
                    Map<String, Object> newDoc = new LinkedHashMap<>();
                    newDoc.put("_id", seqDocId);
                    newDoc.put("type", "lineage_sequence");
                    newDoc.put("repositoryId", repositoryId);
                    newDoc.put("seq", 1L);

                    var result = client.create(newDoc);
                    if (result != null && result.getId() != null) {
                        return 1L;
                    }
                    // Creation failed (race condition) — retry
                    continue;
                }

                // Increment existing counter
                Object currentSeqObj = seqDoc.get("seq");
                long currentSeq = (currentSeqObj instanceof Number) ? ((Number) currentSeqObj).longValue() : 0L;
                long nextSeq = currentSeq + 1;

                seqDoc.put("seq", nextSeq);
                var result = client.update(seqDoc);
                if (result != null && result.isOk()) {
                    return nextSeq;
                }
                // Update failed (409 Conflict) — retry
            } catch (Exception e) {
                logger.debug("CAS retry {} for sequence {}: {}", attempt + 1, seqDocId, e.getMessage());
            }
        }

        throw new RuntimeException("Failed to assign sequence number after " + MAX_CAS_RETRIES + " CAS retries for " + repositoryId);
    }

    /**
     * Query a view and decode each row independently.
     *
     * <p>Per-row on purpose. The previous form decoded inside one try/catch around the whole
     * loop, so a single corrupt row turned the entire batch into an empty list — every healthy
     * row hidden by the one broken one. Now a row that cannot decode comes back as
     * {@link LineageJournalRow.Undecodable} and the healthy rows come back as themselves; what to
     * do about the broken one is the caller's decision, because only the caller knows whether
     * order matters (the outer catch stays: a view-level query failure has no rows to report).
     *
     * <p>Note: Cloudant SDK's {@code Document} does NOT implement {@code Map<String, Object>};
     * properties are extracted via {@code getProperties()} and {@code getId()}/{@code getRev()}.
     */
    private List<LineageJournalRow> queryRowsFromView(String viewName, Map<String, Object> params) {
        try {
            ViewResult result = getLineageClient().queryView(DESIGN_DOC, viewName, params);
            if (result == null || result.getRows() == null) {
                return List.of();
            }

            List<LineageJournalRow> rows = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                if (doc != null) {
                    Map<String, Object> props = new HashMap<>();
                    if (doc.getId() != null) props.put("_id", doc.getId());
                    if (doc.getRev() != null) props.put("_rev", doc.getRev());
                    if (doc.getProperties() != null) props.putAll(doc.getProperties());
                    rows.add(LineageEventCodec.decodeRow(props));
                }
            }
            return rows;
        } catch (Exception e) {
            logger.error("Error querying lineage view {}: {}", viewName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Convenience overload for range queries on single-key views.
     */
    private List<LineageJournalRow> queryRowsFromView(String viewName, String startKey, String endKey,
                                                      boolean descending, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        if (startKey != null) params.put(descending ? "endkey" : "startkey", startKey);
        if (endKey != null) params.put(descending ? "startkey" : "endkey", endKey);
        params.put("descending", descending);
        params.put("limit", limit);
        if (offset > 0) params.put("skip", offset);
        params.put("include_docs", true);
        return queryRowsFromView(viewName, params);
    }

    /**
     * Check if all targets in the event are in terminal state.
     */
    /**
     * Purge keys off {@link LineagePublishStatus#isPurgeEligible()}, not
     * {@link LineagePublishStatus#isTerminal()}: REJECTED is terminal for the projector but its
     * document is the only evidence of the violation it records, so retention must not delete it
     * until increment E's durable record exists.
     */
    private boolean allTargetsPurgeEligible(LineageRecord record) {
        if (record.publishStatusByTarget().isEmpty()) {
            return true;
        }
        return record.publishStatusByTarget().values().stream()
                .allMatch(LineagePublishStatus::isPurgeEligible);
    }

    /**
     * Query the count of events for a specific target+status combination.
     */
    private long queryTargetStatusCount(String target, String status) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("key", List.of(target, status));
            params.put("reduce", true);
            params.put("group", true);

            ViewResult result = getLineageClient().queryView(DESIGN_DOC, "by_target_status", params);
            if (result != null && result.getRows() != null && !result.getRows().isEmpty()) {
                Object value = result.getRows().get(0).getValue();
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            }
            return 0;
        } catch (Exception e) {
            logger.debug("Error querying target status count for [{}, {}]: {}", target, status, e.getMessage());
            return 0;
        }
    }
    // ==================================================================
    // LineageSequencingStore — §8-a v2 (D-rest-1). Deployed dual and inert: nothing calls
    // these in production until activation, and none of the v1 methods above changed.
    // ==================================================================

    private static final String SEQUENCER_LEASE_PREFIX = "lineage_sequencer_lease:";
    private static final int ALLOCATOR_CAS_RETRIES = 5;

    private static String leaseDocumentId(String repositoryId) {
        return SEQUENCER_LEASE_PREFIX + repositoryId;
    }

    /**
     * Strict raw read for the sequencing surface: 404 is {@code null} (an ordinary answer);
     * anything else is a {@link SequencingStorageException} — the wrapper's forgiving
     * {@code get()} returns null for outages too, which would let an infrastructure failure
     * impersonate LEASE_MISSING or COUNTER_MISSING and mis-route the recovery.
     */
    private Map<String, Object> readRawStrict(String documentId) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc = getLineageClient().getClient()
                    .getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                            .Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId(documentId)
                            .build())
                    .execute().getResult();
            Map<String, Object> props = new HashMap<>();
            if (doc.getId() != null) props.put("_id", doc.getId());
            if (doc.getRev() != null) props.put("_rev", doc.getRev());
            if (doc.getProperties() != null) props.putAll(doc.getProperties());
            return props;
        } catch (NotFoundException notFound) {
            return null;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("read failed for '" + documentId + "'", e);
        }
    }

    /**
     * Strict CAS update for the sequencing surface: true = committed, false = 409 (an
     * ordinary CAS loss); infrastructure failures throw — an outage reported as "conflict"
     * would make the sequencer re-read forever instead of latching.
     */
    private boolean updateStrictCas(Map<String, Object> raw) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(raw);
            Object id = withoutMeta.remove("_id");
            Object rev = withoutMeta.remove("_rev");
            doc.setProperties(withoutMeta);
            doc.setId((String) id);
            doc.setRev((String) rev);
            getLineageClient().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId((String) id)
                            .document(doc)
                            .build())
                    .execute();
            return true;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            return false;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("CAS update failed for '"
                    + raw.get("_id") + "'", e);
        }
    }

    /** Exact integral conversion — Gson hands back LazilyParsedNumber; fractions must fail. */
    private static long exactLong(Object value, String what) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(what + " must be a number, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        try {
            return new java.math.BigDecimal(n.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an exact integral value, got "
                    + n);
        }
    }

    @Override
    public java.util.Optional<LeaseGrant> acquireSequencerLease(String repositoryId,
            String nodeId, java.time.Duration ttl) {
        ensureDatabase();
        Map<String, Object> lease = readRawStrict(leaseDocumentId(repositoryId));
        if (lease == null) {
            // §8-a: created by the bootstrap patch only. Operation never creates it — a
            // recreated lease would restart the generation high-watermark.
            throw new LeaseMissingException(repositoryId);
        }
        String owner = lease.get("owner") instanceof String o && !o.isBlank() ? o : null;
        String expiresAt = lease.get("expiresAt") instanceof String e ? e : null;
        boolean free = owner == null
                || (expiresAt != null && isExpired(expiresAt));
        if (!free) {
            return java.util.Optional.empty();
        }
        long generation;
        try {
            generation = exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            generation = -1L;
        }
        if (generation < 0) {
            logger.error("Sequencer lease for {} has a malformed generation — refusing to"
                    + " acquire", repositoryId);
            return java.util.Optional.empty();
        }
        long nextGeneration = Math.addExact(generation, 1);
        String token = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
        String newExpiresAt = Instant.now().plus(ttl).toString();
        lease.put("generation", nextGeneration);
        lease.put("sequencerLeaseToken", token);
        lease.put("owner", nodeId);
        lease.put("expiresAt", newExpiresAt);
        if (updateStrictCas(lease)) {
            Map<String, Object> committed = readRawStrict(leaseDocumentId(repositoryId));
            String rev = committed != null && committed.get("_rev") instanceof String r
                    ? r : "";
            return java.util.Optional.of(new LeaseGrant(repositoryId, nextGeneration, token,
                    nodeId, newExpiresAt, rev));
        }
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<LeaseGrant> renewSequencerLease(LeaseGrant grant,
            java.time.Duration ttl) {
        if (grant == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> lease = readRawStrict(leaseDocumentId(grant.repositoryId()));
        if (lease == null || !matchesGrant(lease, grant)) {
            return java.util.Optional.empty();
        }
        String newExpiresAt = Instant.now().plus(ttl).toString();
        lease.put("expiresAt", newExpiresAt);
        if (updateStrictCas(lease)) {
            Map<String, Object> committed = readRawStrict(
                    leaseDocumentId(grant.repositoryId()));
            String rev = committed != null && committed.get("_rev") instanceof String r
                    ? r : "";
            return java.util.Optional.of(new LeaseGrant(grant.repositoryId(),
                    grant.generation(), grant.sequencerLeaseToken(), grant.owner(),
                    newExpiresAt, rev));
        }
        return java.util.Optional.empty();
    }

    @Override
    public void releaseSequencerLease(LeaseGrant grant) {
        if (grant == null) {
            return;
        }
        try {
            Map<String, Object> lease = readRawStrict(leaseDocumentId(grant.repositoryId()));
            if (lease == null || !matchesGrant(lease, grant)
                    || !grant.rev().equals(lease.get("_rev"))) {
                // The frozen contract is an owner/generation/token/_rev CAS: a grant whose
                // _rev is stale (the worker renewed since) must not release the newer hold.
                return;
            }
            lease.put("owner", null);
            lease.put("expiresAt", Instant.EPOCH.toString());
            // generation and token stay: the document is the generation high-watermark and
            // is never deleted (§8-a).
            updateStrictCas(lease);
        } catch (RuntimeException e) {
            // Release is best-effort by design: expiry frees the lease anyway, and a release
            // failure must not mask the run's real outcome.
            logger.debug("Sequencer lease release skipped for {}: {}", grant.repositoryId(),
                    e.getMessage());
        }
    }

    @Override
    public java.util.Optional<LeaseView> readSequencerLease(String repositoryId) {
        Map<String, Object> lease = readRawStrict(leaseDocumentId(repositoryId));
        if (lease == null) {
            return java.util.Optional.empty();
        }
        long generation;
        try {
            generation = exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            generation = -1L;
        }
        return java.util.Optional.of(new LeaseView(
                generation,
                lease.get("sequencerLeaseToken") instanceof String t ? t : null,
                lease.get("owner") instanceof String o && !o.isBlank() ? o : null,
                lease.get("expiresAt") instanceof String e ? e : null));
    }

    private static boolean matchesGrant(Map<String, Object> lease, LeaseGrant grant) {
        long generation;
        try {
            generation = exactLong(lease.get("generation"), "lease generation");
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        String token = lease.get("sequencerLeaseToken") instanceof String t ? t : null;
        String owner = lease.get("owner") instanceof String o ? o : null;
        return generation == grant.generation()
                && grant.sequencerLeaseToken().equals(token)
                && grant.owner().equals(owner);
    }

    private static boolean isExpired(String expiresAt) {
        try {
            return Instant.parse(expiresAt).isBefore(Instant.now());
        } catch (RuntimeException e) {
            // A lease whose expiry cannot be parsed must not be treated as free — that would
            // let two nodes hold it. Unparseable = held forever = a management repair case.
            return false;
        }
    }

    @Override
    public List<LineageJournalRowV2> findUnsequencedV2(String repositoryId, int limit) {
        return queryV2RowsInClaimOrder("v2_sequencer_backlog", repositoryId, limit);
    }

    @Override
    public List<LineageJournalRowV2> findSequencingV2(String repositoryId, int limit) {
        return queryV2RowsInClaimOrder("v2_sequencer_in_flight", repositoryId, limit);
    }

    private List<LineageJournalRowV2> queryV2RowsInClaimOrder(String viewName,
            String repositoryId, int limit) {
        try {
            // Raw postView, not the shared wrapper: the wrapper returns null for a missing
            // design document, and an empty backlog and a broken index must not look alike —
            // the sequencer would release FENCED_OK over an outage instead of latching.
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view(viewName)
                            .startKey(List.of(repositoryId))
                            .endKey(List.of(repositoryId, new HashMap<>(), new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                // postView returning no result object at all is an abnormal answer, not an
                // empty backlog — empty is a result with zero rows.
                throw new IllegalStateException("view '" + viewName + "' returned no result");
            }
            List<LineageJournalRowV2> rows = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                Map<String, Object> props = new HashMap<>();
                if (doc != null) {
                    if (doc.getId() != null) props.put("_id", doc.getId());
                    if (doc.getRev() != null) props.put("_rev", doc.getRev());
                    if (doc.getProperties() != null) props.putAll(doc.getProperties());
                }
                try {
                    if (doc == null) {
                        // include_docs promised a document; its absence is view/store
                        // inconsistency, exactly as blocking as an undecodable row.
                        throw new IllegalStateException("view row without a document");
                    }
                    rows.add(CouchLineageJournalRowV2.fromRaw(props));
                } catch (RuntimeException e) {
                    // Deterministic order is the contract: sequencing PAST a broken row would
                    // hand later occurredAt values lower positions than the row will get when
                    // repaired. Healthy rows BEFORE the barrier proceed (the queue drains up
                    // to it over successive passes); once the broken row is at the head there
                    // is no progress to report, and pretending "empty backlog / FENCED_OK"
                    // over a blocked queue is the one forbidden answer — so an empty prefix
                    // throws, which the sequencer and the backlog probes surface as STOPPED.
                    if (rows.isEmpty()) {
                        throw new SequencingStorageException("corrupt v2 row '"
                                + props.get("_id") + "' blocks the head of the sequencing"
                                + " queue for '" + repositoryId + "'", e);
                    }
                    logger.error("Undecodable v2 row {} halts the sequencer scan at its"
                            + " position ({} healthy rows before it proceed): {}",
                            props.get("_id"), rows.size(), e.getMessage());
                    break;
                }
            }
            return rows;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("sequencer view '" + viewName
                    + "' query failed", e);
        }
    }

    @Override
    public boolean claimForSequencing(LineageJournalRowV2 row, long generation,
            String sequencerLeaseToken) {
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.UNSEQUENCED, null,
                raw -> CouchLineageJournalRowV2.applySequencing(raw, generation,
                        sequencerLeaseToken));
    }

    @Override
    public boolean reclaimForSequencing(LineageJournalRowV2 row, long staleGeneration,
            long generation, String sequencerLeaseToken) {
        if (staleGeneration >= generation) {
            // §8-a: reclaim only takes rows from strictly older generations. An equal
            // generation is our own in-flight row; a newer one is not ours to touch.
            return false;
        }
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.SEQUENCING,
                staleGeneration,
                raw -> CouchLineageJournalRowV2.applySequencing(raw, generation,
                        sequencerLeaseToken));
    }

    @Override
    public boolean finalizeSequence(LineageJournalRowV2 row, long generation,
            String sequencerLeaseToken, long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive, got " + sequence);
        }
        return casSequencingWrite(row, LineageJournalRowV2.SequencingState.SEQUENCING,
                generation, raw -> {
                    Object token = raw.get(CouchLineageJournalRowV2.FIELD_LEASE_TOKEN);
                    if (!sequencerLeaseToken.equals(token)) {
                        throw new StaleRowException();
                    }
                    CouchLineageJournalRowV2.applyFinalize(raw, sequence);
                });
    }

    /** Signals "the stored row no longer matches what the caller claimed to hold". */
    private static final class StaleRowException extends RuntimeException {
    }

    /**
     * The shared CAS shape: re-read the raw document, verify it still is what the caller
     * holds ({@code _rev}, state, and — when given — generation), apply the mutation
     * field-preservingly, and write under the verified {@code _rev}. Any mismatch or update
     * conflict is {@code false}: the world moved, the caller re-reads.
     */
    private boolean casSequencingWrite(LineageJournalRowV2 row,
            LineageJournalRowV2.SequencingState expectedState, Long expectedGeneration,
            java.util.function.Consumer<Map<String, Object>> mutation) {
        if (row == null) {
            return false;
        }
        try {
            Map<String, Object> raw = readRawStrict(row.documentId());
            if (raw == null) {
                return false;
            }
            if (!row.rev().equals(raw.get("_rev"))) {
                return false;
            }
            Object state = raw.get(CouchLineageJournalRowV2.FIELD_STATE);
            if (!expectedState.name().equals(state)) {
                return false;
            }
            if (expectedGeneration != null) {
                Object generation = raw.get(CouchLineageJournalRowV2.FIELD_GENERATION);
                try {
                    if (exactLong(generation, "sequencerGeneration") != expectedGeneration) {
                        return false;
                    }
                } catch (IllegalArgumentException malformed) {
                    return false;
                }
            }
            mutation.accept(raw);
            return updateStrictCas(raw);
        } catch (StaleRowException stale) {
            return false;
        }
        // SequencingStorageException propagates: an outage is not a CAS loss, and the
        // sequencer must latch on it rather than re-read forever.
    }

    @Override
    public long allocateSequenceFenced(String repositoryId) {
        ensureDatabase();
        String seqDocId = SEQ_PREFIX + repositoryId;
        for (int attempt = 0; attempt < ALLOCATOR_CAS_RETRIES; attempt++) {
            Map<String, Object> seqDoc = readRawStrict(seqDocId);
            if (seqDoc == null) {
                // v1's allocator would create it here with seq=1. This one never seeds (I-4):
                // a missing counter under existing history means rewound sequences.
                throw new SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is missing — bootstrap"
                                + " provisions it; the fenced allocator never seeds");
            }
            Object stored = seqDoc.get("seq");
            Number n;
            try {
                exactLong(stored, "sequence counter");
                n = (Number) stored;
            } catch (IllegalArgumentException malformed) {
                throw new SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is malformed: " + stored);
            }
            if (exactLong(n, "sequence counter") < 0) {
                throw new SequenceCounterException(SequencerHealth.COUNTER_MISSING,
                        "sequence counter for '" + repositoryId + "' is malformed: " + stored);
            }
            long current = exactLong(n, "sequence counter");
            long watermark = sequenceHighWatermark(repositoryId);
            if (current < watermark) {
                throw new SequenceCounterException(SequencerHealth.COUNTER_REWOUND,
                        "sequence counter for '" + repositoryId + "' is at " + current
                                + ", below the finalized high-watermark " + watermark
                                + " — refusing to allocate (I-2); recover manually");
            }
            long next = Math.addExact(current, 1);
            seqDoc.put("seq", next);
            if (updateStrictCas(seqDoc)) {
                return next;
            }
            // false = an ordinary CAS loss to a concurrent allocator; re-read and retry.
        }
        throw new SequenceCounterException(SequencerHealth.STOPPED,
                "fenced allocator for '" + repositoryId + "' lost " + ALLOCATOR_CAS_RETRIES
                        + " consecutive CAS attempts — transient contention, retry later");
    }

    @Override
    public long sequenceHighWatermark(String repositoryId) {
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("sequence_watermark")
                            .keys(List.of(repositoryId))
                            .group(true)
                            .reduce(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException("sequence_watermark returned no result");
            }
            if (result.getRows().isEmpty()) {
                return 0L; // grouped reduce over zero rows: genuinely no history
            }
            Object value = result.getRows().get(0).getValue();
            if (value == null) {
                return 0L; // reduce over zero rows
            }
            if (value instanceof Map<?, ?> stats && stats.get("max") instanceof Number max) {
                return exactLong(max, "sequence_watermark max");
            }
            // A reduce answer that is neither absent nor _stats-shaped is a broken index,
            // not a zero watermark.
            throw new IllegalStateException("malformed sequence_watermark reduce: " + value);
        } catch (RuntimeException e) {
            // The rewind check must not silently pass on a query failure: an unsafe failure
            // stops allocation (the sequencer latches) rather than allocating blind. A
            // missing view/design document lands here too — a sequencer without its views is
            // broken infrastructure, not an empty repository.
            if (e instanceof SequencingStorageException storage) {
                throw storage;
            }
            throw new SequencingStorageException(
                    "sequence watermark query failed for '" + repositoryId + "'", e);
        }
    }


    // ==================================================================
    // LineageV2TransitionStore — §8-b v2 (D-rest-2). Deployed dual and inert like the
    // sequencing surface: nothing calls these in production until D-rest activation.
    // ==================================================================

    /**
     * C1 (v2.3.19): compares the DEPLOYED design document against this binary's view
     * definitions — complete definitions, map source AND reduce (including reduce-absent).
     * An old binary redeploying its dual-schema views during a rolling window is exactly what
     * this catches; activation must refuse until the design doc is this binary's.
     *
     * @return violations (empty = signatures match); "deployment pending" style entries when
     *         the store/DB/design doc is not there yet — never a crash, never a silent pass
     */
    public List<String> viewSignatureViolations() {
        if (!isActive() || !dbProvisioned.get()) {
            return List.of("lineage store not active / database not provisioned yet");
        }
        Map<String, Object> designDoc;
        try {
            designDoc = readRawStrict("_design/" + DESIGN_DOC);
        } catch (RuntimeException e) {
            return List.of("design document unreadable: " + e.getMessage());
        }
        if (designDoc == null) {
            return List.of("design document '_design/" + DESIGN_DOC + "' not deployed yet");
        }
        Object viewsValue = designDoc.get("views");
        if (!(viewsValue instanceof Map<?, ?> deployed)) {
            return List.of("design document has no views map");
        }
        List<String> violations = new ArrayList<>();
        for (var expected : VIEWS.entrySet()) {
            Object entry = deployed.get(expected.getKey());
            if (!(entry instanceof Map<?, ?> view)) {
                violations.add("view '" + expected.getKey() + "' missing from deployed design"
                        + " document");
                continue;
            }
            Object map = view.get("map");
            if (!expected.getValue().map().equals(map)) {
                violations.add("view '" + expected.getKey() + "' map source differs from this"
                        + " binary's definition");
            }
            Object reduce = view.get("reduce");
            String expectedReduce = expected.getValue().reduce();
            boolean reduceMatches = expectedReduce == null
                    ? reduce == null
                    : expectedReduce.equals(reduce);
            if (!reduceMatches) {
                violations.add("view '" + expected.getKey() + "' reduce differs from this"
                        + " binary's definition");
            }
        }
        return violations;
    }

    private Map<String, Object> readV2RawStrict(String recordId) {
        String docId = CouchLineageEventV2.documentId(recordId);
        return readRawStrict(docId);
    }

    /** Decodes strictly; a malformed doc throws (never a value the machine could act on). */
    private LineageJournalRowV2 decodeV2Strict(Map<String, Object> raw) {
        try {
            return CouchLineageJournalRowV2.fromRaw(raw);
        } catch (RuntimeException e) {
            throw new SequencingStorageException("undecodable v2 row '" + raw.get("_id")
                    + "': " + e.getMessage(), e);
        }
    }

    @Override
    public V2ClaimGrant claimForProjection(String recordId, String target,
            java.time.Duration lease) {
        if (recordId == null || recordId.isBlank() || target == null || target.isBlank()
                || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("recordId, target and a positive lease are"
                    + " required");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return null;
        }
        if (!"lineage_event_v2".equals(raw.get("type"))) {
            logger.error("claimForProjection refused: '{}' is not a v2 row", recordId);
            return null;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        if (row.state() != LineageJournalRowV2.SequencingState.SEQUENCED) {
            // Not deliverable, whatever the status map says — claims exist only past the
            // sequencer's finalize.
            return null;
        }
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        LineagePublishStatus status = current == null
                ? LineagePublishStatus.PENDING : current.status();
        if (status != LineagePublishStatus.PENDING && status != LineagePublishStatus.FAILED) {
            return null;
        }
        long nowMs = Instant.now().toEpochMilli();
        long expiresMs = Math.addExact(nowMs, lease.toMillis());
        String token = java.util.UUID.randomUUID().toString();
        CouchLineageJournalRowV2.applyProjectionClaim(raw, target, token, nowMs, expiresMs);
        if (!updateStrictCas(raw)) {
            return null;
        }
        return new V2ClaimGrant(recordId, target, token, Instant.ofEpochMilli(expiresMs));
    }

    /** The token-fenced (expected→next) pairs and their effects, per the frozen §8-b table. */
    private record FencedEffect(boolean toVerifying, boolean incrementRetry,
                                boolean reasonRequired) {
    }

    private static final Map<List<LineagePublishStatus>, FencedEffect> FENCED_TRANSITIONS =
            Map.of(
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING),
                    new FencedEffect(true, false, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED),
                    new FencedEffect(false, false, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.FAILED),
                    new FencedEffect(false, false, false),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.FAILED),
                    new FencedEffect(false, true, false),
                    List.of(LineagePublishStatus.VERIFYING, LineagePublishStatus.UNPROJECTABLE),
                    new FencedEffect(false, false, true),
                    List.of(LineagePublishStatus.PROJECTING, LineagePublishStatus.REJECTED),
                    new FencedEffect(false, false, true));

    @Override
    public boolean transitionV2(String recordId, String target, LineagePublishStatus expected,
            LineagePublishStatus next, String claimToken,
            LineageTargetLifecycle.TerminalReason reason) {
        FencedEffect effect = FENCED_TRANSITIONS.get(List.of(expected, next));
        if (effect == null) {
            throw new IllegalArgumentException("transition " + expected + "->" + next
                    + " is not in the fenced §8-b table — caller bug, not a race");
        }
        if (effect.reasonRequired() && reason == null) {
            throw new IllegalArgumentException(next + " requires a durable terminal reason");
        }
        if (!effect.reasonRequired() && reason != null) {
            throw new IllegalArgumentException(next + " must not carry a terminal reason");
        }
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("fenced transitions require the claim token");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        if (current == null || current.status() != expected
                || !claimToken.equals(current.claimToken())) {
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        if (current.leaseExpiresAtMs() == null || current.leaseExpiresAtMs() <= nowMs) {
            // F4: EVERY claimant write fails after expiry — an expired claimant racing the
            // reaper must lose, not settle. (The reaper's FAILED write CAS-beats us anyway;
            // this makes the fence explicit rather than a rev-timing accident.)
            return false;
        }
        if (effect.toVerifying()) {
            // PROJECTING→VERIFYING renews the lease ATOMICALLY in the same CAS (§8-b: the
            // transition row says "renews lease"). Lease policy is the store's (config).
            // Null-safe for the direct-client test construction (no Spring context);
            // production always injects the config.
            long leaseSeconds = lineageConfig != null
                    ? lineageConfig.getProjectionClaimLeaseSeconds() : 120L;
            long leaseMs = Math.addExact(nowMs,
                    java.time.Duration.ofSeconds(leaseSeconds).toMillis());
            CouchLineageJournalRowV2.applyVerifying(raw, target, nowMs, leaseMs);
        } else {
            CouchLineageJournalRowV2.applySettle(raw, target, next, effect.incrementRetry(),
                    reason);
        }
        return updateStrictCas(raw);
    }

    /** The pre-claim (expected→next) pairs: obligation + admin rows of the frozen table. */
    private static final Map<List<LineagePublishStatus>, Boolean> UNCLAIMED_TRANSITIONS =
            Map.of(
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.WAITING_FOR_CATALOG), false,
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.PENDING), false,
                    List.of(LineagePublishStatus.WAITING_FOR_CATALOG,
                            LineagePublishStatus.UNRESOLVED), true,
                    List.of(LineagePublishStatus.PENDING,
                            LineagePublishStatus.DISCARDED), false,
                    List.of(LineagePublishStatus.FAILED,
                            LineagePublishStatus.DISCARDED), false);

    @Override
    public boolean transitionV2Unclaimed(String recordId, String target,
            LineagePublishStatus expected, LineagePublishStatus next,
            LineageTargetLifecycle.TerminalReason reason) {
        Boolean reasonRequired = UNCLAIMED_TRANSITIONS.get(List.of(expected, next));
        if (reasonRequired == null) {
            throw new IllegalArgumentException("transition " + expected + "->" + next
                    + " is not in the unclaimed §8-b table — caller bug, not a race");
        }
        if (reasonRequired && reason == null) {
            throw new IllegalArgumentException(next + " requires a durable terminal reason");
        }
        if (!reasonRequired && reason != null) {
            throw new IllegalArgumentException(next + " must not carry a terminal reason");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        LineagePublishStatus status = current == null
                ? LineagePublishStatus.PENDING : current.status();
        if (status != expected) {
            return false;
        }
        if (expected == LineagePublishStatus.FAILED) {
            // FAILED→DISCARDED: status-only write; the audit bundle rides along untouched
            // (field-preserving map mutation — nothing is removed).
            CouchLineageJournalRowV2.applySettle(raw, target, next, false, reason);
        } else {
            CouchLineageJournalRowV2.applyStatusOnly(raw, target, next, reason);
        }
        return updateStrictCas(raw);
    }

    @Override
    public boolean renewClaim(String recordId, String target, String claimToken,
            java.time.Duration lease) {
        if (claimToken == null || claimToken.isBlank() || lease == null
                || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("claim token and a positive lease are required");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        LineageTargetLifecycle current = row.targetLifecycles().get(target);
        if (current == null || !current.hasLiveClaim()
                || !claimToken.equals(current.claimToken())) {
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        if (current.leaseExpiresAtMs() == null || current.leaseExpiresAtMs() <= nowMs) {
            // An expired claim never self-resurrects — it goes through the reaper like
            // anyone's.
            return false;
        }
        CouchLineageJournalRowV2.applyRenew(raw, target, Math.addExact(nowMs, lease.toMillis()));
        return updateStrictCas(raw);
    }

    @Override
    public int reapExpiredClaims(String target, Instant cutoff) {
        if (target == null || target.isBlank() || cutoff == null) {
            throw new IllegalArgumentException("target and cutoff are required");
        }
        ensureDatabase();
        long cutoffMs = cutoff.toEpochMilli();
        int reaped = 0;
        // Mutation-safe pagination (F2): a successful reap REMOVES its row from this view, so
        // an anchor-plus-skip continuation would skip the first surviving candidate once its
        // anchor vanished. Instead: no skip, an in-memory examined set (bounded by this run),
        // and an anchor that advances to the last row of every page — corrupt or CAS-lost
        // rows are re-served by CouchDB but never re-examined, and can never pin a page.
        java.util.Set<String> examined = new java.util.HashSet<>();
        Object pageStartKey = List.of(target, 0L);
        String pageStartDocId = null;
        int pageSize = 100;
        while (true) {
            ViewResult result;
            try {
                var builder = new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                        .db(getLineageClient().getDatabaseName())
                        .ddoc(DESIGN_DOC)
                        .view("v2_claims_by_expiry")
                        .startKey(pageStartKey)
                        .endKey(List.of(target, cutoffMs))
                        .inclusiveEnd(false)
                        .reduce(false)
                        .limit((long) pageSize);
                if (pageStartDocId != null) {
                    builder.startKeyDocId(pageStartDocId);
                }
                result = getLineageClient().getClient().postView(builder.build())
                        .execute().getResult();
            } catch (RuntimeException e) {
                throw new SequencingStorageException("v2_claims_by_expiry query failed for '"
                        + target + "'", e);
            }
            if (result == null || result.getRows() == null) {
                throw new SequencingStorageException("v2_claims_by_expiry returned no result"
                        + " for '" + target + "'", null);
            }
            List<ViewResultRow> rows = result.getRows();
            boolean sawNew = false;
            for (ViewResultRow viewRow : rows) {
                String docId = viewRow.getId();
                if (!examined.add(docId)) {
                    continue;
                }
                sawNew = true;
                try {
                    Map<String, Object> raw = readRawStrict(docId);
                    if (raw == null) {
                        continue;
                    }
                    LineageJournalRowV2 row = decodeV2Strict(raw);
                    LineageTargetLifecycle current = row.targetLifecycles().get(target);
                    if (current == null || !current.hasLiveClaim()
                            || current.leaseExpiresAtMs() == null
                            || current.leaseExpiresAtMs() >= cutoffMs) {
                        continue; // stale view entry, rotated claim, or no longer live
                    }
                    // Reap-by-CAS: the status/token just reread are what the CAS write rides
                    // on (_rev). No retry increment — a crashed claim is not an observed
                    // publish failure.
                    CouchLineageJournalRowV2.applySettle(raw, target,
                            LineagePublishStatus.FAILED, false, null);
                    if (updateStrictCas(raw)) {
                        reaped++;
                        if (lineageMetrics != null) {
                            lineageMetrics.recordV2ClaimReaped(target);
                        }
                    }
                } catch (SequencingStorageException e) {
                    // Corrupt rows are refused loudly and cannot pin the page (the examined
                    // set carries the scan past them); infra failures abort the reap.
                    if (e.getCause() instanceof NotFoundException
                            || e.getMessage() != null
                            && e.getMessage().startsWith("undecodable v2 row")) {
                        logger.error("Reaper skipping corrupt v2 row {}: {}", docId,
                                e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
            if (rows.size() < pageSize) {
                return reaped;
            }
            if (!sawNew && rows.size() == pageSize) {
                // A full page of already-examined rows: force the anchor past it.
                ViewResultRow last = rows.get(rows.size() - 1);
                pageStartKey = last.getKey();
                pageStartDocId = last.getId();
                continue;
            }
            ViewResultRow last = rows.get(rows.size() - 1);
            pageStartKey = last.getKey();
            pageStartDocId = last.getId();
        }
    }

    @Override
    public List<LineageJournalRowV2> findV2ByRepositoryAndSequenceRange(String repositoryId,
            long fromSequence, int limit) {
        ensureDatabase();
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("v2_by_repository_and_sequence")
                            // Strictly-after AT THE QUERY (F1): filtering the cursor row out
                            // AFTER the limit shrinks a full page to batchSize-1, which the
                            // merge-window arithmetic reads as coverage-to-infinity and can
                            // skip an unreturned v2 row. Same pattern as the v1 method.
                            .startKey(List.of(repositoryId, Math.addExact(fromSequence, 1)))
                            .endKey(List.of(repositoryId, new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException(
                        "v2_by_repository_and_sequence returned no result");
            }
            List<LineageJournalRowV2> rows = new ArrayList<>();
            for (ViewResultRow viewRow : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = viewRow.getDoc();
                if (doc == null) {
                    throw new IllegalStateException("view row without a document");
                }
                Map<String, Object> props = new HashMap<>();
                if (doc.getId() != null) props.put("_id", doc.getId());
                if (doc.getRev() != null) props.put("_rev", doc.getRev());
                if (doc.getProperties() != null) props.putAll(doc.getProperties());
                rows.add(decodeV2Strict(props));
            }
            return rows;
        } catch (SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SequencingStorageException(
                    "v2_by_repository_and_sequence query failed for '" + repositoryId + "'", e);
        }
    }

    /**
     * The §8-b VERIFYING gauges (F8): count via the view's reduce, oldest verifyingSinceMs via
     * the first ascending row. Diagnostic surface (admin GET) — never a drain input.
     */
    public Map<String, Object> verifyingStats(String target) {
        ensureDatabase();
        try {
            ViewResult countResult = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("v2_verifying_by_since")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .build())
                    .execute().getResult();
            long count = 0;
            if (countResult != null && countResult.getRows() != null
                    && !countResult.getRows().isEmpty()
                    && countResult.getRows().get(0).getValue() instanceof Number n) {
                count = exactLong(n, "verifying count");
            }
            Long oldestSinceMs = null;
            if (count > 0) {
                ViewResult oldest = getLineageClient().getClient().postView(
                        new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                                .db(getLineageClient().getDatabaseName())
                                .ddoc(DESIGN_DOC)
                                .view("v2_verifying_by_since")
                                .startKey(List.of(target))
                                .endKey(List.of(target, new HashMap<>()))
                                .reduce(false)
                                .limit(1L)
                                .build())
                        .execute().getResult();
                if (oldest != null && oldest.getRows() != null && !oldest.getRows().isEmpty()
                        && oldest.getRows().get(0).getKey() instanceof List<?> key
                        && key.size() == 2 && key.get(1) instanceof Number since) {
                    oldestSinceMs = exactLong(since, "oldest verifyingSinceMs");
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("oldestSinceMs", oldestSinceMs);
            return out;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("verifying stats query failed for '"
                    + target + "'", e);
        }
    }

    @Override
    public LineageJournalRowV2 findV2ByRecordId(String recordId) {
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return null;
        }
        return decodeV2Strict(raw);
    }

    @Override
    public List<String> findV2NonTerminalRepositoryIds(String target) {
        ensureDatabase();
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("v2_non_terminal_by_target_repo")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .group(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException(
                        "v2_non_terminal_by_target_repo returned no result");
            }
            List<String> repos = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                Object key = row.getKey();
                if (key instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String repo) {
                    repos.add(repo);
                }
            }
            return repos;
        } catch (SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SequencingStorageException(
                    "v2_non_terminal_by_target_repo query failed for '" + target + "'", e);
        }
    }


    // ==================================================================
    // LineageV2ReplayStore — §8-d (D-rest-3). Deployed dual and inert like the rest of the
    // D-rest surface: nothing calls these in production until activation.
    // ==================================================================

    @Override
    public ReplayGrant requestReplay(String recordId, String target) {
        if (recordId == null || recordId.isBlank() || target == null) {
            throw new IllegalArgumentException("recordId and target are required");
        }
        String canonicalTarget = target.trim();
        if (canonicalTarget.isBlank()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target must not be blank");
        }
        // Null-safe for the direct-client test construction; production always injects.
        if (lineageConfig != null && !lineageConfig.getTargets().contains(canonicalTarget)) {
            // An unconfigured target would create a compensation nothing can ever claim, and
            // readiness never verified its sink.
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is not currently configured (lineage.targets)");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            throw new LineageV2ReplayStore.ReplayRefusedException("row '" + recordId
                    + "' does not exist");
        }
        if (!"lineage_event_v2".equals(raw.get("type"))) {
            throw new LineageV2ReplayStore.ReplayRefusedException("row '" + recordId
                    + "' is not a v2 row");
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        if (row.state() != LineageJournalRowV2.SequencingState.SEQUENCED) {
            throw new LineageV2ReplayStore.ReplayRefusedException(
                    "only a SEQUENCED row can be replayed — this row is " + row.state());
        }
        LineageTargetLifecycle lifecycle = row.targetLifecycles().get(canonicalTarget);
        if (lifecycle == null) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is not owed by this row — replay cannot invent a delivery");
        }
        if (lifecycle.hasLiveClaim()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' holds a live " + lifecycle.status()
                    + " claim — replay must not steal a token-fenced claim");
        }
        if (!lifecycle.status().isTerminal()) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' is " + lifecycle.status() + " — only a terminal delivery is"
                    + " replayable (the live machine owns everything else)");
        }
        LineageReplayRequest existing = row.replayRequests().get(canonicalTarget);
        long generation;
        if (existing == null) {
            generation = 1L;
        } else if (existing.state() == LineageReplayRequest.State.ACKED) {
            generation = Math.addExact(existing.generation(), 1L);
        } else if (existing.state() == LineageReplayRequest.State.FAILED) {
            throw new LineageV2ReplayStore.ReplayRefusedException("target '" + canonicalTarget
                    + "' has a durable FAILED replay request (generation "
                    + existing.generation() + ": " + existing.reason().reason()
                    + ") — it blocks new requests pending an audited repair");
        } else {
            throw new LineageV2ReplayStore.ReplayRefusedException("a replay request is"
                    + " already in progress for target '" + canonicalTarget + "' (state "
                    + existing.state() + ", generation " + existing.generation() + ")");
        }
        String requestId = java.util.UUID.randomUUID().toString();
        long nowMs = Instant.now().toEpochMilli();
        CouchLineageJournalRowV2.applyReplayRequested(raw, canonicalTarget, generation,
                requestId, nowMs);
        if (!updateStrictCas(raw)) {
            return null; // lost the race — the winner's request is in progress
        }
        if (lineageMetrics != null) {
            lineageMetrics.recordReplayRequested(canonicalTarget);
        }
        return new ReplayGrant(recordId, canonicalTarget, generation, requestId);
    }

    @Override
    public boolean advanceReplay(String recordId, String target, String requestId,
            LineageReplayRequest.State expected, LineageReplayRequest.State next) {
        boolean allowed = (expected == LineageReplayRequest.State.REQUESTED
                && next == LineageReplayRequest.State.CREATED)
                || (expected == LineageReplayRequest.State.CREATED
                        && next == LineageReplayRequest.State.ACKED);
        if (!allowed) {
            throw new IllegalArgumentException("replay transition " + expected + "->" + next
                    + " is not in the §8-d table — caller bug, not a race");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId fence is required");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        LineageReplayRequest current = row.replayRequests().get(target);
        if (current == null || current.state() != expected
                || !requestId.equals(current.requestId())) {
            return false;
        }
        CouchLineageJournalRowV2.applyReplayState(raw, target, next,
                Instant.now().toEpochMilli(), null);
        return updateStrictCas(raw);
    }

    @Override
    public boolean failReplay(String recordId, String target, String requestId,
            LineageTargetLifecycle.TerminalReason reason) {
        if (requestId == null || requestId.isBlank() || reason == null) {
            throw new IllegalArgumentException("requestId fence and reason are required");
        }
        ensureDatabase();
        Map<String, Object> raw = readV2RawStrict(recordId);
        if (raw == null) {
            return false;
        }
        LineageJournalRowV2 row = decodeV2Strict(raw);
        LineageReplayRequest current = row.replayRequests().get(target);
        if (current == null || !current.isUnacked()
                || !requestId.equals(current.requestId())) {
            return false;
        }
        CouchLineageJournalRowV2.applyReplayState(raw, target,
                LineageReplayRequest.State.FAILED, Instant.now().toEpochMilli(), reason);
        boolean persisted = updateStrictCas(raw);
        if (persisted && lineageMetrics != null) {
            lineageMetrics.recordReplayFailed(target);
        }
        return persisted;
    }

    @Override
    public List<ReplayRecovery> findUnackedReplayRequests(int limit) {
        ensureDatabase();
        List<ReplayRecovery> out = new ArrayList<>();
        // (documentId, target, requestId, generation) — one row with two active targets is
        // two recovery items, and a superseded request is a different item.
        java.util.Set<String> examined = new java.util.HashSet<>();
        Object pageStartKey = null;
        String pageStartDocId = null;
        int pageSize = Math.min(Math.max(limit, 1), 100);
        while (out.size() < limit) {
            ViewResult result;
            try {
                var builder = new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                        .db(getLineageClient().getDatabaseName())
                        .ddoc(DESIGN_DOC)
                        .view("v2_replay_requests_unacked")
                        .reduce(false)
                        .limit((long) pageSize);
                if (pageStartKey != null) {
                    // EXCLUSIVE continuation (F3): this scan is read-only, so the anchor row
                    // is still present on the next page — skip(1) steps past it and a corrupt
                    // first row can never pin a page even at limit 1.
                    builder.startKey(pageStartKey).skip(1L);
                }
                if (pageStartDocId != null) {
                    builder.startKeyDocId(pageStartDocId);
                }
                result = getLineageClient().getClient().postView(builder.build())
                        .execute().getResult();
            } catch (RuntimeException e) {
                throw new SequencingStorageException(
                        "v2_replay_requests_unacked query failed", e);
            }
            if (result == null || result.getRows() == null) {
                throw new SequencingStorageException(
                        "v2_replay_requests_unacked returned no result", null);
            }
            List<ViewResultRow> rows = result.getRows();
            if (rows.isEmpty()) {
                return out;
            }
            for (ViewResultRow viewRow : rows) {
                if (out.size() >= limit) {
                    return out;
                }
                String docId = viewRow.getId();
                Object key = viewRow.getKey();
                String targetHint = key instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String t ? t : null;
                if (targetHint == null) {
                    logger.error("Replay scan skipping malformed view key {} on {}", key,
                            docId);
                    continue;
                }
                try {
                    Map<String, Object> raw = readRawStrict(docId);
                    if (raw == null) {
                        continue; // purged/changed under us — the view is a hint
                    }
                    LineageJournalRowV2 row = decodeV2Strict(raw);
                    LineageReplayRequest request = row.replayRequests().get(targetHint);
                    if (request == null || !request.isUnacked()) {
                        continue; // stale hint
                    }
                    String identity = docId + "|" + targetHint + "|" + request.requestId()
                            + "|" + request.generation();
                    if (!examined.add(identity)) {
                        continue;
                    }
                    String recordId = row.event().deliveryId();
                    out.add(new ReplayRecovery(recordId, targetHint, request));
                } catch (SequencingStorageException e) {
                    if (e.getMessage() != null
                            && e.getMessage().startsWith("undecodable v2 row")) {
                        logger.error("Replay scan skipping corrupt v2 row {}: {}", docId,
                                e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
            if (rows.size() < pageSize) {
                return out;
            }
            ViewResultRow last = rows.get(rows.size() - 1);
            pageStartKey = last.getKey();
            pageStartDocId = last.getId();
        }
        return out;
    }

    @Override
    public List<LineageJournalRow> findV1ByRepositoryAndSequenceRangeStrict(
            String repositoryId, long fromSequence, int limit) {
        ensureDatabase();
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("by_repository_and_sequence")
                            .startKey(List.of(repositoryId, Math.addExact(fromSequence, 1)))
                            .endKey(List.of(repositoryId, new HashMap<>()))
                            .includeDocs(true)
                            .reduce(false)
                            .limit((long) limit)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                // Empty is a result with zero rows; the ABSENCE of a result is an abnormal
                // answer — the merge window must halt, never read it as full coverage.
                throw new IllegalStateException(
                        "by_repository_and_sequence returned no result");
            }
            List<LineageJournalRow> rows = new ArrayList<>();
            for (ViewResultRow viewRow : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = viewRow.getDoc();
                if (doc == null) {
                    throw new IllegalStateException("view row without a document");
                }
                Map<String, Object> props = new HashMap<>();
                if (doc.getId() != null) props.put("_id", doc.getId());
                if (doc.getRev() != null) props.put("_rev", doc.getRev());
                if (doc.getProperties() != null) props.putAll(doc.getProperties());
                rows.add(LineageEventCodec.decodeRow(props));
            }
            return rows;
        } catch (SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SequencingStorageException(
                    "strict v1 merge fetch failed for '" + repositoryId + "'", e);
        }
    }

    /** F4: the unacked gauge — the recovery view's _count reduce, for diagnostics. */
    public long countUnackedReplayRequests() {
        ensureDatabase();
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("v2_replay_requests_unacked")
                            .reduce(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                return 0;
            }
            Object value = result.getRows().get(0).getValue();
            return value instanceof Number n ? exactLong(n, "unacked replay count") : 0;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("unacked replay count query failed", e);
        }
    }


    // ==================================================================
    // LineageMaterializationStore — v2.3.18 ⑦ (D-rest-4). Deployed dual and inert: nothing
    // resolves a write version before 4a, so nothing creates decisions in production.
    // ==================================================================

    private static final String DECISION_TYPE = "lineage_materialization";

    @Override
    public LineageMaterializationDecision createDecisionIfAbsent(
            LineageMaterializationDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        ensureDatabase();
        Map<String, Object> doc = decisionToRaw(decision);
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(doc);
            withoutMeta.remove("_id");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId(decision.documentId());
            getLineageClient().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId(decision.documentId())
                            .document(sdkDoc)
                            .build())
                    .execute();
            return decision;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            LineageMaterializationDecision stored =
                    readDecision(decision.spoolRecordId());
            if (stored == null) {
                throw new SequencingStorageException("decision occupant for '"
                        + decision.spoolRecordId() + "' vanished after 409", null);
            }
            if (!stored.factPayloadDigest().equals(decision.factPayloadDigest())
                    || !stored.materializationPlanDigest()
                            .equals(decision.materializationPlanDigest())) {
                throw new LineageIntegrityException(decision.documentId(),
                        stored.materializationPlanDigest(),
                        "decision occupant disagrees — a different fact or plan already"
                                + " committed under this spoolRecordId");
            }
            // Benign collision: the STORED decision's allocations are the frozen truth.
            if (lineageMetrics != null) {
                lineageMetrics.recordDecisionCollision();
            }
            return stored;
        } catch (RuntimeException e) {
            throw new SequencingStorageException("decision create failed for '"
                    + decision.spoolRecordId() + "'", e);
        }
    }

    @Override
    public LineageMaterializationDecision readDecision(String spoolRecordId) {
        if (spoolRecordId == null || spoolRecordId.isBlank()) {
            throw new IllegalArgumentException("spoolRecordId must not be blank");
        }
        ensureDatabase();
        Map<String, Object> raw = readRawStrict("lineage_materialization:" + spoolRecordId);
        if (raw == null) {
            return null;
        }
        try {
            return decisionFromRaw(raw);
        } catch (RuntimeException e) {
            throw new SequencingStorageException("undecodable materialization decision '"
                    + spoolRecordId + "': " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> decisionToRaw(LineageMaterializationDecision d) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", d.documentId());
        doc.put("type", DECISION_TYPE);
        doc.put("spoolRecordId", d.spoolRecordId());
        doc.put("factPayloadDigest", d.factPayloadDigest());
        doc.put("materializeSchemaVersion", (long) d.materializeSchemaVersion());
        doc.put("barrierGeneration", d.barrierGeneration());
        doc.put("allocatedEventId", d.allocatedEventId());
        doc.put("planEntries", d.planEntries().stream()
                .map(LineageMaterializationDecision.PlanEntry::asRecord).toList());
        doc.put("materializationPlanDigest", d.materializationPlanDigest());
        doc.put("createdAtMs", d.createdAtMs());
        // v2.3.22: absent planDigestVersion means V2 (historical decisions), so only V3
        // decisions write the version and its chunk-aware fields.
        if (d.planDigestVersion() == 3) {
            doc.put("planDigestVersion", 3L);
            doc.put("partitionVersion", d.partitionVersion());
            doc.put("chunkLimits", java.util.Map.of(
                    "maxEndpointsPerEvent", d.chunkLimits().maxEndpointsPerEvent(),
                    "maxPayloadBytes", d.chunkLimits().maxPayloadBytes()));
            Map<String, Object> classification = new LinkedHashMap<>();
            d.creationClassification().forEach((target, c) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("status", c.status().name());
                Map<String, Object> reason = new LinkedHashMap<>();
                reason.put("reason", c.reason().reason());
                reason.put("detail", c.reason().detail());
                reason.put("atMs", c.reason().atMs());
                entry.put("reason", reason);
                classification.put(target, entry);
            });
            doc.put("creationClassification", classification);
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static LineageMaterializationDecision decisionFromRaw(Map<String, Object> raw) {
        if (!DECISION_TYPE.equals(raw.get("type"))) {
            throw new IllegalArgumentException("not a materialization decision: type="
                    + raw.get("type"));
        }
        String spoolRecordId = requireString(raw, "spoolRecordId");
        String expectedId = "lineage_materialization:" + spoolRecordId;
        if (!expectedId.equals(raw.get("_id"))) {
            throw new IllegalArgumentException("decision _id '" + raw.get("_id")
                    + "' does not match its spoolRecordId");
        }
        long schemaLong = exactLong(raw.get("materializeSchemaVersion"),
                "materializeSchemaVersion");
        if (schemaLong != 1L && schemaLong != 2L) {
            // Validated BEFORE narrowing: 4294967297 must never masquerade as schema 1.
            throw new IllegalArgumentException("materializeSchemaVersion must be 1 or 2, got "
                    + schemaLong);
        }
        int schemaVersion = (int) schemaLong;
        Object entriesValue = raw.get("planEntries");
        if (!(entriesValue instanceof List<?> entryList) || entryList.isEmpty()) {
            throw new IllegalArgumentException("planEntries must be a non-empty list");
        }
        List<LineageMaterializationDecision.PlanEntry> entries = new ArrayList<>();
        for (Object entryValue : entryList) {
            if (!(entryValue instanceof Map)) {
                throw new IllegalArgumentException("plan entry must be a map");
            }
            Map<String, Object> e = (Map<String, Object>) entryValue;
            if (schemaVersion == 1) {
                entries.add(new LineageMaterializationDecision.V1Entry(
                        requireString(e, "eventId"), requireString(e, "v1EventDigest")));
            } else {
                entries.add(new LineageMaterializationDecision.V2Entry(
                        exactLong(e.get("chunkIndex"), "chunkIndex"),
                        requireString(e, "deliveryId"), requireString(e, "eventDigest")));
            }
        }
        // Version dispatch (v2.3.22 B5/C1): absent means V2 — historical decisions decode
        // exactly as they always did, multi-entry shapes included.
        long planDigestVersion = raw.containsKey("planDigestVersion")
                ? exactLong(raw.get("planDigestVersion"), "planDigestVersion") : 2L;
        if (planDigestVersion != 2L && planDigestVersion != 3L) {
            throw new IllegalArgumentException("unknown planDigestVersion "
                    + planDigestVersion + " — this build cannot act on it");
        }
        Long partitionVersion = null;
        LineageChunkPlanner.ChunkLimits chunkLimits = null;
        Map<String, LineageMaterializationDecision.CreationClassification> classification =
                new LinkedHashMap<>();
        if (planDigestVersion == 2L) {
            // V3-only fields under V2 are a malformed document, not fields to ignore (F4).
            for (String forbidden : List.of("partitionVersion", "chunkLimits",
                    "creationClassification")) {
                if (raw.containsKey(forbidden)) {
                    throw new IllegalArgumentException("a V2 decision must not carry '"
                            + forbidden + "'");
                }
            }
        }
        if (planDigestVersion == 3L) {
            partitionVersion = exactLong(raw.get("partitionVersion"), "partitionVersion");
            Object limitsValue = raw.get("chunkLimits");
            if (!(limitsValue instanceof Map)) {
                throw new IllegalArgumentException("a V3 decision requires chunkLimits");
            }
            Map<String, Object> limits = (Map<String, Object>) limitsValue;
            chunkLimits = new LineageChunkPlanner.ChunkLimits(
                    exactLong(limits.get("maxEndpointsPerEvent"), "maxEndpointsPerEvent"),
                    exactLong(limits.get("maxPayloadBytes"), "maxPayloadBytes"));
            Object classificationValue = raw.get("creationClassification");
            if (classificationValue != null) {
                if (!(classificationValue instanceof Map)) {
                    throw new IllegalArgumentException("creationClassification must be a map");
                }
                for (var e : ((Map<String, Object>) classificationValue).entrySet()) {
                    if (!(e.getValue() instanceof Map)) {
                        throw new IllegalArgumentException("classification entry must be a map");
                    }
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    Object reasonValue = entry.get("reason");
                    if (!(reasonValue instanceof Map)) {
                        throw new IllegalArgumentException("classification requires its reason");
                    }
                    Map<String, Object> reason = (Map<String, Object>) reasonValue;
                    classification.put(e.getKey(),
                            new LineageMaterializationDecision.CreationClassification(
                                    LineagePublishStatus.valueOf(requireString(entry, "status")),
                                    new LineageTargetLifecycle.TerminalReason(
                                            requireString(reason, "reason"),
                                            reason.get("detail") instanceof String d ? d : null,
                                            exactLong(reason.get("atMs"), "atMs"))));
                }
            }
        }
        // The typed constructor recomputes the plan digest — tampered content throws here.
        return new LineageMaterializationDecision(spoolRecordId,
                requireString(raw, "factPayloadDigest"), schemaVersion,
                exactLong(raw.get("barrierGeneration"), "barrierGeneration"),
                requireString(raw, "allocatedEventId"), entries,
                requireString(raw, "materializationPlanDigest"),
                exactLong(raw.get("createdAtMs"), "createdAtMs"),
                (int) planDigestVersion, partitionVersion, chunkLimits, classification);
    }

    private static String requireString(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return s;
    }

    @Override
    public MaterializedV1Row readMaterializedV1RowStrict(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        ensureDatabase();
        Map<String, Object> raw = readRawStrict("lineage:" + eventId);
        if (raw == null) {
            return null;
        }
        try {
            return new MaterializedV1Row(strictV1Event(raw, eventId),
                    raw.get("_rev") instanceof String r ? r : null);
        } catch (RuntimeException e) {
            throw new SequencingStorageException("undecodable materialized v1 row 'lineage:"
                    + eventId + "': " + e.getMessage(), e);
        }
    }

    /**
     * The strict v1 materialization decoder (v2.3.21 B4): the exact writer shape, no
     * defaulting. Absence of the two writer-omitted-when-empty maps decodes as canonical
     * empty; present-but-wrong-type is refused.
     */
    @SuppressWarnings("unchecked")
    private static LineageEvent strictV1Event(Map<String, Object> raw, String eventId) {
        if (!"lineage_event".equals(raw.get("type"))) {
            throw new IllegalArgumentException("type must be lineage_event, got "
                    + raw.get("type"));
        }
        long schemaVersion = exactLong(raw.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1, got " + schemaVersion);
        }
        String storedEventId = requireString(raw, "eventId");
        if (!storedEventId.equals(eventId) || !("lineage:" + eventId).equals(raw.get("_id"))) {
            throw new IllegalArgumentException("row identity disagrees with its _id");
        }
        LineageProcessType processType;
        try {
            processType = LineageProcessType.valueOf(requireString(raw, "processType"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown processType " + raw.get("processType"));
        }
        List<String> inputs = strictStringList(raw.get("inputs"), "inputs");
        List<String> outputs = strictStringList(raw.get("outputs"), "outputs");
        Map<String, String> snapshot;
        Object sa = raw.get("snapshotAttributes");
        if (sa == null && !raw.containsKey("snapshotAttributes")) {
            snapshot = Map.of(); // writer omits when empty — absence IS canonical empty
        } else if (sa instanceof Map) {
            snapshot = new LinkedHashMap<>();
            for (var e : ((Map<Object, Object>) sa).entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v)) {
                    throw new IllegalArgumentException("snapshotAttributes must map strings"
                            + " to strings");
                }
                ((LinkedHashMap<String, String>) snapshot).put(k, v);
            }
        } else {
            throw new IllegalArgumentException("snapshotAttributes present but not a map");
        }
        Map<String, LineagePublishStatus> statuses;
        Object ps = raw.get("publishStatusByTarget");
        if (ps == null && !raw.containsKey("publishStatusByTarget")) {
            statuses = Map.of();
        } else if (ps instanceof Map) {
            statuses = new LinkedHashMap<>();
            for (var e : ((Map<Object, Object>) ps).entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String v)) {
                    throw new IllegalArgumentException("publishStatusByTarget must map"
                            + " strings to status names");
                }
                ((LinkedHashMap<String, LineagePublishStatus>) statuses)
                        .put(k, LineagePublishStatus.valueOf(v));
            }
        } else {
            throw new IllegalArgumentException("publishStatusByTarget present but not a map");
        }
        long sequence = exactLong(raw.get("sequenceNumber"), "sequenceNumber");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0");
        }
        return new LineageEvent((int) schemaVersion, storedEventId,
                requireString(raw, "eventKey"), sequence, requireString(raw, "occurredAt"),
                requireString(raw, "repositoryId"), processType, inputs, outputs,
                requireStringAllowEmpty(raw, "runId"),
                requireStringAllowEmpty(raw, "correlationId"),
                (int) exactLong(raw.get("version"), "version"), snapshot, statuses);
    }

    private static String requireStringAllowEmpty(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return s;
    }

    private static List<String> strictStringList(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(what + " must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new IllegalArgumentException(what + " must contain only strings");
            }
            out.add(s);
        }
        return out;
    }

    @Override
    public void createMaterializedV1RowIfAbsent(LineageEvent event,
            String expectedV1EventDigest) {
        if (event == null || expectedV1EventDigest == null
                || expectedV1EventDigest.isBlank()) {
            throw new IllegalArgumentException("event and expected digest are required");
        }
        ensureDatabase();
        MaterializedV1Row existing = readMaterializedV1RowStrict(event.eventId());
        if (existing != null) {
            verifyMaterializedV1Digest(existing.event(), expectedV1EventDigest);
            return;
        }
        // The fenced allocator, never the eager v1 helper: counter-required,
        // watermark-checked, fail-closed. A burned number on a lost race is an accepted gap.
        long sequence = allocateSequenceFenced(event.repositoryId());
        LineageEvent sequenced = new LineageEvent(event.schemaVersion(), event.eventId(),
                event.eventKey(), sequence, event.occurredAt(), event.repositoryId(),
                event.processType(), event.inputs(), event.outputs(), event.runId(),
                event.correlationId(), event.version(), event.snapshotAttributes(),
                event.publishStatusByTarget());
        Map<String, Object> doc = new CouchLineageEvent(sequenced).toMap();
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(doc);
            Object id = withoutMeta.remove("_id");
            withoutMeta.remove("_rev");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId((String) id);
            getLineageClient().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId((String) id)
                            .document(sdkDoc)
                            .build())
                    .execute();
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            MaterializedV1Row occupant = readMaterializedV1RowStrict(event.eventId());
            if (occupant == null) {
                throw new SequencingStorageException("v1 row occupant for '"
                        + event.eventId() + "' vanished after 409", null);
            }
            verifyMaterializedV1Digest(occupant.event(), expectedV1EventDigest);
        } catch (RuntimeException e) {
            throw new SequencingStorageException("materialized v1 create failed for '"
                    + event.eventId() + "'", e);
        }
    }

    @Override
    public void appendV2Classified(LineageEventV2 event,
            Map<String, LineageMaterializationDecision.CreationClassification> classification) {
        if (event == null || classification == null || classification.isEmpty()) {
            throw new IllegalArgumentException("event and a non-empty classification are"
                    + " required");
        }
        if (!event.publishStatusByTarget().keySet().equals(classification.keySet())) {
            throw new IllegalArgumentException("the classified targets and the event's status"
                    + " targets must be exactly equal");
        }
        for (var e : classification.entrySet()) {
            if (event.publishStatusByTarget().get(e.getKey()) != e.getValue().status()) {
                throw new IllegalArgumentException("target '" + e.getKey() + "' status"
                        + " disagrees with its classification");
            }
        }
        if (event.sequenceNumber() != 0) {
            throw new IllegalArgumentException("a classified append is still an UNSEQUENCED"
                    + " row: sequences are the fenced sequencer's");
        }
        ensureDatabase();
        Map<String, Object> doc = new LinkedHashMap<>(CouchLineageEventV2.toMap(event));
        doc.put("state", LineageJournalRowV2.SequencingState.UNSEQUENCED.name());
        Map<String, Object> reasons = new LinkedHashMap<>();
        classification.forEach((target, c) -> {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", c.reason().reason());
            reason.put("detail", c.reason().detail());
            reason.put("atMs", c.reason().atMs());
            reasons.put(target, reason);
        });
        doc.put("v2TerminalReasonByTarget", reasons);
        try {
            com.ibm.cloud.cloudant.v1.model.Document sdkDoc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new LinkedHashMap<>(doc);
            Object id = withoutMeta.remove("_id");
            withoutMeta.remove("_rev");
            sdkDoc.setProperties(withoutMeta);
            sdkDoc.setId((String) id);
            getLineageClient().getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .docId((String) id)
                            .document(sdkDoc)
                            .build())
                    .execute();
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            Map<String, Object> occupantRaw =
                    readV2RawStrict(event.deliveryId());
            if (occupantRaw == null) {
                throw new SequencingStorageException("classified occupant for '"
                        + event.deliveryId() + "' vanished after 409", null);
            }
            LineageJournalRowV2 occupant = decodeV2Strict(occupantRaw);
            if (!occupant.event().creationPayloadDigest()
                    .equals(event.creationPayloadDigest())) {
                throw new LineageIntegrityException(
                        CouchLineageEventV2.documentId(event.deliveryId()),
                        occupant.event().creationPayloadDigest(),
                        "classified occupant carries a different payload");
            }
            // The classification is part of what must match: a PENDING occupant at this key
            // is a DIFFERENT decision about the same payload.
            for (var e : classification.entrySet()) {
                LineageTargetLifecycle lifecycle =
                        occupant.targetLifecycles().get(e.getKey());
                boolean same = lifecycle != null
                        && lifecycle.status() == e.getValue().status()
                        && lifecycle.terminalReason() != null
                        && lifecycle.terminalReason().reason()
                                .equals(e.getValue().reason().reason())
                        && lifecycle.terminalReason().detail()
                                .equals(e.getValue().reason().detail())
                        && lifecycle.terminalReason().atMs() == e.getValue().reason().atMs();
                if (!same) {
                    throw new LineageIntegrityException(
                            CouchLineageEventV2.documentId(event.deliveryId()),
                            occupant.event().creationPayloadDigest(),
                            "classified occupant disagrees for target '" + e.getKey() + "'");
                }
            }
        } catch (RuntimeException e) {
            if (isDocumentTooLarge(e)) {
                throw new DocumentTooLargeException("CouchDB refused the document for its"
                        + " size: " + e.getMessage(), e);
            }
            throw new SequencingStorageException("classified append failed for '"
                    + event.deliveryId() + "'", e);
        }
    }

    /**
     * CouchDB's document-size verdict, classified strictly (v2.3.22 D1): 413, or a status
     * whose reason names {@code document_too_large}. Everything else is infrastructure.
     */
    static boolean isDocumentTooLarge(RuntimeException e) {
        // ONLY a response-carrying failure counts, and only 413 or a CouchDB error/reason of
        // document_too_large: a 503 (or any message that merely says "too large") is an
        // infrastructure failure and must propagate, never park a fact (F3).
        if (!(e instanceof com.ibm.cloud.sdk.core.service.exception.ServiceResponseException
                sre)) {
            return false;
        }
        if (sre.getStatusCode() == 413) {
            return true;
        }
        if (sre.getStatusCode() != 400 && sre.getStatusCode() != 500) {
            return false;
        }
        Object reason = sre.getDebuggingInfo() == null ? null
                : sre.getDebuggingInfo().get("reason");
        Object error = sre.getDebuggingInfo() == null ? null
                : sre.getDebuggingInfo().get("error");
        return "document_too_large".equals(reason) || "document_too_large".equals(error);
    }

    @Override
    public List<String> findV2SequencedRepositoryIds(String target) {
        ensureDatabase();
        try {
            ViewResult result = getLineageClient().getClient().postView(
                    new com.ibm.cloud.cloudant.v1.model.PostViewOptions.Builder()
                            .db(getLineageClient().getDatabaseName())
                            .ddoc(DESIGN_DOC)
                            .view("v2_sequenced_repositories")
                            .startKey(List.of(target))
                            .endKey(List.of(target, new HashMap<>()))
                            .reduce(true)
                            .group(true)
                            .build())
                    .execute().getResult();
            if (result == null || result.getRows() == null) {
                throw new IllegalStateException("v2_sequenced_repositories returned no result");
            }
            List<String> repos = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                if (row.getKey() instanceof List<?> parts && parts.size() == 2
                        && parts.get(1) instanceof String repo) {
                    repos.add(repo);
                }
            }
            return repos;
        } catch (SequencingStorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SequencingStorageException(
                    "v2_sequenced_repositories query failed for '" + target + "'", e);
        }
    }

    private static void verifyMaterializedV1Digest(LineageEvent stored, String expected) {
        String recomputed = LineageSpoolIdentity.v1EventDigest(stored.eventId(),
                stored.eventKey(), stored.repositoryId(), stored.processType(),
                stored.inputs(), stored.outputs(), stored.snapshotAttributes(),
                stored.occurredAt(), stored.correlationId());
        if (!recomputed.equals(expected)) {
            throw new LineageIntegrityException("lineage:" + stored.eventId(), recomputed,
                    "materialized v1 occupant disagrees with the frozen plan entry");
        }
    }

}
