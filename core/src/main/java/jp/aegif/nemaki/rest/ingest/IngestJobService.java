package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CouchDB-backed service for ingest job records and dead-letter queue entries.
 */
public class IngestJobService {

    private static final Logger logger = LoggerFactory.getLogger(IngestJobService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    // ── Job Records ────────────────────────────────────────────────

    public IngestJobRecord createJob(String profileId, String connectorId, String repositoryId) {
        IngestJobRecord job = new IngestJobRecord();
        job.setJobId("job-" + UUID.randomUUID().toString().substring(0, 8));
        job.setProfileId(profileId);
        job.setConnectorId(connectorId);
        job.setRepositoryId(repositoryId);
        String now = Instant.now().toString();
        job.setStartedAt(now);
        job.setLastHeartbeatAt(now);
        job.setStatus(IngestJobRecord.Status.RUNNING);
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
        return job;
    }

    /**
     * Update the heartbeat timestamp of a running job.
     * Called periodically during long fetches to prove the job is alive.
     */
    public void heartbeat(IngestJobRecord job) {
        if (job == null) return;
        job.setLastHeartbeatAt(Instant.now().toString());
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
    }

    /**
     * Scan for RUNNING jobs whose lastHeartbeatAt is older than
     * {@link IngestJobRecord#STUCK_TIMEOUT_MS} and mark them STUCK.
     * Called once per scheduler poll cycle.
     *
     * @return number of jobs marked STUCK
     */
    @SuppressWarnings("unchecked")
    public int detectStuckJobs() {
        List<IngestJobRecord> running = findByTypeAndField(
                IngestJobRecord.DOC_TYPE, "status", "RUNNING",
                IngestJobRecord.class, 100);
        long now = System.currentTimeMillis();
        int stuckCount = 0;
        for (IngestJobRecord job : running) {
            String hb = job.getLastHeartbeatAt();
            if (hb == null) hb = job.getStartedAt();
            if (hb == null) continue;
            try {
                long hbMs = Instant.parse(hb).toEpochMilli();
                if (now - hbMs > IngestJobRecord.STUCK_TIMEOUT_MS) {
                    job.setStatus(IngestJobRecord.Status.STUCK);
                    job.setCompletedAt(Instant.now().toString());
                    job.setErrors(java.util.List.of("Job stuck: no heartbeat for "
                            + ((now - hbMs) / 60_000) + " minutes"));
                    upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE,
                            MAPPER.convertValue(job, Map.class));
                    logger.warn("Marked job {} as STUCK (no heartbeat for {}min)",
                            job.getJobId(), (now - hbMs) / 60_000);
                    stuckCount++;
                }
            } catch (Exception e) {
                logger.debug("Failed to parse heartbeat for job {}: {}", job.getJobId(), e.getMessage());
            }
        }
        return stuckCount;
    }

    public void completeJob(IngestJobRecord job, FetchResult result) {
        job.setCompletedAt(Instant.now().toString());
        job.setFetched(result.fetched());
        job.setImported(result.imported());
        int errorCount = result.errors() != null ? result.errors().size() : 0;
        job.setFailed(errorCount);
        job.setSkipped(result.skipped());
        job.setErrors(result.errors());
        if (result.hasErrors() && result.imported() > 0) {
            job.setStatus(IngestJobRecord.Status.PARTIAL);
        } else if (result.hasErrors()) {
            job.setStatus(IngestJobRecord.Status.FAILED);
        } else {
            job.setStatus(IngestJobRecord.Status.COMPLETED);
        }
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
    }

    public List<IngestJobRecord> listJobs(int limit) {
        return findByType(IngestJobRecord.DOC_TYPE, IngestJobRecord.class, limit);
    }

    public List<IngestJobRecord> listJobsByProfile(String profileId) {
        Map<String, Object> selector = Map.of(
                "type", IngestJobRecord.DOC_TYPE,
                "profileId", profileId);
        return findBySelector(selector, IngestJobRecord.class, 50);
    }

    // ── Dead-Letter Queue ──────────────────────────────────────────

    public void saveToDlq(ExternalIngestRequest request, String errorMessage) {
        saveToDlq(request, errorMessage, null);
    }

    public void saveToDlq(ExternalIngestRequest request, String errorMessage, byte[] contentBytes) {
        try {
            IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
            dlq.setDlqId("dlq-" + UUID.randomUUID().toString().substring(0, 8));
            dlq.setProfileId(request.getProfileId());
            dlq.setConnectorId(request.getConnectorId());
            dlq.setRepositoryId(request.getRepositoryId());
            dlq.setSourceObjectId(request.getSourceObjectId());
            dlq.setSourceObjectType(request.getSourceObjectType());
            dlq.setFileName(request.getFileName());
            dlq.setFailedAt(Instant.now().toString());
            dlq.setErrorMessage(errorMessage);
            dlq.setRetryCount(0);
            dlq.setHasContent(contentBytes != null && contentBytes.length > 0);
            // Serialize full request for replay (contentStream is @JsonIgnore, auto-excluded)
            dlq.setOriginalRequestJson(MAPPER.writeValueAsString(request));
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonMap = MAPPER.convertValue(dlq, Map.class);
            String docId = upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE, jsonMap);

            // Attach binary content to CouchDB document if available
            if (contentBytes != null && contentBytes.length > 0 && docId != null) {
                attachContentToDlq(docId, request.getFileName(), request.getMimeType(), contentBytes);
            }

            logger.info("Saved to DLQ: {} (source={}, contentSize={})",
                    dlq.getDlqId(), request.getSourceObjectId(),
                    contentBytes != null ? contentBytes.length : 0);
        } catch (Exception e) {
            logger.error("Failed to save to DLQ: {}", e.getMessage(), e);
        }
    }

    /** Load binary content from a DLQ CouchDB document's attachment. */
    public byte[] loadDlqContent(String dlqId) {
        try {
            CloudantClientWrapper client = getConfClient();
            String dbName = client.getDatabaseName();
            var cloudant = client.getClient();

            List<Document> docs = findRawDocs(cloudant, dbName,
                    Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId));
            if (docs.isEmpty()) return null;

            Document doc = docs.get(0);
            // Get the first attachment
            if (doc.getAttachments() == null || doc.getAttachments().isEmpty()) return null;
            String attName = doc.getAttachments().keySet().iterator().next();

            // Check attachment size before loading to prevent OOM
            var attMeta = doc.getAttachments().get(attName);
            if (attMeta != null && attMeta.length() != null && attMeta.length() > 100L * 1024 * 1024) {
                logger.warn("DLQ attachment too large ({} bytes) for {}, skipping", attMeta.length(), dlqId);
                return null;
            }

            var getAttOpts = new com.ibm.cloud.cloudant.v1.model.GetAttachmentOptions.Builder()
                    .db(dbName).docId(doc.getId()).attachmentName(attName).build();
            try (java.io.InputStream is = cloudant.getAttachment(getAttOpts).execute().getResult()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            logger.warn("Failed to load DLQ content for {}: {}", dlqId, e.getMessage());
            return null;
        }
    }

    /** Attach binary content to a DLQ CouchDB document. */
    private void attachContentToDlq(String docId, String fileName, String mimeType, byte[] content) {
        try {
            CloudantClientWrapper client = getConfClient();
            String dbName = client.getDatabaseName();
            var cloudant = client.getClient();

            // Get current rev
            var docResponse = cloudant.getDocument(
                    new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(dbName).docId(docId).build()).execute().getResult();
            String rev = docResponse.getRev();

            String attName = fileName != null ? fileName : "content";
            String attMime = mimeType != null ? mimeType : "application/octet-stream";
            var putAttOpts = new com.ibm.cloud.cloudant.v1.model.PutAttachmentOptions.Builder()
                    .db(dbName).docId(docId).rev(rev)
                    .attachmentName(attName).contentType(attMime)
                    .attachment(new java.io.ByteArrayInputStream(content))
                    .build();
            cloudant.putAttachment(putAttOpts).execute();
        } catch (Exception e) {
            logger.warn("Failed to attach content to DLQ {}: {}", docId, e.getMessage());
        }
    }

    public List<IngestDeadLetterRecord> listDlq(int limit) {
        return findByType(IngestDeadLetterRecord.DOC_TYPE, IngestDeadLetterRecord.class, limit);
    }

    public IngestDeadLetterRecord getDlqEntry(String dlqId) {
        List<IngestDeadLetterRecord> results = findBySelector(
                Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId),
                IngestDeadLetterRecord.class, 1);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Reserve a DLQ entry for retry by atomically updating lastRetryAt
     * BEFORE the actual dispatch.  Uses CouchDB _rev as an optimistic
     * lock: if another thread already reserved the same entry, the
     * upsert fails with 409 Conflict and we return false.
     *
     * @return true if reservation succeeded (caller should proceed with dispatch),
     *         false if another concurrent retry already claimed this entry
     */
    public boolean reserveDlqRetry(IngestDeadLetterRecord dlq) {
        dlq.setRetryCount(dlq.getRetryCount() + 1);
        dlq.setLastRetryAt(Instant.now().toString());
        try {
            String savedId = upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE,
                    MAPPER.convertValue(dlq, Map.class));
            if (savedId == null) {
                // upsertDocument returns null on !result.isOk() (e.g. _rev conflict)
                // without throwing — treat as failed reservation
                logger.debug("DLQ retry reservation failed (write conflict): dlqId={}", dlq.getDlqId());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.debug("DLQ retry reservation failed (concurrent retry?): {}", e.getMessage());
            return false;
        }
    }

    /** @deprecated Use {@link #reserveDlqRetry(IngestDeadLetterRecord)} instead. */
    @Deprecated
    public void updateDlqRetry(IngestDeadLetterRecord dlq) {
        reserveDlqRetry(dlq);
    }

    public void deleteDlqEntry(String dlqId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();
        List<Document> docs = findRawDocs(cloudant, dbName,
                Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId));
        for (Document doc : docs) {
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String upsertDocument(String docKey, String docType, Map<String, Object> jsonMap) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();

        jsonMap.put("type", docType);
        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        // Find existing by docKey field name (jobId or dlqId)
        String keyField = docType.equals(IngestJobRecord.DOC_TYPE) ? "jobId" : "dlqId";
        List<Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", docType, keyField, docKey));
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            logger.error("Failed to save {} {}: {}", docType, docKey, result.getError());
            return null;
        }
        return result.getId();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> findByType(String docType, Class<T> clazz, int limit) {
        return findBySelector(Map.of("type", docType), clazz, limit);
    }

    private <T> List<T> findByTypeAndField(String docType, String field, String value,
                                            Class<T> clazz, int limit) {
        return findBySelector(Map.of("type", docType, field, value), clazz, limit);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> findBySelector(Map<String, Object> selector, Class<T> clazz, int limit) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();

        List<Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<T> results = new ArrayList<>();
        int count = 0;
        for (Document rawDoc : rawDocs) {
            if (count >= limit) break;
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, clazz));
                count++;
            } catch (Exception e) {
                logger.warn("Failed to deserialize {}: {}", clazz.getSimpleName(), e.getMessage());
            }
        }
        return results;
    }

    private List<Document> findRawDocs(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
                                       String dbName, Map<String, Object> selector) {
        PostFindOptions findOptions = new PostFindOptions.Builder()
                .db(dbName).selector(selector).limit(200).build();
        FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
        List<Document> docs = findResult.getDocs();
        return docs != null ? docs : List.of();
    }

    private CloudantClientWrapper getConfClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("nemaki_conf database client not available");
        }
        return client;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
