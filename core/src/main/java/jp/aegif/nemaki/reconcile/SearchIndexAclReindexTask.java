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
        private Reason() {}
    }

    /** Opaque handle for the admin API (stable across updates); the CouchDB _id is deterministic. */
    private String taskId;
    private String repositoryId;
    private String objectId;
    private String reason;
    private int attempts;
    private String status = Status.PENDING;
    /** Bumped on every new enqueue event; the ACK only succeeds if the rev (hence generation) is unchanged. */
    private long generation;
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

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }

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
     * The deterministic CouchDB {@code _id} for a (repository, object) pair.
     * The objectId is percent-ish encoded (only {@code :} and {@code %}) so the
     * {@code ::} separator is unambiguous even if an id ever contained a colon.
     */
    public static String deterministicId(String repositoryId, String objectId) {
        return ID_PREFIX + repositoryId + "::" + encode(objectId);
    }

    private static String encode(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace(":", "%3A");
    }
}
