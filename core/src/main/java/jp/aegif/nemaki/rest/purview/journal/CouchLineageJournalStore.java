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
public class CouchLineageJournalStore implements LineageJournalStore {

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

        // by_target_status — projector claim and backlog monitoring
        views.put("by_target_status", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.publishStatusByTarget) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { emit([k, t[k]], null); } } } }",
                "_count"));

        // by_process_type — countByProcessType stats and listing
        views.put("by_process_type", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.processType) { emit(doc.processType, null); } }",
                "_count"));

        // by_occurred_at — cross-repository time ordering for findAll
        views.put("by_occurred_at", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + ") { emit(doc.occurredAt, null); } }",
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

        // by_target_status_time — target+status+occurredAt for oldest-first queries
        views.put("by_target_status_time", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.publishStatusByTarget && doc.occurredAt) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { emit([k, t[k], doc.occurredAt], null); } } } }",
                null));

        // by_repository_and_sequence — projection cursor sequence ordering
        views.put("by_repository_and_sequence", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.repositoryId && doc.sequenceNumber) { " +
                        "emit([doc.repositoryId, doc.sequenceNumber], null); } }",
                null));

        // non_terminal_by_target_repo — distinct repos with non-terminal events per target
        views.put("non_terminal_by_target_repo", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.publishStatusByTarget && doc.repositoryId) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { " +
                        "var s = t[k]; if (s === 'PENDING' || s === 'FAILED' || s === 'PROJECTING') { emit([k, doc.repositoryId], null); } } } } }",
                "_count"));

        // projecting_by_claimed_at — PROJECTING events ordered by claimedAt for stale reaping.
        // Key: [target, claimedAt], where claimedAt falls back to occurredAt for pre-upgrade
        // events, so oldest-claimed is always found regardless of the sample window size.
        views.put("projecting_by_claimed_at", new ViewDefinition(
                "function(doc) { if (" + EVENT_TYPES + " && doc.publishStatusByTarget) { " +
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
    public List<LineageEvent> findByRepositoryId(String repositoryId, int limit, int offset) {
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

        return queryEventsFromView("by_repository_and_time", params);
    }

    @Override
    public List<LineageEvent> findByProcessType(String repositoryId, LineageProcessType processType, int limit, int offset) {
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
        return queryEventsFromView("by_repo_process_type_time", params);
    }

    @Override
    public List<LineageEvent> findByProcessType(LineageProcessType processType, int limit, int offset) {
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

        return queryEventsFromView("by_process_type_time", params);
    }

    @Override
    public int updatePublishStatus(String eventId, String target, LineagePublishStatus status) {
        if (!dbProvisioned.get()) {
            return 0;
        }

        try {
            String docId = CouchLineageEvent.ID_PREFIX + eventId;
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
                logger.debug("Event not found for updatePublishStatus: {}", eventId);
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
            logger.debug("Failed to update publish status for event {}: {}", eventId, e.getMessage());
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

        List<LineageEvent> candidates = queryEventsFromView("by_occurred_at", params);

        List<String[]> toDelete = new ArrayList<>(); // [id, rev] pairs
        for (LineageEvent event : candidates) {
            if (allTargetsTerminal(event)) {
                String docId = CouchLineageEvent.ID_PREFIX + event.eventId();
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
                if (doc != null) {
                    toDelete.add(new String[]{(String) doc.get("_id"), (String) doc.get("_rev")});
                }
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
    public int discardEvent(String eventId, String target) {
        if (!dbProvisioned.get()) {
            return 0;
        }

        try {
            String docId = CouchLineageEvent.ID_PREFIX + eventId;
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
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
            logger.debug("Failed to discard event {}: {}", eventId, e.getMessage());
            return 0;
        }
    }

    @Override
    public long countNonTerminalByTarget(String target) {
        if (!ensureClientForRead()) {
            return 0;
        }

        long count = 0;
        for (LineagePublishStatus status : LineagePublishStatus.values()) {
            if (!status.isTerminal()) {
                count += queryTargetStatusCount(target, status.name());
            }
        }
        return count;
    }


    @Override
    public List<LineageEvent> findAll(int limit, int offset) {
        if (!ensureClientForRead()) {
            return List.of();
        }

        int cappedLimit = Math.min(Math.max(limit, 1), 200);

        // Use by_occurred_at (single-key view on occurredAt) for correct
        // cross-repository time ordering. descending=true → newest first.
        Map<String, Object> params = new HashMap<>();
        params.put("limit", cappedLimit);
        if (offset > 0) {
            params.put("skip", offset);
        }
        params.put("include_docs", true);
        params.put("descending", true);

        return queryEventsFromView("by_occurred_at", params);
    }

    @Override
    public LineageEvent findByEventId(String eventId) {
        if (!ensureClientForRead() || eventId == null || eventId.isEmpty()) {
            return null;
        }

        try {
            String docId = CouchLineageEvent.ID_PREFIX + eventId;
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) {
                return null;
            }
            CouchLineageEvent couchEvent = new CouchLineageEvent(doc);
            return couchEvent.toLineageEvent();
        } catch (Exception e) {
            logger.debug("Error finding lineage event by id {}: {}", eventId, e.getMessage());
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
    public List<LineageEvent> findByTargetAndStatus(String target, LineagePublishStatus status, int limit) {
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
        return queryEventsFromView("by_target_status", params);
    }

    @Override
    public List<LineageEvent> findByTargetAndStatusOldestFirst(String target, LineagePublishStatus status, int limit) {
        if (!ensureClientForRead()) {
            return List.of();
        }
        Map<String, Object> params = new HashMap<>();
        // by_target_status_time key: [target, status, occurredAt] — ascending = oldest first
        params.put("startkey", List.of(target, status.name(), ""));
        params.put("endkey", List.of(target, status.name(), "\ufff0"));
        params.put("limit", limit);
        params.put("include_docs", true);
        return queryEventsFromView("by_target_status_time", params);
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

            List<LineageEvent> staleProjecting = queryEventsFromView("projecting_by_claimed_at", params);
            if (staleProjecting.isEmpty()) break;

            int batchReaped = 0;
            for (LineageEvent event : staleProjecting) {
                try {
                    int reset = updatePublishStatus(event.eventId(), target, LineagePublishStatus.FAILED);
                    if (reset > 0) {
                        logger.info("Reset stale PROJECTING event to FAILED: eventKey={}, target={}",
                                event.eventKey(), target);
                        batchReaped++;
                    }
                } catch (Exception e) {
                    logger.debug("Error resetting stale event {}: {}", event.eventId(), e.getMessage());
                }
            }
            totalReaped += batchReaped;

            // Stop iterating when no progress was made in this batch
            if (batchReaped == 0) break;
        }
        return totalReaped;
    }

    @Override
    public int getRetryCount(String eventId, String target) {
        if (!ensureClientForRead()) {
            return 0;
        }
        try {
            String docId = CouchLineageEvent.ID_PREFIX + eventId;
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) getLineageClient().get(Map.class, docId, null);
            if (doc == null) return 0;

            @SuppressWarnings("unchecked")
            Map<String, Object> retryCounts = (Map<String, Object>) doc.get("retryCountByTarget");
            if (retryCounts == null) return 0;

            Object count = retryCounts.get(target);
            return (count instanceof Number) ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            logger.debug("Error reading retry count for event {}: {}", eventId, e.getMessage());
            return 0;
        }
    }



    @Override
    public List<LineageEvent> findByDateRange(String start, String end, int limit, int offset) {
        if (!ensureClientForRead()) return List.of();
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return queryEventsFromView("by_occurred_at", start, end, false, cappedLimit, offset);
    }

    @Override
    public List<LineageEvent> findByRepositoryAndSequenceRange(String repositoryId, long fromSequence, int limit) {
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
        return queryEventsFromView("by_repository_and_sequence", params);
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
     * Query events from a view and convert rows to LineageEvent list.
     *
     * <p>Note: Cloudant SDK's {@code Document} does NOT implement
     * {@code Map<String, Object>}. We must extract properties via
     * {@code Document.getProperties()} and {@code Document.getId()}/{@code getRev()}.
     */
    private List<LineageEvent> queryEventsFromView(String viewName, Map<String, Object> params) {
        try {
            ViewResult result = getLineageClient().queryView(DESIGN_DOC, viewName, params);
            if (result == null || result.getRows() == null) {
                return List.of();
            }

            List<LineageEvent> events = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                if (doc != null) {
                    Map<String, Object> props = new HashMap<>();
                    if (doc.getId() != null) props.put("_id", doc.getId());
                    if (doc.getRev() != null) props.put("_rev", doc.getRev());
                    if (doc.getProperties() != null) props.putAll(doc.getProperties());
                    CouchLineageEvent couchEvent = new CouchLineageEvent(props);
                    events.add(couchEvent.toLineageEvent());
                }
            }
            return events;
        } catch (Exception e) {
            logger.error("Error querying lineage view {}: {}", viewName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Convenience overload for range queries on single-key views.
     */
    private List<LineageEvent> queryEventsFromView(String viewName, String startKey, String endKey,
                                                    boolean descending, int limit, int offset) {
        Map<String, Object> params = new HashMap<>();
        if (startKey != null) params.put(descending ? "endkey" : "startkey", startKey);
        if (endKey != null) params.put(descending ? "startkey" : "endkey", endKey);
        params.put("descending", descending);
        params.put("limit", limit);
        if (offset > 0) params.put("skip", offset);
        params.put("include_docs", true);
        return queryEventsFromView(viewName, params);
    }

    /**
     * Check if all targets in the event are in terminal state.
     */
    private boolean allTargetsTerminal(LineageEvent event) {
        if (event.publishStatusByTarget().isEmpty()) {
            return true;
        }
        return event.publishStatusByTarget().values().stream()
                .allMatch(LineagePublishStatus::isTerminal);
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
}
