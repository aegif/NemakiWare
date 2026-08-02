package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * CouchDB-backed implementation of {@link ProjectionCursorStore}.
 *
 * <p>Stores cursor documents in the {@code nemaki_lineage} database
 * with type {@code projection_cursor} and document ID
 * {@code projection_cursor:{target}:{repositoryId}}.
 */
@Component
public class CouchProjectionCursorStore implements ProjectionCursorStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchProjectionCursorStore.class);

    @Autowired
    private LineageJournalStore journalStore;

    private CloudantClientWrapper getClient() {
        if (journalStore instanceof CouchLineageJournalStore couchStore && couchStore.isActive()) {
            return couchStore.getLineageClient();
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProjectionCursor getCursor(String target, String repositoryId) {
        CloudantClientWrapper client = getClient();
        if (client == null) return null;

        String docId = "projection_cursor:" + target + ":" + repositoryId;
        try {
            Map<String, Object> doc = client.get(Map.class, docId, null);
            if (doc == null) return null;
            return fromDoc(doc);
        } catch (Exception e) {
            logger.debug("Failed to get cursor for target='{}', repo='{}': {}", target, repositoryId, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateCursor(ProjectionCursor cursor) {
        CloudantClientWrapper client = getClient();
        if (client == null) return;

        String docId = cursor.docId();
        try {
            Map<String, Object> existing = client.get(Map.class, docId, null);
            if (existing != null) {
                existing.put("lastProcessedSequence", cursor.lastProcessedSequence());
                existing.put("updatedAt", Instant.now().toString());
                client.update(existing);
            } else {
                Map<String, Object> newDoc = new LinkedHashMap<>();
                newDoc.put("_id", docId);
                newDoc.put("type", "projection_cursor");
                newDoc.put("target", cursor.target());
                newDoc.put("repositoryId", cursor.repositoryId());
                newDoc.put("lastProcessedSequence", cursor.lastProcessedSequence());
                newDoc.put("updatedAt", Instant.now().toString());
                client.create(newDoc);
            }
        } catch (Exception e) {
            logger.warn("Failed to update cursor for target='{}', repo='{}': {}",
                    cursor.target(), cursor.repositoryId(), e.getMessage());
        }
    }

    @Override
    public boolean advanceCursorMonotonic(ProjectionCursor cursor) {
        CloudantClientWrapper client = getClient();
        if (client == null) {
            logger.error("Monotonic cursor advance impossible: cursor store inactive");
            return false;
        }
        String docId = cursor.docId();
        long incoming = cursor.lastProcessedSequence();
        if (incoming < 0) {
            logger.error("Monotonic cursor advance refused: negative incoming position {}",
                    incoming);
            return false;
        }
        // One CAS attempt + one reread on conflict: either the write lands, or someone else
        // already advanced at/past the incoming position (success), or the caller halts.
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> existing;
            try {
                existing = readRawStrict(client, docId);
            } catch (RuntimeException e) {
                logger.error("Monotonic cursor read failed for {}: {}", docId, e.getMessage());
                return false;
            }
            if (existing != null) {
                long stored;
                try {
                    Object value = existing.get("lastProcessedSequence");
                    if (!(value instanceof Number n)) {
                        throw new IllegalArgumentException("lastProcessedSequence must be a"
                                + " number, got " + value);
                    }
                    stored = new java.math.BigDecimal(n.toString()).longValueExact();
                    if (stored < 0) {
                        throw new IllegalArgumentException("stored position is negative: "
                                + stored);
                    }
                } catch (RuntimeException e) {
                    // Never coerced to zero: a malformed cursor rewound to 0 would republish
                    // the whole repository.
                    logger.error("Monotonic cursor refused malformed stored position in {}: {}",
                            docId, e.getMessage());
                    return false;
                }
                if (stored >= incoming) {
                    return true;
                }
                existing.put("lastProcessedSequence", incoming);
                existing.put("updatedAt", Instant.now().toString());
                try {
                    if (casPut(client, docId, existing)) {
                        return true;
                    }
                    // CAS lost — reread once; success iff the winner already covers us.
                    continue;
                } catch (RuntimeException e) {
                    logger.error("Monotonic cursor CAS failed for {}: {}", docId,
                            e.getMessage());
                    return false;
                }
            }
            Map<String, Object> fresh = new LinkedHashMap<>();
            fresh.put("_id", docId);
            fresh.put("type", "projection_cursor");
            fresh.put("target", cursor.target());
            fresh.put("repositoryId", cursor.repositoryId());
            fresh.put("lastProcessedSequence", incoming);
            fresh.put("updatedAt", Instant.now().toString());
            try {
                if (casPut(client, docId, fresh)) {
                    return true;
                }
                continue; // create/create conflict — reread once
            } catch (RuntimeException e) {
                logger.error("Monotonic cursor create failed for {}: {}", docId, e.getMessage());
                return false;
            }
        }
        logger.warn("Monotonic cursor advance for {} lost two CAS rounds — halting repository"
                + " until the next poll", docId);
        return false;
    }

    /** Strict raw read: 404 → null; anything else propagates (never silent absence). */
    private Map<String, Object> readRawStrict(CloudantClientWrapper client, String docId) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc = client.getClient().getDocument(
                    new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(client.getDatabaseName())
                            .docId(docId)
                            .build())
                    .execute().getResult();
            Map<String, Object> props = new LinkedHashMap<>();
            if (doc.getId() != null) props.put("_id", doc.getId());
            if (doc.getRev() != null) props.put("_rev", doc.getRev());
            if (doc.getProperties() != null) props.putAll(doc.getProperties());
            return props;
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException notFound) {
            return null;
        }
    }

    /** Strict CAS put: true = committed, false = 409; anything else propagates. */
    private boolean casPut(CloudantClientWrapper client, String docId, Map<String, Object> raw) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new LinkedHashMap<>(raw);
            withoutMeta.remove("_id");
            Object rev = withoutMeta.remove("_rev");
            doc.setProperties(withoutMeta);
            doc.setId(docId);
            if (rev != null) {
                doc.setRev((String) rev);
            }
            client.getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(client.getDatabaseName())
                            .docId(docId)
                            .document(doc)
                            .build())
                    .execute();
            return true;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ProjectionCursor> getAllCursors() {
        CloudantClientWrapper client = getClient();
        if (client == null) return Collections.emptyList();

        try {
            Map<String, Object> selector = new LinkedHashMap<>();
            selector.put("type", "projection_cursor");
            List<Map> docs = client.findBySelector(selector, Map.class);
            List<ProjectionCursor> cursors = new ArrayList<>();
            for (Map<String, Object> doc : docs) {
                try {
                    cursors.add(fromDoc(doc));
                } catch (Exception e) {
                    logger.debug("Failed to parse cursor doc: {}", e.getMessage());
                }
            }
            return cursors;
        } catch (Exception e) {
            logger.debug("Failed to get all cursors: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isActive() {
        return getClient() != null;
    }

    private ProjectionCursor fromDoc(Map<String, Object> doc) {
        String target = (String) doc.get("target");
        String repositoryId = (String) doc.get("repositoryId");
        long seq = doc.containsKey("lastProcessedSequence")
                ? ((Number) doc.get("lastProcessedSequence")).longValue()
                : 0;
        String updatedAtStr = (String) doc.get("updatedAt");
        Instant updatedAt = updatedAtStr != null ? Instant.parse(updatedAtStr) : Instant.now();
        return new ProjectionCursor(target, repositoryId, seq, updatedAt);
    }
}
