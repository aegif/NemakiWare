package jp.aegif.nemaki.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.PutDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.ConflictException;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CouchDB-backed durable queue for {@link SearchIndexAclReindexTask}, redesigned
 * for correct concurrency / durability semantics:
 *
 * <ul>
 *   <li><b>Deterministic {@code _id}</b> ({@code (repository, object)}): concurrent
 *       enqueues collapse to one document — a create conflict (409) is resolved as
 *       an in-place update.</li>
 *   <li><b>{@code _rev} compare-and-swap</b> for every transition (claim / complete
 *       / retry / fail): a stale rev → 409 → the operation is abandoned, so two
 *       replicas cannot both process the same entry and a poller cannot clobber a
 *       newer failure event that arrived mid-flight.</li>
 *   <li><b>Lease</b> ({@code LEASED} + {@code leaseExpiresAt}): a crashed poller's
 *       claim expires and is reclaimable.</li>
 *   <li><b>DB-side due selection</b>: a Mango {@code $lte} range + ascending sort on
 *       {@code nextAttemptAt} (epoch millis), so the oldest-due entries are served
 *       first and a backlog beyond one batch is not starved.</li>
 * </ul>
 *
 * <p>Enqueue never throws; a persistence failure increments an in-JVM counter
 * ({@link #getEnqueueFailureCount()}) surfaced via the admin metrics endpoint —
 * because if CouchDB itself is unavailable, the queue write fails too (the ACL
 * change that triggered it was persisted earlier while CouchDB was up; this queue
 * targets the common case of a Solr-only failure with CouchDB healthy).
 */
public class SearchIndexReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexReconciliationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ENQUEUE_CONFLICT_RETRIES = 5;
    private static final int METRICS_CAP = 1000;

    private CloudantClientPool connectorPool;
    private final AtomicLong enqueueFailureCount = new AtomicLong(0);

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    public long getEnqueueFailureCount() {
        return enqueueFailureCount.get();
    }

    // ── Enqueue (atomic dedupe by deterministic _id, generation bump) ──

    /**
     * Enqueue (or refresh) the reconciliation entry for {@code objectId}. Idempotent
     * per {@code (repositoryId, objectId)} via the deterministic {@code _id}: if an
     * entry exists it is flipped back to {@code PENDING}, its {@code generation} is
     * bumped (which invalidates any in-flight lease's rev) and it becomes due now;
     * otherwise a fresh {@code PENDING} entry is created. Create/update conflicts are
     * retried a bounded number of times. Never throws.
     */
    public void enqueue(String repositoryId, String objectId, String reason) {
        if (repositoryId == null || objectId == null) {
            return;
        }
        String docId = SearchIndexAclReindexTask.deterministicId(repositoryId, objectId);
        for (int attempt = 0; attempt < ENQUEUE_CONFLICT_RETRIES; attempt++) {
            try {
                long now = System.currentTimeMillis();
                SearchIndexAclReindexTask existing = getByCouchId(docId);
                SearchIndexAclReindexTask task;
                if (existing == null) {
                    task = new SearchIndexAclReindexTask();
                    task.setTaskId("sir-" + UUID.randomUUID());
                    task.setRepositoryId(repositoryId);
                    task.setObjectId(objectId);
                    task.setCouchId(docId);
                    task.setCouchRev(null); // create
                    task.setAttempts(0);
                    task.setGeneration(1);
                    task.setCreatedAt(now);
                } else {
                    task = existing;
                    task.setGeneration(task.getGeneration() + 1);
                    // A fresh failure event deserves a fresh retry budget — reset the
                    // attempt count so re-opening a FAILED entry (or any new event) does
                    // not inherit a nearly-exhausted count from the previous episode.
                    task.setAttempts(0);
                }
                task.setStatus(SearchIndexAclReindexTask.Status.PENDING);
                task.setReason(reason);
                task.setNextAttemptAt(now); // a fresh failure is due immediately
                task.setLeaseOwner(null);
                task.setLeaseExpiresAt(0);
                task.setUpdatedAt(now);
                if (putCas(task) != null) {
                    return; // success
                }
                // CAS conflict — another writer changed the doc; retry the loop.
            } catch (Exception e) {
                logger.warn("Failed to enqueue reconcile for {} / {} (attempt {}): {}",
                        repositoryId, objectId, attempt + 1, e.getMessage());
                enqueueFailureCount.incrementAndGet();
                return;
            }
        }
        logger.warn("Failed to enqueue reconcile for {} / {} after {} conflict retries",
                repositoryId, objectId, ENQUEUE_CONFLICT_RETRIES);
        enqueueFailureCount.incrementAndGet();
    }

    // ── Claim (CAS lease) ──────────────────────────────────────────

    /**
     * Claim up to {@code batchSize} due entries for {@code nodeId}, leasing each for
     * {@code leaseMillis}. "Due" = {@code PENDING} with {@code nextAttemptAt <= now},
     * plus {@code LEASED} entries whose lease has expired (crashed holder). Each claim
     * is a {@code _rev} CAS that flips the entry to {@code LEASED}; a lost CAS (another
     * replica won) simply drops the candidate. Returns the entries actually claimed
     * (each carrying the post-claim {@code _rev} for a later CAS complete/retry/fail).
     */
    public List<SearchIndexAclReindexTask> claimDue(int batchSize, String nodeId, long leaseMillis) {
        long now = System.currentTimeMillis();
        List<SearchIndexAclReindexTask> candidates = new ArrayList<>();
        // Expired LEASED first: these are tasks a crashed/stalled worker abandoned, so
        // they must be recovered promptly — and taking them first prevents them from
        // being starved under a sustained PENDING backlog (a full batch of PENDING
        // would otherwise never leave room for expired-lease reclaim).
        candidates.addAll(findSortedAsc(
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                        "status", SearchIndexAclReindexTask.Status.LEASED,
                        "leaseExpiresAt", Map.of("$lte", now)),
                "leaseExpiresAt", batchSize));
        if (candidates.size() < batchSize) {
            candidates.addAll(findSortedAsc(
                    Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                            "status", SearchIndexAclReindexTask.Status.PENDING,
                            "nextAttemptAt", Map.of("$lte", now)),
                    "nextAttemptAt", batchSize - candidates.size()));
        }
        List<SearchIndexAclReindexTask> claimed = new ArrayList<>();
        for (SearchIndexAclReindexTask task : candidates) {
            if (claimed.size() >= batchSize) break;
            task.setStatus(SearchIndexAclReindexTask.Status.LEASED);
            task.setLeaseOwner(nodeId);
            task.setLeaseExpiresAt(now + Math.max(1000L, leaseMillis));
            task.setUpdatedAt(now);
            String newRev = putCas(task);
            if (newRev != null) {
                claimed.add(task); // couchRev updated by putCas
            }
            // else: another replica claimed / a new event superseded — skip.
        }
        return claimed;
    }

    // ── ACK / retry / fail (CAS on the claim rev) ──────────────────

    /**
     * Complete a claimed task (delete it). CAS on the claim {@code _rev}: if a new
     * enqueue bumped the generation while we were re-driving, the rev changed and the
     * delete fails (409) — the fresh {@code PENDING} entry survives and is re-processed.
     *
     * @return true if deleted, false if the CAS lost (a newer event survived).
     */
    public boolean complete(SearchIndexAclReindexTask task) {
        return deleteCas(task);
    }

    /**
     * Heartbeat: extend a held lease when it is running low, so a legitimately long
     * re-drive of a large subtree does not lose its lease mid-flight. CAS on the
     * current rev — if another worker has reclaimed the (expired) lease the rev has
     * changed and the renewal fails, returning {@code false} so the caller
     * ({@link jp.aegif.nemaki.cmis.service.AclService#reindexSearchIndexAclForObject})
     * STOPS writing (cooperative fencing: a worker that lost its lease must not keep
     * overwriting the reclaiming worker's fresher readers). Returns {@code true}
     * while still comfortably held (no write) or after a successful renewal.
     */
    public boolean renewLeaseIfNeeded(SearchIndexAclReindexTask task, long leaseMillis) {
        if (task == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        // Renew only when less than half the lease remains (cheap no-op otherwise).
        if (task.getLeaseExpiresAt() - now > Math.max(1000L, leaseMillis) / 2) {
            return true;
        }
        task.setLeaseExpiresAt(now + Math.max(1000L, leaseMillis));
        task.setUpdatedAt(now);
        return putCas(task) != null;
    }

    /** Release the lease and reschedule with backoff (CAS on the claim rev). */
    public boolean retryLater(SearchIndexAclReindexTask task, long backoffMillis) {
        long now = System.currentTimeMillis();
        task.setAttempts(task.getAttempts() + 1);
        task.setStatus(SearchIndexAclReindexTask.Status.PENDING);
        task.setNextAttemptAt(now + Math.max(0, backoffMillis));
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(0);
        task.setUpdatedAt(now);
        return putCas(task) != null;
    }

    /** Mark permanently FAILED — kept for operator inspection (CAS on the claim rev). */
    public boolean markFailed(SearchIndexAclReindexTask task, String error) {
        long now = System.currentTimeMillis();
        task.setAttempts(task.getAttempts() + 1);
        task.setStatus(SearchIndexAclReindexTask.Status.FAILED);
        task.setLastError(truncate(error));
        task.setLeaseOwner(null);
        task.setLeaseExpiresAt(0);
        task.setUpdatedAt(now);
        return putCas(task) != null;
    }

    // ── Admin / metrics ────────────────────────────────────────────

    private static final int LIST_LIMIT_CAP = 1000;

    public List<SearchIndexAclReindexTask> list(int limit) {
        return list(null, limit);
    }

    /**
     * List tasks, optionally filtered by {@code status} — applied in the Mango
     * selector (NOT after a limit) so {@code ?status=FAILED} is accurate even when
     * FAILED entries sit past the first page of PENDING ones. The limit is capped.
     */
    public List<SearchIndexAclReindexTask> list(String status, int limit) {
        int capped = Math.min(Math.max(1, limit), LIST_LIMIT_CAP);
        Map<String, Object> selector = (status == null || status.isBlank())
                ? Map.of("type", SearchIndexAclReindexTask.DOC_TYPE)
                : Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                        "status", status.toUpperCase(java.util.Locale.ROOT));
        return find(selector, capped);
    }

    public SearchIndexAclReindexTask getByTaskId(String taskId) {
        List<SearchIndexAclReindexTask> r = find(
                Map.of("type", SearchIndexAclReindexTask.DOC_TYPE, "taskId", taskId), 1);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Admin override: delete the entry addressed by its opaque taskId (CAS on the current rev). */
    public boolean forceDeleteByTaskId(String taskId) {
        SearchIndexAclReindexTask t = getByTaskId(taskId);
        return t != null && deleteCas(t);
    }

    /**
     * Claim a single task by its opaque taskId for a MANUAL (admin) retry — the same
     * {@code _rev} CAS lease the poller uses, so a manual retry serializes with the
     * scheduler instead of racing it (two workers must not re-index the same object
     * concurrently). Returns the claimed task (carrying the post-claim rev), or
     * {@code null} if the task is missing, is currently {@code LEASED} with an
     * unexpired lease (a poller is processing it), or the claim CAS lost.
     */
    public SearchIndexAclReindexTask claimForManualRetry(String taskId, String nodeId, long leaseMillis) {
        SearchIndexAclReindexTask t = getByTaskId(taskId);
        if (t == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (SearchIndexAclReindexTask.Status.LEASED.equals(t.getStatus()) && t.getLeaseExpiresAt() > now) {
            return null; // an active lease means a poller is (or just was) processing it
        }
        t.setStatus(SearchIndexAclReindexTask.Status.LEASED);
        t.setLeaseOwner(nodeId);
        t.setLeaseExpiresAt(now + Math.max(1000L, leaseMillis));
        t.setUpdatedAt(now);
        return putCas(t) != null ? t : null;
    }

    /** True if the task addressed by taskId is currently LEASED with an unexpired lease. */
    public boolean isActivelyLeased(String taskId) {
        SearchIndexAclReindexTask t = getByTaskId(taskId);
        return t != null && SearchIndexAclReindexTask.Status.LEASED.equals(t.getStatus())
                && t.getLeaseExpiresAt() > System.currentTimeMillis();
    }

    /**
     * Queue-health snapshot for alerting. Fail-SOFT: the in-process
     * {@code enqueueFailureCount} is ALWAYS reported (even when CouchDB is down),
     * and if the CouchDB count/age queries fail the response carries
     * {@code queueMetricsAvailable=false} rather than erroring — so a monitor can
     * still see "enqueues are failing" during a CouchDB outage.
     *
     * <p>Two distinct age signals: {@code oldestPendingCreatedAgeMs} (how long the
     * longest-waiting entry has existed) and {@code mostOverduePendingMs} (how far
     * past its due time the most-overdue entry is — 0 if nothing is due yet).
     * Note: {@code enqueueFailureCount} is per-JVM and resets on restart; in a
     * multi-replica deployment aggregate it across replicas.
     */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enqueueFailureCount", enqueueFailureCount.get());
        m.put("countsCappedAt", METRICS_CAP);
        try {
            long now = System.currentTimeMillis();
            m.put("pending", countCapped(SearchIndexAclReindexTask.Status.PENDING));
            m.put("leased", countCapped(SearchIndexAclReindexTask.Status.LEASED));
            m.put("failed", countCapped(SearchIndexAclReindexTask.Status.FAILED));

            List<SearchIndexAclReindexTask> oldestCreated = findSortedAsc(
                    Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                            "status", SearchIndexAclReindexTask.Status.PENDING),
                    "createdAt", 1);
            m.put("oldestPendingCreatedAgeMs", oldestCreated.isEmpty()
                    ? 0L : Math.max(0L, now - oldestCreated.get(0).getCreatedAt()));

            List<SearchIndexAclReindexTask> mostOverdue = findSortedAsc(
                    Map.of("type", SearchIndexAclReindexTask.DOC_TYPE,
                            "status", SearchIndexAclReindexTask.Status.PENDING),
                    "nextAttemptAt", 1);
            long overdue = mostOverdue.isEmpty() ? 0L
                    : Math.max(0L, now - mostOverdue.get(0).getNextAttemptAt());
            m.put("mostOverduePendingMs", overdue);

            m.put("queueMetricsAvailable", true);
        } catch (Exception e) {
            logger.warn("Reconcile metrics query failed (CouchDB unavailable?): {}", e.getMessage());
            m.put("queueMetricsAvailable", false);
        }
        return m;
    }

    // ── CouchDB primitives (CAS) ───────────────────────────────────

    /** PUT the task at its deterministic id with its captured rev (CAS). Returns the new rev, or null on 409. */
    @SuppressWarnings("unchecked")
    private String putCas(SearchIndexAclReindexTask task) {
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();

        Map<String, Object> props = MAPPER.convertValue(task, Map.class);
        props.put("type", SearchIndexAclReindexTask.DOC_TYPE);
        props.remove("_id");
        props.remove("_rev");

        Document doc = new Document();
        doc.setId(task.getCouchId());
        if (task.getCouchRev() != null) {
            doc.setRev(task.getCouchRev());
        }
        doc.setProperties(props);
        try {
            var result = cloudant.putDocument(new PutDocumentOptions.Builder()
                    .db(db).docId(task.getCouchId()).document(doc).build()).execute().getResult();
            if (result != null && result.isOk()) {
                task.setCouchRev(result.getRev());
                return result.getRev();
            }
            return null;
        } catch (ConflictException e) {
            return null; // CAS lost
        }
    }

    private boolean deleteCas(SearchIndexAclReindexTask task) {
        if (task.getCouchId() == null || task.getCouchRev() == null) {
            return false;
        }
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            var result = cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                    .db(db).docId(task.getCouchId()).rev(task.getCouchRev()).build()).execute().getResult();
            return result != null && result.isOk();
        } catch (ConflictException e) {
            return false; // a newer event changed the doc — leave it
        }
    }

    private SearchIndexAclReindexTask getByCouchId(String docId) {
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            Document doc = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(db).docId(docId).build()).execute().getResult();
            return toTask(doc);
        } catch (NotFoundException e) {
            return null;
        }
    }

    // ── Mango find helpers ─────────────────────────────────────────

    private List<SearchIndexAclReindexTask> find(Map<String, Object> selector, int limit) {
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        FindResult r = cloudant.postFind(new PostFindOptions.Builder()
                .db(db).selector(selector).limit(Math.max(1, limit)).build()).execute().getResult();
        return toTasks(r);
    }

    private List<SearchIndexAclReindexTask> findSortedAsc(Map<String, Object> selector, String sortField, int limit) {
        if (limit <= 0) return List.of();
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        try {
            FindResult r = cloudant.postFind(new PostFindOptions.Builder()
                    .db(db).selector(selector)
                    .sort(List.of(Map.of(sortField, "asc")))
                    .limit(limit).build()).execute().getResult();
            return toTasks(r);
        } catch (Exception e) {
            // A missing sort index degrades to an unsorted scan rather than failing
            // the whole poll (the patch registers the index; this is a safety net).
            logger.debug("Sorted find fell back to unsorted (index not ready?): {}", e.getMessage());
            return find(selector, limit);
        }
    }

    private List<SearchIndexAclReindexTask> toTasks(FindResult r) {
        List<SearchIndexAclReindexTask> out = new ArrayList<>();
        if (r == null || r.getDocs() == null) return out;
        for (Document d : r.getDocs()) {
            SearchIndexAclReindexTask t = toTask(d);
            if (t != null) out.add(t);
        }
        return out;
    }

    private SearchIndexAclReindexTask toTask(Document doc) {
        if (doc == null) return null;
        try {
            Map<String, Object> props = new HashMap<>(doc.getProperties());
            props.remove("_id");
            props.remove("_rev");
            props.remove("type");
            SearchIndexAclReindexTask t = MAPPER.convertValue(props, SearchIndexAclReindexTask.class);
            t.setCouchId(doc.getId());
            t.setCouchRev(doc.getRev());
            return t;
        } catch (Exception e) {
            logger.warn("Failed to deserialize reconcile task {}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private int countCapped(String status) {
        return find(Map.of("type", SearchIndexAclReindexTask.DOC_TYPE, "status", status), METRICS_CAP).size();
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
