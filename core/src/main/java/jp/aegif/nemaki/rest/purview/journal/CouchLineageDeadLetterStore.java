package jp.aegif.nemaki.rest.purview.journal;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * CouchDB implementation of {@link LineageDeadLetterStore}.
 *
 * <p>Stores dead-letter events in the same {@code nemaki_lineage} database
 * with {@code type=lineage_dead_letter} documents.
 */
@Component
public class CouchLineageDeadLetterStore implements LineageDeadLetterStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchLineageDeadLetterStore.class);
    private static final String DESIGN_DOC = "lineage";

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired
    private LineageConfig lineageConfig;

    private CloudantClientWrapper getClient() {
        if (journalStore instanceof CouchLineageJournalStore couchStore && couchStore.isActive()) {
            return couchStore.getLineageClient();
        }
        return null;
    }

    @Override
    public void record(LineageEvent event, String reason) {
        CloudantClientWrapper client = getClient();
        if (client == null) return;

        try {
            String docId = "lineage_dl:" + event.eventId();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", docId);
            doc.put("type", "lineage_dead_letter");
            doc.put("eventId", event.eventId());
            doc.put("eventKey", event.eventKey());
            doc.put("repositoryId", event.repositoryId());
            doc.put("processType", event.processType() != null ? event.processType().name() : null);
            doc.put("occurredAt", event.occurredAt());
            doc.put("inputs", event.inputs());
            doc.put("outputs", event.outputs());
            doc.put("snapshotAttributes", event.snapshotAttributes());
            doc.put("reason", reason);
            doc.put("recordedAt", Instant.now().toString());
            doc.put("replayed", false);

            if (!client.exists(docId)) {
                client.create(doc);
                logger.debug("Dead-letter recorded: eventId={}, reason={}", event.eventId(), reason);
            }
        } catch (Exception e) {
            logger.warn("Failed to persist dead-letter for event {}: {}", event.eventId(), e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findAll(int limit, int offset, Boolean replayed) {
        CloudantClientWrapper client = getClient();
        if (client == null) return List.of();

        try {
            int cappedLimit = Math.min(Math.max(limit, 1), 200);
            Map<String, Object> params = new HashMap<>();
            params.put("limit", cappedLimit);
            if (offset > 0) params.put("skip", offset);
            params.put("include_docs", true);
            params.put("reduce", false);

            String viewName;
            if (replayed != null) {
                viewName = "dead_letter_by_replayed";
                params.put("startkey", List.of(replayed, ""));
                params.put("endkey", List.of(replayed, "\ufff0"));
            } else {
                viewName = "dead_letter_by_time";
                params.put("descending", true);
            }

            ViewResult result = client.queryView(DESIGN_DOC, viewName, params);
            if (result == null || result.getRows() == null) return List.of();

            List<Map<String, Object>> results = new ArrayList<>();
            for (ViewResultRow row : result.getRows()) {
                com.ibm.cloud.cloudant.v1.model.Document sdkDoc = row.getDoc();
                if (sdkDoc != null) {
                    Map<String, Object> doc = new HashMap<>();
                    if (sdkDoc.getId() != null) doc.put("_id", sdkDoc.getId());
                    if (sdkDoc.getProperties() != null) doc.putAll(sdkDoc.getProperties());
                    results.add(doc);
                }
            }
            return results;
        } catch (Exception e) {
            logger.warn("Failed to query dead-letter events: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> findByEventId(String eventId) {
        CloudantClientWrapper client = getClient();
        if (client == null) return null;

        try {
            Map<String, Object> doc = client.get(Map.class, "lineage_dl:" + eventId, null);
            if (doc != null) {
                doc.remove("_rev");
                logger.debug("Dead-letter found: eventId={}", eventId);
            } else {
                logger.debug("Dead-letter not found: eventId={}", eventId);
            }
            return doc;
        } catch (Exception e) {
            logger.debug("Failed to retrieve dead-letter event {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean replay(String eventId, LineageJournalStore targetStore) {
        CloudantClientWrapper client = getClient();
        if (client == null) return false;

        try {
            Map<String, Object> doc = client.get(Map.class, "lineage_dl:" + eventId, null);
            if (doc == null) return false;

            Boolean alreadyReplayed = (Boolean) doc.get("replayed");
            if (Boolean.TRUE.equals(alreadyReplayed)) return false;

            // Reconstruct LineageEvent
            LineageEvent event = reconstructEvent(doc);
            if (event == null) return false;

            // Re-append to journal (idempotent by eventKey)
            targetStore.append(event);

            // Mark as replayed
            doc.put("replayed", true);
            doc.put("replayedAt", Instant.now().toString());
            client.update(doc);

            logger.info("Replayed dead-letter event: eventId={}", eventId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to replay dead-letter event {}: {}", eventId, e.getMessage());
            return false;
        }
    }

    @Override
    public int replayAll(LineageJournalStore targetStore) {
        int totalReplayed = 0;
        int maxIterations = 10; // safeguard: max 1000 total (10 × 100 batch)
        for (int i = 0; i < maxIterations; i++) {
            List<Map<String, Object>> unreplayed = findAll(100, 0, false);
            if (unreplayed.isEmpty()) break;
            int batchReplayed = 0;
            for (Map<String, Object> dlRecord : unreplayed) {
                String eventId = (String) dlRecord.get("eventId");
                if (eventId != null && replay(eventId, targetStore)) {
                    batchReplayed++;
                }
            }
            totalReplayed += batchReplayed;
            if (batchReplayed == 0) break; // no progress — avoid infinite loop
        }
        return totalReplayed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public long count(Boolean replayed) {
        CloudantClientWrapper client = getClient();
        if (client == null) return 0;

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("reduce", true);
            params.put("group", true);

            if (replayed != null) {
                params.put("startkey", List.of(replayed, ""));
                params.put("endkey", List.of(replayed, "\ufff0"));
            }

            ViewResult result = client.queryView(DESIGN_DOC, "dead_letter_by_replayed", params);
            if (result != null && result.getRows() != null) {
                long total = 0;
                for (ViewResultRow row : result.getRows()) {
                    Object val = row.getValue();
                    if (val instanceof Number) total += ((Number) val).longValue();
                }
                return total;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public int purgeReplayed() {
        List<Map<String, Object>> replayed = findAll(1000, 0, true);
        CloudantClientWrapper client = getClient();
        if (client == null) return 0;

        int count = 0;
        for (Map<String, Object> dl : replayed) {
            try {
                String id = (String) dl.get("_id");
                if (id == null) id = "lineage_dl:" + dl.get("eventId");
                // Need to get the _rev for deletion
                Map<String, Object> doc = client.get(Map.class, id, null);
                if (doc != null) {
                    String rev = (String) doc.get("_rev");
                    if (rev != null) {
                        client.delete(id, rev);
                        count++;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to purge dead-letter doc: {}", e.getMessage());
            }
        }
        return count;
    }

    @Override
    public boolean isActive() {
        return getClient() != null;
    }

    @SuppressWarnings("unchecked")
    private LineageEvent reconstructEvent(Map<String, Object> doc) {
        try {
            String processTypeStr = (String) doc.get("processType");
            LineageProcessType processType = null;
            if (processTypeStr != null && !processTypeStr.isEmpty()) {
                try {
                    processType = LineageProcessType.valueOf(processTypeStr);
                } catch (IllegalArgumentException ignored) {}
            }

            List<String> inputs = doc.get("inputs") instanceof List ? (List<String>) doc.get("inputs") : List.of();
            List<String> outputs = doc.get("outputs") instanceof List ? (List<String>) doc.get("outputs") : List.of();

            Map<String, String> snapshotAttrs = Map.of();
            Object snapshotObj = doc.get("snapshotAttributes");
            if (snapshotObj instanceof Map) {
                Map<String, Object> rawMap = (Map<String, Object>) snapshotObj;
                Map<String, String> stringMap = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                    stringMap.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                }
                snapshotAttrs = stringMap;
            }

            return new LineageEvent(
                    1, // schemaVersion
                    (String) doc.get("eventId"),
                    (String) doc.get("eventKey"),
                    0, // sequenceNumber — will be reassigned on append
                    (String) doc.get("occurredAt"),
                    (String) doc.get("repositoryId"),
                    processType,
                    inputs,
                    outputs,
                    "", // runId
                    "", // correlationId
                    1,  // version
                    snapshotAttrs,
                    Map.of() // publishStatusByTarget
            );
        } catch (Exception e) {
            logger.warn("Failed to reconstruct LineageEvent from dead-letter doc: {}", e.getMessage());
            return null;
        }
    }
}
