package jp.aegif.nemaki.reconcile;

/**
 * A durable reconciliation-queue entry recording that an object's search-index
 * ACL (the Solr {@code readers} field and its RAG block, plus the readers of the
 * relationships that reference it) needs to be re-computed because an earlier
 * asynchronous refresh failed.
 *
 * <p>The asynchronous ACL propagation in {@code AclServiceImpl}
 * (applyAcl / move) is best-effort: a per-node re-index or a reverse-lookup of
 * relationships can throw (a transient CouchDB/Solr failure), and an async Solr
 * write can permanently fail after its bounded retries. Left only as a WARN,
 * such a failure would leave the object's {@code readers} stale until the next
 * unrelated ACL touch or a full re-index — which, in the GRANT direction, is a
 * permanent search-invisibility (the in-memory getFiltered can remove a hit Solr
 * returned but never add one Solr excluded). Recording the failed object here
 * lets a scheduled poller re-drive the refresh and lets an operator observe /
 * retry it.
 *
 * <p>Persisted in {@code nemaki_conf} (system-wide, like the ingest job / DLQ
 * records). Deserialized by Jackson, so a public no-arg constructor and
 * getters/setters are required.
 */
public class SearchIndexAclReindexTask {

    public static final String DOC_TYPE = "searchIndexAclReindexTask";

    /** Queue entry lifecycle. */
    public static final class Status {
        public static final String PENDING = "PENDING";
        public static final String FAILED = "FAILED";
        private Status() {}
    }

    /** Why the object was enqueued (for operator triage; not load-bearing). */
    public static final class Reason {
        public static final String TRAVERSAL_FAILURE = "TRAVERSAL_FAILURE";
        public static final String NODE_REFRESH_FAILURE = "NODE_REFRESH_FAILURE";
        public static final String RELATIONSHIP_REFRESH_FAILURE = "RELATIONSHIP_REFRESH_FAILURE";
        public static final String INDEX_WRITE_FAILURE = "INDEX_WRITE_FAILURE";
        private Reason() {}
    }

    private String taskId;
    private String repositoryId;
    private String objectId;
    private String reason;
    private int attempts;
    private String status = Status.PENDING;
    private String lastError;
    private String createdAt;
    private String updatedAt;
    /** ISO-8601 timestamp the entry is next eligible for a retry (backoff). */
    private String nextAttemptAt;

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

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(String nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
}
