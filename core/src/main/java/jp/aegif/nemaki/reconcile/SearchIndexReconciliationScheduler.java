package jp.aegif.nemaki.reconcile;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic poller that drains the {@link SearchIndexReconciliationService} durable
 * queue: for each due {@link SearchIndexAclReindexTask} it re-drives the object's
 * search-index ACL refresh via {@link AclService#reindexSearchIndexAclForObject}.
 * A clean re-drive deletes the task; a failure reserves it for a later retry with
 * backoff; after {@code maxAttempts} it is marked {@code FAILED} and kept for
 * operator inspection (the admin API can retry or delete it).
 *
 * <p>Multi-replica safe: gated by {@link LeaderElection} (only the leader polls)
 * and by CouchDB {@code _rev} optimistic locking on each reservation. XML-wired
 * (serviceContext.xml) with {@code init-method="start"} / {@code destroy-method="stop"}.
 */
public class SearchIndexReconciliationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexReconciliationScheduler.class);

    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 120;
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final int DEFAULT_BATCH = 50;
    private static final long DEFAULT_BASE_BACKOFF_SECONDS = 60;
    private static final long MAX_BACKOFF_SECONDS = 3600;
    private static final String LEADER_ROLE = "search-index-reconciliation";

    private SearchIndexReconciliationService reconciliationService;
    private AclService aclService;
    private LeaderElection leaderElection;
    private PropertyManager propertyManager;

    private long pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    private int batchSize = DEFAULT_BATCH;
    private long baseBackoffSeconds = DEFAULT_BASE_BACKOFF_SECONDS;

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
            pollIntervalSeconds = readLong("nemakiware.searchindex.reconcile.pollIntervalSeconds", DEFAULT_POLL_INTERVAL_SECONDS);
            maxAttempts = (int) readLong("nemakiware.searchindex.reconcile.maxAttempts", DEFAULT_MAX_ATTEMPTS);
            batchSize = (int) readLong("nemakiware.searchindex.reconcile.batchSize", DEFAULT_BATCH);
            baseBackoffSeconds = readLong("nemakiware.searchindex.reconcile.baseBackoffSeconds", DEFAULT_BASE_BACKOFF_SECONDS);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SearchIndexReconcile");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafe,
                pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        logger.info("Search-index reconciliation scheduler started (interval={}s, maxAttempts={}, leaderElection={})",
                pollIntervalSeconds, maxAttempts,
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
        // Multi-replica: only the leader drains the shared queue.
        if (leaderElection != null && leaderElection.isEnabled() && !leaderElection.isLeader(LEADER_ROLE)) {
            logger.debug("Not the leader for '{}' — skipping reconciliation poll", LEADER_ROLE);
            return;
        }
        List<SearchIndexAclReindexTask> due = reconciliationService.listDue(batchSize);
        if (due.isEmpty()) {
            return;
        }
        int reconciled = 0, retried = 0, failed = 0;
        for (SearchIndexAclReindexTask task : due) {
            // Reserve via _rev optimistic lock (bump attempts + push nextAttemptAt out)
            // BEFORE the re-drive, so a crash mid-reindex still leaves the task due
            // later and two replicas cannot both claim it.
            long backoff = backoffMillis(task.getAttempts() + 1);
            if (!reconciliationService.reserveForRetry(task, backoff)) {
                continue; // another replica claimed it
            }
            boolean clean;
            try {
                clean = aclService.reindexSearchIndexAclForObject(task.getRepositoryId(), task.getObjectId());
            } catch (Exception e) {
                logger.warn("Reconcile re-drive threw for {} / {}: {}",
                        task.getRepositoryId(), task.getObjectId(), e.getMessage());
                clean = false;
            }
            if (clean) {
                reconciliationService.delete(task.getTaskId());
                reconciled++;
            } else if (task.getAttempts() >= maxAttempts) {
                reconciliationService.markFailed(task,
                        "Exhausted " + maxAttempts + " reconciliation attempts");
                failed++;
            } else {
                retried++; // reservation already pushed nextAttemptAt out
            }
        }
        if (reconciled + retried + failed > 0) {
            logger.info("Search-index reconciliation poll: reconciled={}, retrying={}, failed={} (due={})",
                    reconciled, retried, failed, due.size());
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

    private long readLong(String key, long dflt) {
        try {
            String v = propertyManager.readValue(key);
            if (v != null && !v.isBlank()) return Long.parseLong(v.trim());
        } catch (Exception ignore) {
            // fall through to default
        }
        return dflt;
    }
}
