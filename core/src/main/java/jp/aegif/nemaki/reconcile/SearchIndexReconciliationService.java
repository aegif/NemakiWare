package jp.aegif.nemaki.reconcile;

import tools.jackson.databind.ObjectMapper;
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
import jp.aegif.nemaki.config.ObjectMapperFactory;

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
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();
    /**
     * A stored task whose epoch obligation is CORRUPT (increment 7a). Deliberately its own type so
     * {@link #toTask}'s catch-all cannot swallow it into a phantom "no such task".
     */
    public static final class CorruptReconcileTaskException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public CorruptReconcileTaskException(String message) { super(message); }
    }

    /** The persisted field name (increment 7a). */
    static final String FIELD_MIN_REQUIRED_EPOCH = "minRequiredEpoch";

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
        enqueue(repositoryId, objectId, reason, SearchIndexAclReindexTask.Operation.ACL_REINDEX);
    }

    /**
     * Best-effort enqueue CARRYING an epoch obligation (§11.3): used by refresh paths that know the
     * mutation's finalized epoch {@code E}, so a per-node failure's task records what it owes. Merge
     * is monotonic-max as everywhere else; {@code null} means "no obligation known" (0).
     */
    public void enqueue(String repositoryId, String objectId, String reason, Long minRequiredEpoch) {
        tryEnqueue(repositoryId, objectId, reason,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX,
                minRequiredEpoch == null ? 0L : minRequiredEpoch);
    }

    /**
     * As {@link #enqueue(String, String, String)} with an explicit
     * {@link SearchIndexAclReindexTask.Operation}. ACL_REINDEX and RAG_PURGE are
     * INDEPENDENT obligations living under SEPARATE deterministic ids (an ACL
     * reindex covers CMIS readers + descendants + relationships; a purge covers
     * only the RAG block) — there is deliberately NO cross-operation merge rule:
     * the previous "PURGE wins on a shared id" scheme let a completed purge delete
     * an unfinished ACL obligation. The same object may hold both tasks.
     */
    public void enqueue(String repositoryId, String objectId, String reason, String operation) {
        // Best-effort refresh carries NO epoch obligation (0). A monotonic merge means this can only
        // ever raise the task's PENDING status, never lower an obligation a finalized epoch set.
        tryEnqueue(repositoryId, objectId, reason, operation, 0L);
    }

    /**
     * DURABLE enqueue for security obligations (the PWC RAG purge): returns only
     * after the task document durably exists, and THROWS otherwise. The fail-soft
     * {@link #enqueue} may swallow a CouchDB outage into a metric — acceptable for
     * best-effort refresh acceleration, but a security purge must never end up in
     * "Solr delete failed AND queue write failed AND the caller reported success".
     *
     * @throws IllegalStateException when the task could not be durably persisted
     */
    public void enqueueOrThrow(String repositoryId, String objectId, String reason, String operation) {
        enqueueOrThrow(repositoryId, objectId, reason, operation, 0L);
    }

    /**
     * DURABLE enqueue carrying an EPOCH OBLIGATION (design §3, increment 7a) — the primitive the
     * epoch outbox ACK is built on.
     *
     * <p>Returns only after the task durably records {@code minRequiredEpoch >= requiredEpoch}. That
     * is a stronger contract than "the task exists": the ACK condition is the obligation, not the
     * document. Marking a document {@code RECONCILE_ENQUEUED} against a task whose obligation
     * predates the epoch just finalized would clear the outbox for work nobody is going to do, and
     * the scanner would then count that document as enqueued and never look at it again.
     *
     * <p>The post-write verification is a fresh READ, not a trust of the write: a CAS that lost, a
     * concurrent writer, or a v1 task whose field is absent must all fail the check rather than pass
     * on the strength of what we intended to store.
     *
     * <p>{@code requiredEpoch} must be {@code >= 1} for a real obligation — the counter's first
     * allocation is 1, so 0 means "no epoch obligation" and is what the best-effort path passes.
     *
     * @throws IllegalStateException when the obligation could not be durably established
     */
    public void enqueueOrThrow(String repositoryId, String objectId, String reason, String operation,
                               long requiredEpoch) {
        if (requiredEpoch > 0 && SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(operation)) {
            throw new IllegalArgumentException("RAG_PURGE carries no epoch obligation — a purge is an "
                    + "unconditional deletion, not a reconciliation to a point in the ACL timeline");
        }
        if (!tryEnqueue(repositoryId, objectId, reason, operation, requiredEpoch)) {
            throw new IllegalStateException("Failed to durably enqueue " + operation
                    + " reconciliation task for " + repositoryId + " / " + objectId);
        }
        if (requiredEpoch > 0) {
            String docId = SearchIndexAclReindexTask.deterministicId(repositoryId, objectId, operation);
            SearchIndexAclReindexTask stored = getByCouchId(docId);
            if (stored == null || stored.getMinRequiredEpoch() < requiredEpoch) {
                throw new IllegalStateException("Reconciliation task for " + repositoryId + " / "
                        + objectId + " does not durably record minRequiredEpoch >= " + requiredEpoch
                        + " (stored=" + (stored == null ? "absent" : stored.getMinRequiredEpoch())
                        + ") — refusing to report the obligation as enqueued");
            }
        }
    }

    /** @return true iff the task durably exists after this call. Never throws. */
    private boolean tryEnqueue(String repositoryId, String objectId, String reason, String operation,
                               long requiredEpoch) {
        if (repositoryId == null || objectId == null) {
            return false;
        }
        String docId = SearchIndexAclReindexTask.deterministicId(repositoryId, objectId, operation);
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
                    task.setMinRequiredEpoch(requiredEpoch);
                    task.setCreatedAt(now);
                } else {
                    task = existing;
                    task.setGeneration(task.getGeneration() + 1);
                    // MONOTONIC max (increment 7a): a later best-effort refresh must never LOWER an
                    // obligation that a finalized epoch already raised. Same-operation only — the id
                    // encodes the operation, so a merge never meets a RAG_PURGE here.
                    task.setMinRequiredEpoch(Math.max(task.getMinRequiredEpoch(), requiredEpoch));
                    // A fresh failure event deserves a fresh retry budget — reset the
                    // attempt count so re-opening a FAILED entry (or any new event) does
                    // not inherit a nearly-exhausted count from the previous episode.
                    task.setAttempts(0);
                }
                task.setStatus(SearchIndexAclReindexTask.Status.PENDING);
                task.setReason(reason);
                // Per-operation namespaces: the id already encodes the operation, so a
                // merge only ever meets a task of the SAME operation. Just (re)stamp it.
                task.setOperation(SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(operation)
                        ? SearchIndexAclReindexTask.Operation.RAG_PURGE
                        : SearchIndexAclReindexTask.Operation.ACL_REINDEX);
                task.setNextAttemptAt(now); // a fresh failure is due immediately
                task.setLeaseOwner(null);
                task.setLeaseExpiresAt(0);
                task.setUpdatedAt(now);
                if (putCas(task) != null) {
                    return true; // durably persisted
                }
                // CAS conflict — another writer changed the doc; retry the loop.
            } catch (CorruptReconcileTaskException e) {
                // Review 7a residual #1: do NOT flatten corruption into a generic enqueue failure.
                // `enqueueOrThrow` would otherwise report a plain IllegalStateException and the ACK
                // audit could not tell "CouchDB was unavailable" from "the stored obligation is
                // damaged" — which need different operator responses. The outbox marker survives
                // either way (fail-closed), but only one of them is repairable by retrying.
                enqueueFailureCount.incrementAndGet();
                throw e;
            } catch (Exception e) {
                logger.warn("Failed to enqueue reconcile for {} / {} (attempt {}): {}",
                        repositoryId, objectId, attempt + 1, e.getMessage());
                enqueueFailureCount.incrementAndGet();
                return false;
            }
        }
        logger.warn("Failed to enqueue reconcile for {} / {} after {} conflict retries",
                repositoryId, objectId, ENQUEUE_CONFLICT_RETRIES);
        enqueueFailureCount.incrementAndGet();
        return false;
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
        // Attempt to extend the lease via CAS. CRITICAL: do NOT commit the local
        // leaseExpiresAt/updatedAt mutation unless the CAS SUCCEEDS — otherwise a
        // failed renewal (the lease was reclaimed, or putCas THREW on a timeout)
        // would leave the local expiry in the future, so the NEXT checkpoint would
        // see "plenty of time left", skip the CAS, and wrongly report the lease as
        // still held, silently defeating cooperative fencing. The restore runs in a
        // finally so it covers BOTH a null (conflict) return AND a thrown exception;
        // the exception itself still propagates (the guard treats it as lease-lost).
        long prevExpires = task.getLeaseExpiresAt();
        long prevUpdated = task.getUpdatedAt();
        boolean committed = false;
        try {
            task.setLeaseExpiresAt(now + Math.max(1000L, leaseMillis));
            task.setUpdatedAt(now);
            committed = putCas(task) != null;
            return committed;
        } finally {
            if (!committed) {
                task.setLeaseExpiresAt(prevExpires);
                task.setUpdatedAt(prevUpdated);
            }
        }
    }

    /**
     * A latched cooperative-fencing guard for a claimed task, shared by the poller
     * and the admin manual-retry so both fence identically. Each call
     * {@link #renewLeaseIfNeeded heartbeats/renews} the lease and returns whether it
     * is still held; once a renewal fails (the lease was reclaimed by another worker)
     * the guard LATCHES to {@code false} permanently — a worker that lost its lease
     * must never resume writing, even if a later renewal transiently looked fine. The
     * re-drive polls this before every index write and aborts (LeaseLostException)
     * the moment it returns {@code false}.
     */
    public java.util.function.BooleanSupplier fenceGuard(SearchIndexAclReindexTask task, long leaseMillis) {
        final java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean(false);
        return () -> {
            if (lost.get()) {
                return false;
            }
            boolean held;
            try {
                held = renewLeaseIfNeeded(task, leaseMillis);
            } catch (Exception t) {
                // FAIL-CLOSED: any Exception while renewing the lease means we can no
                // longer PROVE we still hold it (a CouchDB timeout could hide a reclaim).
                // Latch the guard false permanently so the re-drive aborts instead of
                // resuming writes on an unprovable lease. NOTE: only Exception, NOT
                // Throwable — a fatal Error (OutOfMemoryError, ThreadDeath, linkage) must
                // propagate, not be swallowed as a mere "lease lost".
                logger.warn("Lease renewal threw for task {} — fencing closed (lease treated as lost): {}",
                        task != null ? task.getTaskId() : "?", t.toString());
                lost.set(true);
                return false;
            }
            if (!held) {
                lost.set(true);
            }
            return held;
        };
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

    /**
     * Re-open a task that could not even be ATTEMPTED, leaving {@code attempts} untouched (design
     * §5.1 item 1, wiring gate 4).
     *
     * <p>Used when a quarantined dependency blocks the re-drive. {@link #retryLater} counts the
     * drive as a failed attempt, which is right for a re-drive that ran and failed — but a blocked
     * task never ran. Counting it walks {@code attempts} up towards {@code maxAttempts} while
     * nothing is wrong with the task itself, so a subtree blocked for a day comes out of the
     * quarantine one ordinary failure away from terminal-FAILED: the abandonment §5.1 item 1 exists
     * to prevent, merely deferred by a step. It also leaves a misleading attempt count for whoever
     * reads the queue.
     *
     * <p>The scheduler still never lets this become terminal (its quarantine branch returns before
     * the cap check); this keeps the recorded state honest as well.
     */
    public boolean retryLaterWithoutCountingAnAttempt(SearchIndexAclReindexTask task, long backoffMillis) {
        long now = System.currentTimeMillis();
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

    /**
     * Inline settlement of the own-node obligation (§11.4, decision D6): after the synchronous
     * fenced write of a mutation succeeded, consume the task the ACK just created — otherwise every
     * applyAcl costs one redundant queue re-drive.
     *
     * <p>Consumes ONLY when it is safe to: the task must exist, must not be LEASED with a live
     * lease (a poller owns it; its re-drive is idempotent and performs the terminus itself), and its
     * obligation must be COVERED ({@code minRequiredEpoch <= satisfiedEpoch} — a concurrent newer
     * mutation may have merged a higher obligation, which this write did not satisfy). The delete is
     * the same {@code _rev} CAS as {@link #complete}; losing it means a newer event superseded us
     * and the queue owns convergence.
     *
     * @return true if the obligation was consumed (or none existed); false if it must be left to
     *         the poller.
     */
    public boolean settleIfCovered(String repositoryId, String objectId, long satisfiedEpoch) {
        String docId = SearchIndexAclReindexTask.deterministicId(
                repositoryId, objectId, SearchIndexAclReindexTask.Operation.ACL_REINDEX);
        SearchIndexAclReindexTask t;
        try {
            t = getByCouchId(docId);
        } catch (CorruptReconcileTaskException e) {
            return false; // contained elsewhere; never consume what cannot be read
        }
        if (t == null) {
            return true; // nothing outstanding
        }
        if (SearchIndexAclReindexTask.Status.LEASED.equals(t.getStatus())
                && t.getLeaseExpiresAt() > System.currentTimeMillis()) {
            return false; // a poller is mid-re-drive — its terminus wins
        }
        if (t.getMinRequiredEpoch() > satisfiedEpoch) {
            return false; // a HIGHER obligation is pending — this write did not cover it
        }
        return deleteCas(t);
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

            // Corrupt entries are EXCLUDED from claim/list, so without this they would be invisible
            // — the queue would look healthy while a damaged obligation sat there forever. A
            // non-zero value is an operator action: GET/DELETE .../reconcile/corrupt.
            m.put("corrupt", listCorrupt(METRICS_CAP).size());

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

    /**
     * Deserialize a page, CONTAINING corruption instead of propagating it.
     *
     * <p>Increment 7a made {@link #toTask} throw on a corrupt {@code minRequiredEpoch} rather than
     * return null, so a damaged obligation could not masquerade as a missing one. But every read
     * path — {@code list}, {@code claimDue}, {@code metrics}, {@code getByTaskId} and therefore the
     * admin retry/delete — funnels through here, so ONE corrupt document took the whole queue down
     * AND removed the operator's only means of deleting it: an unrecoverable stall (wiring gate 4
     * absorbed this residual).
     *
     * <p>The fix is containment, NOT silence. A corrupt document is skipped for EXECUTION (it must
     * never be claimed: its obligation is unknown, so an ACK would be meaningless) but stays fully
     * visible through {@link #listCorrupt} and the {@code corrupt} metric, and is removable via
     * {@link #deleteCorruptByDocId}. Nothing vanishes — which was the whole point of 7a.
     */
    private List<SearchIndexAclReindexTask> toTasks(FindResult r) {
        List<SearchIndexAclReindexTask> out = new ArrayList<>();
        if (r == null || r.getDocs() == null) return out;
        for (Document d : r.getDocs()) {
            SearchIndexAclReindexTask t;
            try {
                t = toTask(d);
            } catch (CorruptReconcileTaskException e) {
                if (corruptDocIdsSeen.add(d.getId())) {
                    logger.warn("Reconcile task {} is CORRUPT and is being skipped: {} — it is listed "
                            + "under GET /v1/admin/search-index/reconcile/corrupt and removable with "
                            + "DELETE .../corrupt/{}; the rest of the queue keeps draining",
                            d.getId(), e.getMessage(), d.getId());
                }
                continue;
            }
            if (t != null) {
                // A document that deserializes again has been repaired behind our back (an operator
                // editing CouchDB directly, not via the delete route) — forget it, so a LATER
                // re-corruption of the same id WARNs instead of being deduped against the old one.
                if (!corruptDocIdsSeen.isEmpty()) corruptDocIdsSeen.remove(d.getId());
                out.add(t);
            }
        }
        return out;
    }

    /** One corrupt queue document, read RAW (Jackson is exactly what could not be trusted here). */
    public static final class CorruptTaskRef {
        private String docId;
        private String taskId;
        private String repositoryId;
        private String objectId;
        private String reason;

        public String getDocId() { return docId; }
        public String getTaskId() { return taskId; }
        public String getRepositoryId() { return repositoryId; }
        public String getObjectId() { return objectId; }
        public String getReason() { return reason; }
    }

    /**
     * The corrupt queue documents, with enough raw context to act on them: which object the task was
     * for, and why it is corrupt. Read WITHOUT Jackson — a value that broke deserialization must not
     * be run through the same deserializer to be reported.
     *
     * <p>Removing one is {@link #deleteCorruptByDocId}. Deleting a task only means "this refresh
     * will not be retried automatically"; the object's readers are then brought up to date by an
     * ordinary re-index of {@code objectId}, which is why that id is reported.
     *
     * <p><b>Bounded like {@link #list}:</b> it examines the first {@code limit} queue documents
     * (capped at {@value #LIST_LIMIT_CAP}), so on a queue larger than that a corrupt entry past the
     * cap is not reported here — the same caveat {@code countsCappedAt} already states for the
     * status counts. A deep backlog is itself the thing to fix first.
     */
    public List<CorruptTaskRef> listCorrupt(int limit) {
        int capped = Math.min(Math.max(1, limit), LIST_LIMIT_CAP);
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        FindResult r = cloudant.postFind(new PostFindOptions.Builder()
                .db(db).selector(Map.of("type", SearchIndexAclReindexTask.DOC_TYPE))
                .limit(capped).build()).execute().getResult();
        List<CorruptTaskRef> out = new ArrayList<>();
        if (r == null || r.getDocs() == null) return out;
        for (Document d : r.getDocs()) {
            try {
                toTask(d);
            } catch (CorruptReconcileTaskException e) {
                CorruptTaskRef ref = new CorruptTaskRef();
                ref.docId = d.getId();
                Map<String, Object> p = d.getProperties();
                ref.taskId = str(p, "taskId");
                ref.repositoryId = str(p, "repositoryId");
                ref.objectId = str(p, "objectId");
                ref.reason = e.getMessage();
                out.add(ref);
            } catch (RuntimeException ignore) {
                // Any OTHER deserialization failure is already handled (and logged) inside toTask,
                // which returns null for it; nothing reaches here. Defensive only.
            }
        }
        return out;
    }

    /**
     * Delete a CORRUPT queue document by its CouchDB {@code _id} — the escape hatch the ordinary
     * {@code taskId}-addressed delete cannot provide, because addressing by {@code taskId} requires
     * deserializing the very document that will not deserialize.
     *
     * <p>Refuses to delete a HEALTHY document: this is a repair tool, not a second delete API, and
     * an operator pointing it at a live task would silently drop a pending obligation. Use the
     * {@code taskId} delete for those (it has the LEASED protection this one cannot apply).
     *
     * @return true if a corrupt document was deleted; false if it is absent, or healthy (refused).
     */
    public boolean deleteCorruptByDocId(String docId) {
        if (docId == null || docId.isBlank()) return false;
        CloudantClientWrapper client = getConfClient();
        Cloudant cloudant = client.getClient();
        String db = client.getDatabaseName();
        Document doc;
        try {
            doc = cloudant.getDocument(new GetDocumentOptions.Builder()
                    .db(db).docId(docId).build()).execute().getResult();
        } catch (NotFoundException e) {
            return false;
        }
        if (doc == null || !SearchIndexAclReindexTask.DOC_TYPE.equals(
                str(doc.getProperties(), "type"))) {
            return false; // not a reconcile task — never delete an unrelated document
        }
        try {
            toTask(doc);
            return false; // healthy — refuse
        } catch (CorruptReconcileTaskException expected) {
            // corrupt, as claimed — proceed
        } catch (RuntimeException other) {
            return false;
        }
        try {
            var result = cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                    .db(db).docId(docId).rev(doc.getRev()).build()).execute().getResult();
            boolean ok = result != null && result.isOk();
            if (ok) {
                corruptDocIdsSeen.remove(docId);
                logger.info("Deleted CORRUPT reconcile task {} on admin request — re-index object {} "
                        + "to bring its readers up to date", docId, str(doc.getProperties(), "objectId"));
            }
            return ok;
        } catch (ConflictException e) {
            return false;
        }
    }

    private static String str(Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v instanceof String ? (String) v : (v == null ? null : String.valueOf(v));
    }

    /** Corrupt ids already logged, so a stalled document WARNs once rather than every poll. */
    private final java.util.Set<String> corruptDocIdsSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The stored {@code minRequiredEpoch}, validated (increment 7a).
     *
     * <p>ABSENT or explicit null is {@code 0} — a v1 task, whose obligation is best-effort. PRESENT
     * but non-numeric, fractional, out of range or negative is CORRUPTION and is rejected: flattening
     * it to 0 would confuse a damaged obligation with a missing one, and the ACK would then treat a
     * corrupt task exactly like a legitimately old one.
     */
    private static void requireValidMinRequiredEpoch(String docId, Map<String, Object> props) {
        if (!props.containsKey(FIELD_MIN_REQUIRED_EPOCH)) {
            return; // v1 task
        }
        Object v = props.get(FIELD_MIN_REQUIRED_EPOCH);
        if (v == null) {
            props.remove(FIELD_MIN_REQUIRED_EPOCH); // explicit null == absent == 0
            return;
        }
        if (!(v instanceof Number)) {
            throw new CorruptReconcileTaskException("reconcile task " + docId + " has a non-numeric "
                    + FIELD_MIN_REQUIRED_EPOCH + ": " + v);
        }
        long epoch;
        try {
            epoch = new java.math.BigDecimal(v.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new CorruptReconcileTaskException("reconcile task " + docId + " has a non-integral / "
                    + "out-of-range " + FIELD_MIN_REQUIRED_EPOCH + ": " + v);
        }
        if (epoch < 0) {
            throw new CorruptReconcileTaskException("reconcile task " + docId + " has a negative "
                    + FIELD_MIN_REQUIRED_EPOCH + ": " + epoch);
        }
    }

    private SearchIndexAclReindexTask toTask(Document doc) {
        if (doc == null) return null;
        try {
            Map<String, Object> props = new HashMap<>(doc.getProperties());
            props.remove("_id");
            props.remove("_rev");
            props.remove("type");
            // Increment 7a: validate BEFORE Jackson, which would happily coerce 1.5 or "3" and hide
            // corruption as a plausible obligation. Absent / null is 0 (a v1 task); anything present
            // must be a non-negative integer.
            requireValidMinRequiredEpoch(doc.getId(), props);
            SearchIndexAclReindexTask t = MAPPER.convertValue(props, SearchIndexAclReindexTask.class);
            t.setCouchId(doc.getId());
            t.setCouchRev(doc.getRev());
            return t;
        } catch (CorruptReconcileTaskException e) {
            // NOT swallowed HERE: a corrupt epoch obligation must surface. Returning null would make
            // the task VANISH from list / claim / metrics — the damaged obligation would look like
            // "no task at all", which is strictly worse than the flattening this validation exists to
            // prevent. Found while writing the test for it (increment 7a). {@link #toTasks} contains
            // it for page reads (so one bad document cannot stall the queue) and re-surfaces it
            // through listCorrupt / the corrupt metric; {@link #tryEnqueue} still lets it escape,
            // because enqueuing OVER a damaged obligation for that same object is not containable.
            throw e;
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
