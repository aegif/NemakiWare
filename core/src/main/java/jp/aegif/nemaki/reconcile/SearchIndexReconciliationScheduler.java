package jp.aegif.nemaki.reconcile;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Leader-gated poller that drains the {@link SearchIndexReconciliationService}
 * queue with confirmed re-drives:
 * <ol>
 *   <li>{@code claimDue} leases a batch of due entries via {@code _rev} CAS
 *       (two replicas cannot claim the same entry);</li>
 *   <li>{@link AclService#reindexSearchIndexAclForObject} re-drives each object
 *       SYNCHRONOUSLY (writes are confirmed, failures are counted) — see that
 *       method's {@code forceSync} contract;</li>
 *   <li>a clean re-drive {@code complete}s (CAS delete, so a failure event that
 *       arrived mid-flight survives); a failure {@code retryLater}s with backoff;
 *       at the attempt cap it is {@code markFailed} and kept for inspection.</li>
 * </ol>
 * XML-wired (serviceContext.xml) with {@code init-method="start"} /
 * {@code destroy-method="stop"}.
 */
public class SearchIndexReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexReconciliationScheduler.class);

    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 120;
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final int DEFAULT_BATCH = 50;
    private static final long DEFAULT_BASE_BACKOFF_SECONDS = 60;
    private static final long DEFAULT_LEASE_SECONDS = 300;
    private static final long MAX_BACKOFF_SECONDS = 3600;
    /** Attempt number whose backoff (capped by MAX_BACKOFF_SECONDS) a never-failing RAG_PURGE settles at. */
    private static final int PURGE_BACKOFF_CAP_ATTEMPT = 10;
    private static final String LEADER_ROLE = "search-index-reconciliation";

    private SearchIndexReconciliationService reconciliationService;
    private AclService aclService;
    private LeaderElection leaderElection;
    private PropertyManager propertyManager;

    private long pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    private int batchSize = DEFAULT_BATCH;
    private long baseBackoffSeconds = DEFAULT_BASE_BACKOFF_SECONDS;
    private long leaseSeconds = DEFAULT_LEASE_SECONDS;
    private String nodeId = "node-" + UUID.randomUUID();

    private volatile ScheduledExecutorService scheduler;

    public void setReconciliationService(SearchIndexReconciliationService s) { this.reconciliationService = s; }
    public void setAclService(AclService s) { this.aclService = s; }
    public void setLeaderElection(LeaderElection l) { this.leaderElection = l; }
    public void setPropertyManager(PropertyManager p) { this.propertyManager = p; }

    public void start() {
        if (scheduler != null) return;
        if (reconciliationService == null || aclService == null) {
            logger.info("Search-index reconciliation scheduler not started (service/aclService unwired)");
            return;
        }
        if (propertyManager != null) {
            // All values must be positive; a non-positive / unparseable override is
            // rejected with a WARN and the default kept (a 0 poll interval would spin,
            // a 0 batch would never drain, a 0 lease would let every claim expire).
            pollIntervalSeconds = readPositiveLong("nemakiware.searchindex.reconcile.pollIntervalSeconds", DEFAULT_POLL_INTERVAL_SECONDS);
            maxAttempts = (int) Math.min(Integer.MAX_VALUE,
                    readPositiveLong("nemakiware.searchindex.reconcile.maxAttempts", DEFAULT_MAX_ATTEMPTS));
            batchSize = (int) Math.min(Integer.MAX_VALUE,
                    readPositiveLong("nemakiware.searchindex.reconcile.batchSize", DEFAULT_BATCH));
            baseBackoffSeconds = readPositiveLong("nemakiware.searchindex.reconcile.baseBackoffSeconds", DEFAULT_BASE_BACKOFF_SECONDS);
            leaseSeconds = readPositiveLong("nemakiware.searchindex.reconcile.leaseSeconds", DEFAULT_LEASE_SECONDS);
        }
        if (leaderElection != null) {
            try {
                String id = leaderElection.getNodeId();
                if (id != null && !id.isBlank()) nodeId = id;
            } catch (Exception ignore) { /* keep generated node id */ }
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SearchIndexReconcile");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafe,
                pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        logger.info("Search-index reconciliation scheduler started (interval={}s, maxAttempts={}, lease={}s, node={}, leaderElection={})",
                pollIntervalSeconds, maxAttempts, leaseSeconds, nodeId,
                (leaderElection != null && leaderElection.isEnabled()) ? "enabled" : "disabled");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            logger.info("Search-index reconciliation scheduler stopped");
        }
    }

    private void pollSafe() {
        try {
            poll();
        } catch (Exception e) {
            logger.warn("Search-index reconciliation poll failed (will retry next interval): {}", e.getMessage());
        }
    }

    /** One poll cycle. Package-private so it can be unit-driven. */
    void poll() {
        if (leaderElection != null && leaderElection.isEnabled() && !leaderElection.isLeader(LEADER_ROLE)) {
            logger.debug("Not the leader for '{}' — skipping reconciliation poll", LEADER_ROLE);
            return;
        }
        List<SearchIndexAclReindexTask> claimed =
                reconciliationService.claimDue(batchSize, nodeId, leaseSeconds * 1000L);
        if (claimed.isEmpty()) {
            return;
        }
        int reconciled = 0, retried = 0, failed = 0;
        for (SearchIndexAclReindexTask task : claimed) {
            boolean clean;
            try {
                // Dispatch on the task's OPERATION — a purge must not be handled by an
                // ACL reindex (which would leave, or even refresh, the very RAG block
                // the purge exists to remove).
                if (SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(task.getEffectiveOperation())) {
                    // Purge is an idempotent delete + verify; no epoch/lease fencing
                    // needed beyond the queue lease (a double delete is harmless).
                    clean = aclService.purgeRagBlockForObject(task.getRepositoryId(), task.getObjectId());
                } else {
                    // Cooperative fencing: the re-drive polls this LATCHED guard before
                    // every index write; it heartbeats/renews the lease and returns false
                    // (permanently, once lost) so a long re-drive that outlives its lease
                    // stops writing instead of clobbering a reclaiming worker's fresher
                    // readers. The admin manual-retry uses the SAME guard.
                    clean = aclService.reindexSearchIndexAclForObject(
                            task.getRepositoryId(), task.getObjectId(),
                            reconciliationService.fenceGuard(task, leaseSeconds * 1000L));
                }
            } catch (Exception e) {
                logger.warn("Reconcile re-drive threw for {} / {}: {}",
                        task.getRepositoryId(), task.getObjectId(), e.getMessage());
                clean = false;
            }
            boolean isPurge = SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(task.getEffectiveOperation());
            if (clean) {
                if (reconciliationService.complete(task)) reconciled++;
                // else: a newer failure event superseded the claim — left PENDING, re-processed later.
            } else if (!isPurge && task.getAttempts() + 1 >= maxAttempts) {
                // This drive is the maxAttempts-th (attempts counts PRIOR failed drives),
                // so give up after exactly maxAttempts re-drives — for ACL_REINDEX only.
                reconciliationService.markFailed(task, "Exhausted " + maxAttempts + " reconciliation attempts");
                failed++;
            } else {
                // A RAG_PURGE NEVER becomes terminal FAILED: a purge that cannot run yet
                // (RAG disabled, Solr down) must resume when the blocker clears, or a
                // stale PWC block (the seed oracle) would silently return. It stays
                // PENDING under CAPPED backoff (attempts stop growing the delay past the
                // cap, but the task is never abandoned).
                long backoff = backoffMillis(isPurge
                        ? Math.min(task.getAttempts() + 1, PURGE_BACKOFF_CAP_ATTEMPT)
                        : task.getAttempts() + 1);
                reconciliationService.retryLater(task, backoff);
                retried++;
            }
        }
        if (reconciled + retried + failed > 0) {
            logger.info("Search-index reconciliation poll: reconciled={}, retrying={}, failed={} (claimed={})",
                    reconciled, retried, failed, claimed.size());
        }
    }

    /** Exponential backoff (base * 2^(attempt-1)) capped at MAX_BACKOFF_SECONDS. */
    private long backoffMillis(int attempt) {
        long secs = baseBackoffSeconds;
        for (int i = 1; i < attempt && secs < MAX_BACKOFF_SECONDS; i++) {
            secs *= 2;
        }
        return Math.min(secs, MAX_BACKOFF_SECONDS) * 1000L;
    }

    /** Read a strictly-positive long; a non-positive or unparseable value keeps the default (with a WARN). */
    private long readPositiveLong(String key, long dflt) {
        try {
            String v = propertyManager.readValue(key);
            if (v != null && !v.isBlank()) {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
                logger.warn("Ignoring non-positive config {}='{}', using default {}", key, v, dflt);
            }
        } catch (Exception e) {
            logger.warn("Ignoring invalid config {} ('{}'), using default {}", key, e.getMessage(), dflt);
        }
        return dflt;
    }
}
