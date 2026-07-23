package jp.aegif.nemaki.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;

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
 * CouchDB-backed durable queue for {@link SearchIndexAclReindexTask} entries.
 *
 * <p>Records objects whose asynchronous search-index ACL refresh
 * ({@code AclServiceImpl}) failed, so a scheduled poller can re-drive them and an
 * operator can observe / retry them. Persisted in {@code nemaki_conf}, following
 * the same pattern as {@code IngestJobService}: Mango {@code _find} selectors,
 * upsert keyed by the natural {@code taskId}, and CouchDB {@code _rev} as an
 * optimistic lock so two replicas cannot process the same entry twice.
 */
public class SearchIndexReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexReconciliationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    // ── Enqueue ────────────────────────────────────────────────────

    /**
     * Enqueue (or refresh) a reconciliation task for {@code objectId}. Deduped by
     * {@code (repositoryId, objectId)}:
     * <ul>
     *   <li>no existing entry → create a fresh {@code PENDING} task (attempts=0,
     *       due now);</li>
     *   <li>existing {@code PENDING} → update its reason/timestamp but keep its
     *       attempt count and backoff so a storm of enqueues cannot reset the
     *       retry clock;</li>
     *   <li>existing {@code FAILED} (previously gave up) → re-open it as
     *       {@code PENDING} with attempts=0, due now (a fresh ACL event deserves a
     *       fresh set of retries).</li>
     * </ul>
     * Never throws — a reconciliation-queue write failure must not break the ACL
     * change that triggered it.
     */
    public void enqueue(String repositoryId, String objectId, String reason) {
        if (repositoryId == null || objectId == null) {
            return;
        }
        try {
            String now = Instant.now().toString();
            SearchIndexAclReindexTask existing = findByRepoAndObject(repositoryId, objectId);
            SearchIndexAclReindexTask task;
            if (existing == null) {
                task = new SearchIndexAclReindexTask();
                task.setTaskId("sir-" + UUID.randomUUID().toString().substring(0, 8));
                task.setRepositoryId(repositoryId);
                task.setObjectId(objectId);
                task.setAttempts(0);
                task.setStatus(SearchIndexAclReindexTask.Status.PENDING);
                task.setCreatedAt(now);
                task.setNextAttemptAt(now);
            } else {
                task = existing;
                if (SearchIndexAclReindexTask.Status.FAILED.equals(task.getStatus())) {
                    task.setStatus(SearchIndexAclReindexTask.Status.PENDING);
                    task.setAttempts(0);
                    task.setNextAttemptAt(now);
                }
            }
            task.setReason(reason);
            task.setUpdatedAt(now);
            upsert(task);
            if (logger.isDebugEnabled()) {
                logger.debug("Enqueued search-index ACL reconcile: repo={} object={} reason={} taskId={}",
                        repositoryId, objectId, reason, task.getTaskId());
            }
        } catch (Exception e) {
            logger.warn("Failed to enqueue search-index ACL reconcile for {} / {}: {}",
                    repositoryId, objectId, e.getMessage());
        }
    }

    // ── Query ──────────────────────────────────────────────────────

    /** All PENDING tasks whose {@code nextAttemptAt} is at or before now, up to {@code limit}. */
    public List<SearchIndexAclReindexTask> listDue(int limit) {
        String now = Instant.now().toString();
        List<SearchIndexAclReindexTask> pending = findBySelector(
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                        "status", SearchIndexAclReindexTask.Status.PENDING),
                200);
        List<SearchIndexAclReindexTask> due = new ArrayList<>();
        for (SearchIndexAclReindexTask t : pending) {
            String next = t.getNextAttemptAt();
            if (next == null || next.compareTo(now) <= 0) {
                due.add(t);
                if (due.size() >= limit) break;
            }
        }
        return due;
    }

    public List<SearchIndexAclReindexTask> list(int limit) {
        return findBySelector(Map.of("type", SearchIndexAclReindexTask.DOC_TYPE), limit);
    }

    public SearchIndexAclReindexTask getByTaskId(String taskId) {
        List<SearchIndexAclReindexTask> r = findBySelector(
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE, "taskId", taskId), 1);
        return r.isEmpty() ? null : r.get(0);
    }

    private SearchIndexAclReindexTask findByRepoAndObject(String repositoryId, String objectId) {
        List<SearchIndexAclReindexTask> r = findBySelector(
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                        "repositoryId", repositoryId, "objectId", objectId), 1);
        return r.isEmpty() ? null : r.get(0);
    }

    // ── Retry lifecycle ────────────────────────────────────────────

    /**
     * Reserve a task for a retry attempt: bump attempts, push {@code nextAttemptAt}
     * out by {@code backoffMs}, and persist under the current {@code _rev}. Uses the
     * {@code _rev} optimistic lock — returns {@code false} if another replica /
     * thread already claimed this entry (concurrent poll), so the caller must not
     * proceed with the re-index.
     */
    public boolean reserveForRetry(SearchIndexAclReindexTask task, long backoffMs) {
        task.setAttempts(task.getAttempts() + 1);
        String now = Instant.now().toString();
        task.setUpdatedAt(now);
        task.setNextAttemptAt(Instant.now().plusMillis(Math.max(0, backoffMs)).toString());
        try {
            return upsert(task) != null;
        } catch (Exception e) {
            logger.debug("Reconcile retry reservation failed (concurrent poll?): {}", e.getMessage());
            return false;
        }
    }

    /** Mark a task permanently FAILED (retries exhausted) — kept for operator visibility. */
    public void markFailed(SearchIndexAclReindexTask task, String error) {
        task.setStatus(SearchIndexAclReindexTask.Status.FAILED);
        task.setLastError(truncate(error));
        task.setUpdatedAt(Instant.now().toString());
        try {
            upsert(task);
        } catch (Exception e) {
            logger.warn("Failed to mark reconcile task {} FAILED: {}", task.getTaskId(), e.getMessage());
        }
    }

    public void delete(String taskId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        Cloudant cloudant = client.getClient();
        for (Document doc : findRawDocs(cloudant, dbName,
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE, "taskId", taskId))) {
            cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String upsert(SearchIndexAclReindexTask task) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        Cloudant cloudant = client.getClient();

        Map<String, Object> jsonMap = MAPPER.convertValue(task, Map.class);
        jsonMap.put("type", SearchIndexAclReindexTask.DOC_TYPE);
        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }
        List<Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE, "taskId", task.getTaskId()));
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        }
        DocumentResult result = cloudant.postDocument(new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build()).execute().getResult();
        if (result == null || !result.isOk()) {
            logger.debug("Reconcile upsert not ok for {}: {}", task.getTaskId(),
                    result != null ? result.getError() : "null result");
            return null;
        }
        return result.getId();
    }

    @SuppressWarnings("unchecked")
    private List<SearchIndexAclReindexTask> findBySelector(Map<String, Object> selector, int limit) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        Cloudant cloudant = client.getClient();
        List<Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<SearchIndexAclReindexTask> results = new ArrayList<>();
        for (Document rawDoc : rawDocs) {
            if (results.size() >= limit) break;
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, SearchIndexAclReindexTask.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize reconcile task: {}", e.getMessage());
            }
        }
        return results;
    }

    private List<Document> findRawDocs(Cloudant cloudant, String dbName, Map<String, Object> selector) {
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

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
