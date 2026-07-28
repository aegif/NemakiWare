package jp.aegif.nemaki.epoch;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Leader-gated crash-recovery loop for the ACL-epoch outbox (design §11.7, approved decision D4).
 *
 * <p>Runs {@link AclEpochFinalizationService#scan} per main repository on a LONG period. Crash
 * recovery must not depend on an operator noticing: a JVM that dies between an ACL mutation\u0027s
 * Phase-1 commit and its finalize/ACK leaves durable state that only a sweep advances. The
 * increment-11 admin endpoint remains the on-demand entry; this is the unattended floor.
 *
 * <p><b>Runs ONLY when the wiring flag is ON.</b> With the flag off, no production path creates
 * epoch state, so a sweep could only ever find test residue — and the flag-off posture must stay
 * bit-identical pre-epoch behavior (§11.8). Leader-gated exactly like the reconciliation poller so
 * multiple replicas do not sweep concurrently (safe, but wasteful and noisy).
 *
 * <p>Uses a PLATFORM scheduler thread ({@code ScheduledExecutorService} is not virtual-thread
 * compatible per the project constraint); each sweep is bounded by the per-pass budget.
 */
public class AclEpochScanScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AclEpochScanScheduler.class);
    private static final String LEADER_ROLE = "acl-epoch-scan";
    private static final long DEFAULT_INTERVAL_SECONDS = 300;
    private static final int DEFAULT_MAX_DOCS_PER_PASS = 500;

    private AclEpochFinalizationService finalizationService;
    private PropertyManager propertyManager;
    private LeaderElection leaderElection;
    private RepositoryInfoMap repositoryInfoMap;

    private ScheduledExecutorService scheduler;
    private long intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    private int maxDocsPerPass = DEFAULT_MAX_DOCS_PER_PASS;

    public void setFinalizationService(AclEpochFinalizationService s) { this.finalizationService = s; }
    public void setPropertyManager(PropertyManager p) { this.propertyManager = p; }
    public void setLeaderElection(LeaderElection l) { this.leaderElection = l; }
    public void setRepositoryInfoMap(RepositoryInfoMap m) { this.repositoryInfoMap = m; }

    boolean wiringEnabled() {
        try {
            return propertyManager != null
                    && propertyManager.readBoolean(PropertyKey.ACL_EPOCH_WIRING_ENABLED);
        } catch (Exception e) {
            return false;
        }
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        if (!wiringEnabled()) {
            logger.info("ACL-epoch scan scheduler NOT started — wiring flag is off (§11.8 default)");
            return;
        }
        if (finalizationService == null || repositoryInfoMap == null) {
            logger.warn("ACL-epoch scan scheduler NOT started — finalizationService/repositoryInfoMap unwired");
            return;
        }
        intervalSeconds = readPositiveLong(PropertyKey.ACL_EPOCH_SCAN_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS);
        maxDocsPerPass = (int) Math.min(Integer.MAX_VALUE,
                readPositiveLong(PropertyKey.ACL_EPOCH_SCAN_MAX_DOCS_PER_PASS, DEFAULT_MAX_DOCS_PER_PASS));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acl-epoch-scan");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("ACL-epoch scan scheduler started (interval={}s, maxDocsPerPass={}, leaderElection={})",
                intervalSeconds, maxDocsPerPass,
                (leaderElection != null && leaderElection.isEnabled()) ? "enabled" : "disabled");
    }

    /** Package-private probe for the unit test: is a sweep thread scheduled? */
    boolean isRunning() {
        return scheduler != null;
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Package-private for the unit test. */
    void poll() {
        if (!wiringEnabled()) {
            // Defence in depth on top of start()'s gate: PropertyManager resolves system
            // properties and ENV on every read, so a deployment that flips the flag OFF at
            // runtime (or a future caller invoking poll() directly) must not keep sweeping.
            return;
        }
        if (leaderElection != null && leaderElection.isEnabled() && !leaderElection.isLeader(LEADER_ROLE)) {
            return; // only the leader sweeps the shared outbox
        }
        for (String repositoryId : repositoryInfoMap.getMainRepositoryKeys()) {
            try {
                AclEpochFinalizationService.ScanSummary s =
                        finalizationService.scan(repositoryId, maxDocsPerPass);
                if (s.scanned > 0 || s.more || !s.errors.isEmpty()) {
                    logger.info("ACL-epoch sweep '{}': scanned={}, finalized={}, acked={}, quarantined={}, "
                                    + "contended={}, more={}", repositoryId, s.scanned, s.finalized,
                            s.acked, s.quarantined, s.contended, s.more);
                }
            } catch (Exception e) {
                // Per-repository isolation: one broken repository must not stop the sweep of the
                // others; the next cycle retries (progress is durable).
                logger.warn("ACL-epoch sweep failed for '{}': {}", repositoryId, e.getMessage());
            }
        }
    }

    private long readPositiveLong(String key, long dflt) {
        try {
            String v = propertyManager.readValue(key);
            long parsed = Long.parseLong(v.trim());
            if (parsed > 0) {
                return parsed;
            }
            logger.warn("{} must be positive; keeping default {}", key, dflt);
        } catch (Exception e) {
            // absent/unreadable → default
        }
        return dflt;
    }
}
