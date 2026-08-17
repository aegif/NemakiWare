package jp.aegif.nemaki.reconcile;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A durable reconciliation-queue entry recording that an object's search-index
 * ACL (the Solr {@code readers} field and its RAG block, plus the readers of the
 * relationships that reference it) needs to be re-computed because an earlier
 * asynchronous refresh failed.
 *
 * <p>Persisted in {@code nemaki_conf} under a DETERMINISTIC CouchDB {@code _id}
 * ({@code search-index-acl-reconcile::{repositoryId}::{objectId}}) so that
 * concurrent enqueues for the same object collapse to one document (create
 * conflicts resolve to an in-place update), and so that every state transition is
 * an atomic compare-and-swap on the document's {@code _rev}.
 *
 * <p>Lifecycle: {@code PENDING} → (claimed by a poller) {@code LEASED} →
 * re-driven; a clean re-drive deletes the document, a failure returns it to
 * {@code PENDING} with backoff, and after the attempt cap it becomes
 * {@code FAILED} (kept for operator inspection). A NEW failure event for an object
 * already {@code LEASED} bumps {@code generation} and flips it back to
 * {@code PENDING}, which invalidates the in-flight lease's rev — so the poller's
 * CAS delete fails and the fresh failure survives.
 *
 * <p>Time fields are epoch milliseconds (not ISO strings) so Mango {@code $lte}
 * range + sort on {@code nextAttemptAt} / {@code leaseExpiresAt} is exact.
 */
public class SearchIndexAclReindexTask {

    public static final String DOC_TYPE = "searchIndexAclReindexTask";
    public static final String ID_PREFIX = "search-index-acl-reconcile::";
    /**
     * Separate deterministic-id namespace for {@link Operation#RAG_PURGE} tasks.
     * ACL_REINDEX and RAG_PURGE are INDEPENDENT obligations (an ACL reindex covers
     * CMIS readers + descendants + relationships; a purge covers only the RAG
     * block) — sharing one document with a precedence rule would let a completed
     * purge delete an unfinished ACL obligation. The same object may hold BOTH
     * tasks; each completes on its own.
     */
    public static final String PURGE_ID_PREFIX = "search-index-rag-purge::";

    public static final class Status {
        public static final String PENDING = "PENDING";
        public static final String LEASED = "LEASED";
        public static final String FAILED = "FAILED";
        private Status() {}
    }

    public static final class Reason {
        public static final String TRAVERSAL_FAILURE = "TRAVERSAL_FAILURE";
        public static final String NODE_REFRESH_FAILURE = "NODE_REFRESH_FAILURE";
        public static final String RELATIONSHIP_REFRESH_FAILURE = "RELATIONSHIP_REFRESH_FAILURE";
        public static final String INDEX_WRITE_FAILURE = "INDEX_WRITE_FAILURE";
        public static final String CACHE_EVICTION_FAILURE = "CACHE_EVICTION_FAILURE";
        public static final String PWC_PURGE_FAILURE = "PWC_PURGE_FAILURE";
        /**
         * Not a failure at all: the ACL-epoch outbox ACK establishing a durable obligation for a
         * mutation that SUCCEEDED (increment 7b). It used to be recorded as
         * {@code INDEX_WRITE_FAILURE}, which sent anyone triaging the queue looking for a Solr
         * problem that never happened. The field is free-form, so existing tasks keep their values.
         */
        public static final String OUTBOX_ACK = "OUTBOX_ACK";
        private Reason() {}
    }

    /**
     * What the re-drive must DO for this task. The scheduler and the admin manual
     * retry DISPATCH on this (they must not blindly ACL-reindex: an ACL re-index of
     * a PWC would leave — or even refresh — the very RAG block a purge task exists
     * to remove).
     */
    public static final class Operation {
        /** Recompute + rewrite the object's search-index ACL (the default). */
        public static final String ACL_REINDEX = "ACL_REINDEX";
        /**
         * Remove the object's RAG block and verify it is gone (Private Working
         * Copies must never be RAG-indexed; a failed best-effort delete becomes
         * this durable task).
         */
        public static final String RAG_PURGE = "RAG_PURGE";
        private Operation() {}
    }

    /** Opaque handle for the admin API (stable across updates); the CouchDB _id is deterministic. */
    private String taskId;
    private String repositoryId;
    private String objectId;
    private String reason;
    /**
     * {@link Operation} for the re-drive. Absent on pre-existing documents →
     * treated as {@link Operation#ACL_REINDEX} (backward compatible). On a merge
     * (enqueue onto an existing task for the same object) {@code RAG_PURGE} takes
     * precedence — a purge must not be downgraded back to an ACL reindex that
     * would keep the block alive.
     */
    private String operation;
    private int attempts;
    private String status = Status.PENDING;
    /** Bumped on every new enqueue event; the ACK only succeeds if the rev (hence generation) is unchanged. */
    private long generation;
    /**
     * The HIGHEST ACL epoch this task is obliged to reconcile (design §3, increment 7a).
     *
     * <p>The epoch outbox ACK is <b>not</b> "a task exists" — it is
     * {@code minRequiredEpoch >= finalizedEpoch}. Without this field an ACK could mark a document
     * {@code RECONCILE_ENQUEUED} while the queued obligation predates the epoch just finalized, and
     * the miss would then be invisible: the scanner counts the document as enqueued and never looks
     * again. That is the exact failure wiring gate 1 exists to prevent.
     *
     * <p>Merged MONOTONICALLY on every enqueue ({@code max(existing, requested)}), so a later
     * best-effort refresh can never LOWER an obligation a finalized epoch already raised.
     *
     * <p><b>Absent / null reads as 0</b> — v1 tasks predate the field and carry best-effort
     * obligations only. That is deliberately fail-closed for the ACK: {@code 0 >= finalizedEpoch} is
     * false for every real epoch (the counter's first allocation is 1), so a v1 task can never
     * satisfy an ACK, and the outbox marker stays until a fresh enqueue raises it.
     *
     * <p>A PRESENT but non-integral / negative value is NOT flattened to 0 — that would confuse
     * corruption with absence. It is rejected on read.
     *
     * <p><b>{@code ACL_REINDEX} only.</b> A {@code RAG_PURGE} task carries no epoch obligation: a
     * purge is an unconditional deletion of a RAG block, not a reconciliation to a point in the ACL
     * timeline, and the two live under separate deterministic ids with no cross-operation merge. The
     * field is never written for a purge and never compared for one.
     */
    private long minRequiredEpoch;
    /** Epoch millis the entry is next eligible for a retry (backoff). */
    private long nextAttemptAt;
    /** Lease holder node id and expiry (epoch millis) while {@code LEASED}; a crashed holder's lease expires and is reclaimable. */
    private String leaseOwner;
    private long leaseExpiresAt;
    private long createdAt;
    private long updatedAt;
    private String lastError;

    /** CouchDB {@code _id} — deterministic, carried for CAS. Not a stored data field. */
    @JsonIgnore
    private String couchId;
    /** CouchDB {@code _rev} captured at read/claim time — the CAS token. Not a stored data field. */
    @JsonIgnore
    private String couchRev;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    /** The effective operation: absent/unknown (pre-existing docs) = ACL_REINDEX. */
    @JsonIgnore
    public String getEffectiveOperation() {
        return Operation.RAG_PURGE.equals(operation) ? Operation.RAG_PURGE : Operation.ACL_REINDEX;
    }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }

    public long getMinRequiredEpoch() { return minRequiredEpoch; }
    public void setMinRequiredEpoch(long minRequiredEpoch) { this.minRequiredEpoch = minRequiredEpoch; }

    public long getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(long nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public long getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(long leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    @JsonIgnore
    public String getCouchId() { return couchId; }
    public void setCouchId(String couchId) { this.couchId = couchId; }

    @JsonIgnore
    public String getCouchRev() { return couchRev; }
    public void setCouchRev(String couchRev) { this.couchRev = couchRev; }

    /**
     * The deterministic CouchDB {@code _id} for a (repository, object) pair —
     * ACL_REINDEX namespace (unchanged; pre-existing queue documents live here).
     * The objectId is percent-ish encoded (only {@code :} and {@code %}) so the
     * {@code ::} separator is unambiguous even if an id ever contained a colon.
     */
    public static String deterministicId(String repositoryId, String objectId) {
        return ID_PREFIX + repositoryId + "::" + encode(objectId);
    }

    /**
     * The deterministic {@code _id} for the given OPERATION. RAG_PURGE tasks live in
     * their own namespace so an ACL_REINDEX obligation and a RAG_PURGE obligation on
     * the same object are separate documents that complete independently.
     */
    public static String deterministicId(String repositoryId, String objectId, String operation) {
        if (Operation.RAG_PURGE.equals(operation)) {
            return PURGE_ID_PREFIX + repositoryId + "::" + encode(objectId);
        }
        return deterministicId(repositoryId, objectId);
    }

    private static String encode(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace(":", "%3A");
    }
}
