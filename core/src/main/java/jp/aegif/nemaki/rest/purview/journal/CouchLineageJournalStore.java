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

    private CloudantClientWrapper getLineageClient() {
        if (lineageClient == null) {
            ensureDatabase();
        }
        return lineageClient;
    }

    /**
     * Deploy CouchDB views for the lineage design document.
     */
    private void deployViews() {
        CloudantClientWrapper client = lineageClient;

        // View 1: by_event_key — idempotency check
        client.createOrUpdateView(DESIGN_DOC, "by_event_key",
                "function(doc) { if (doc.type === 'lineage_event' && doc.eventKey) { emit(doc.eventKey, null); } }",
                null);

        // View 2: by_repository_and_time — time-range queries and purge
        client.createOrUpdateView(DESIGN_DOC, "by_repository_and_time",
                "function(doc) { if (doc.type === 'lineage_event') { emit([doc.repositoryId, doc.occurredAt], null); } }",
                null);

        // View 3: by_target_status — projector claim and backlog monitoring
        client.createOrUpdateView(DESIGN_DOC, "by_target_status",
                "function(doc) { if (doc.type === 'lineage_event' && doc.publishStatusByTarget) { " +
                        "var t = doc.publishStatusByTarget; for (var k in t) { if (t.hasOwnProperty(k)) { emit([k, t[k]], null); } } } }",
                "_count");

        // View 4: by_process_type — countByProcessType stats
        client.createOrUpdateView(DESIGN_DOC, "by_process_type",
                "function(doc) { if (doc.type === 'lineage_event' && doc.processType) { emit(doc.processType, null); } }",
                "_count");

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
    public List<LineageEvent> findByRepositoryId(String repositoryId, int limit) {
        if (!dbProvisioned.get()) {
            return List.of();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of(repositoryId, ""));
        params.put("endkey", List.of(repositoryId, "\ufff0"));
        params.put("limit", limit);
        params.put("include_docs", true);

        return queryEventsFromView("by_repository_and_time", params);
    }

    @Override
    public List<LineageEvent> findByProcessType(String repositoryId, LineageProcessType processType, int limit) {
        // Use by_repository_and_time view and filter in memory (no dedicated view for processType)
        List<LineageEvent> all = findByRepositoryId(repositoryId, limit * 3);
        return all.stream()
                .filter(e -> e.processType() == processType)
                .limit(limit)
                .toList();
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

        // Query all events before cutoff time, across all repositories
        // Use startkey ["", ""] and endkey ["\ufff0", cutoffStr]
        // Actually, to be safe, iterate known repository prefixes or use a broad range.
        // Since by_repository_and_time key is [repositoryId, occurredAt],
        // we query with a broad startkey and endkey that covers all repos.
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of("", ""));
        params.put("endkey", List.of("\ufff0", cutoffStr));
        params.put("limit", PURGE_BATCH_SIZE);
        params.put("include_docs", true);

        List<LineageEvent> candidates = queryEventsFromView("by_repository_and_time", params);

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
        if (!dbProvisioned.get()) {
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
        if (!dbProvisioned.get()) {
            return List.of();
        }

        int cappedLimit = Math.min(Math.max(limit, 1), 200);

        // descending=true reverses the key order: startkey must be high, endkey must be low
        Map<String, Object> params = new HashMap<>();
        params.put("startkey", List.of("\ufff0", "\ufff0"));
        params.put("endkey", List.of("", ""));
        params.put("limit", cappedLimit);
        if (offset > 0) {
            params.put("skip", offset);
        }
        params.put("include_docs", true);
        params.put("descending", true);

        return queryEventsFromView("by_repository_and_time", params);
    }

    @Override
    public LineageEvent findByEventId(String eventId) {
        if (!dbProvisioned.get() || eventId == null || eventId.isEmpty()) {
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
        if (!dbProvisioned.get()) {
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
    public boolean isActive() {
        if (lineageConfig.getMode() != LineageMode.JOURNALED) {
            return false;
        }
        if (!dbProvisioned.get()) {
            // Try to check if DB exists without full provisioning
            try {
                CloudantClientWrapper anyClient = connectorPool.getClient("nemaki_conf");
                Cloudant cloudant = anyClient.getClient();
                GetDatabaseInformationOptions opts = new GetDatabaseInformationOptions.Builder()
                        .db(DB_NAME).build();
                cloudant.getDatabaseInformation(opts).execute();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
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
