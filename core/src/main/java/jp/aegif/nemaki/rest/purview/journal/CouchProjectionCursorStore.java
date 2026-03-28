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
