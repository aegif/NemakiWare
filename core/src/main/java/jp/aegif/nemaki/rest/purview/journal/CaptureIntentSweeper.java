/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.journal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.util.PropertyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Turns intents that outlived their deadline into {@code UNRESOLVED}, and applies retention.
 *
 * <h2>Why this runs by default</h2>
 *
 * <p>The obvious thing — copy the purge scheduler — does not work: its cron defaults to empty, so
 * it does not run at all until configured. If this did the same, {@code UNRESOLVED} would never
 * appear and the operator listing would always be empty. An always-empty listing is
 * indistinguishable from "nothing went wrong", which is worse than having no listing (design
 * §6.8-4).
 *
 * <p>It starts on {@code isActive()} — whether the lineage database exists — and deliberately
 * <b>not</b> on whether any repository is in journaled mode. Switching the last one off would
 * otherwise stop the sweeper and freeze every intent still open at that instant.
 *
 * <h2>Why it is not protected by leader election</h2>
 *
 * <p>Leader election is off by default and, when off, grants leadership to every replica — so
 * "the leader guards it" would be a claim that is false in the default configuration. Safety
 * comes from {@code _rev} CAS instead: each transition is idempotent, so several replicas
 * sweeping at once still produce the right answer.
 *
 * <p>Correctness and cost are different questions. In the default configuration N replicas walk
 * the same batch every interval, producing N reads and N−1 rejected writes per row. The batch is
 * bounded and the interval is offset per replica to blunt that; it is not eliminated.
 */
@Component
public class CaptureIntentSweeper {

    private static final Logger logger = LoggerFactory.getLogger(CaptureIntentSweeper.class);

    // The prefix is "capture-boundary", not "capture": lineage.capture.version-events and
    // lineage.capture.generic-relationships already exist and mean "capture these events into
    // lineage", which is a different sense of the word. Sharing a prefix with them would put two
    // unrelated features one keystroke apart in an operator's configuration file.

    /** How long an intent may stay open before it is called unresolved. */
    static final String KEY_STALE_MINUTES = "lineage.capture-boundary.stale.minutes";
    static final int DEFAULT_STALE_MINUTES = 15;

    /** How often to sweep. A different question from the staleness threshold, so a different key. */
    static final String KEY_INTERVAL_MINUTES = "lineage.capture-boundary.sweep.interval.minutes";
    static final int DEFAULT_INTERVAL_MINUTES = 5;

    static final String KEY_BATCH_LIMIT = "lineage.capture-boundary.sweep.batch";
    static final int DEFAULT_BATCH_LIMIT = 200;

    /** Retention for rows the sweeper gave up on. {@code 0} = keep forever, and that is default. */
    static final String KEY_RETENTION_UNRESOLVED = "lineage.capture-boundary.retention.unresolved.days";

    /**
     * Retention for completed rows. {@code 0} = keep forever, and that is the default.
     *
     * <p>Default-unlimited is a decision, not an oversight: these rows are the evidence of when
     * something was ingested, and the product should not delete evidence unless an operator asks
     * it to. The cost of the default is that the lineage database grows with ingest volume —
     * one row per item, permanently — so database size becomes something to watch.
     */
    static final String KEY_RETENTION_CAPTURED = "lineage.capture-boundary.retention.captured.days";

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private LineageJournalStore journalStore;

    @Autowired(required = false)
    private CaptureMaintenanceStore maintenanceStore;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        if (maintenanceStore == null) {
            logger.info("Capture intent sweeper not started: no maintenance store is wired");
            return;
        }
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "CaptureIntentSweeper");
            t.setDaemon(true);
            return t;
        });
        long intervalMinutes = intervalMinutes();
        // Offset the first run per JVM so replicas do not all walk the same batch at the same
        // instant. Derived from the identity hash, which needs no clock and no randomness.
        long jitterSeconds = Math.abs(System.identityHashCode(this) % 60);
        scheduler.scheduleWithFixedDelay(this::runOnce, jitterSeconds,
                intervalMinutes * 60, TimeUnit.SECONDS);
        logger.info("Capture intent sweeper started (every {} min, stale after {} min)",
                intervalMinutes, staleMinutes());
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One pass. Package-private so a test can drive it without waiting for the timer. */
    void runOnce() {
        try {
            if (journalStore != null && !journalStore.isActive()) {
                // No lineage database. Sharing the same verdict rather than probing separately:
                // the existing purge scheduler already probes every 60 seconds and does not cache
                // the NotFoundException, and doubling that would double a permanent probe.
                return;
            }
            int batch = batchLimit();
            long now = System.currentTimeMillis();

            int swept = maintenanceStore.sweepExpiredIntents(
                    now - TimeUnit.MINUTES.toMillis(staleMinutes()), batch);
            if (swept > 0) {
                logger.info("Capture sweeper moved {} intent(s) to UNRESOLVED", swept);
            }

            int unresolvedDays = retentionDays(KEY_RETENTION_UNRESOLVED);
            if (unresolvedDays > 0) {
                int purged = maintenanceStore.purgeUnresolvedOlderThan(
                        now - TimeUnit.DAYS.toMillis(unresolvedDays), batch);
                if (purged > 0) {
                    logger.info("Capture retention deleted {} unresolved row(s)", purged);
                }
            }

            int capturedDays = retentionDays(KEY_RETENTION_CAPTURED);
            if (capturedDays > 0) {
                int purged = maintenanceStore.purgeCapturedOlderThan(
                        now - TimeUnit.DAYS.toMillis(capturedDays), batch);
                if (purged > 0) {
                    logger.info("Capture retention deleted {} captured row(s)", purged);
                }
            }
        } catch (Exception e) {
            // A sweep that throws must not kill the scheduled task: scheduleWithFixedDelay stops
            // rescheduling after an uncaught exception, and nothing would report that it had.
            logger.error("Capture sweeper pass failed: {}", e.toString(), e);
        }
    }

    /**
     * The effective stale threshold: never below the scheduler's fetch timeout + 5.
     *
     * <p>The defaults used to contradict each other — sweep at 15 minutes, fetch timeout at 30
     * — so a fetch in its second half could have its intent judged dead while it was still
     * writing (capture-outbox §6.9-3, M5). Coupling the floor to the SAME config the scheduler
     * reads closes that in every configuration, not just tuned ones; an operator setting the
     * stale threshold higher is still honored.
     *
     * <p>What this is NOT (P1-1(e) §4, Codex H6): a liveness guarantee. The scheduler's timeout
     * only interrupts — a blocking fetch can outlive it — and the webhook / IMAP IDLE paths run
     * without a timeout wrapper at all. A zombie pass can still be swept and write afterwards;
     * the runbook states the residual, and the verify endpoint's "swept-before-baseline does
     * not downgrade" rule inherits it.
     */
    int staleMinutes() {
        int configured = positiveInt(KEY_STALE_MINUTES, DEFAULT_STALE_MINUTES);
        int fetchTimeoutFloor = positiveInt("ingest.scheduler.fetchTimeoutMinutes", 30) + 5;
        return Math.max(configured, fetchTimeoutFloor);
    }

    int intervalMinutes() {
        return positiveInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES);
    }

    int batchLimit() {
        return positiveInt(KEY_BATCH_LIMIT, DEFAULT_BATCH_LIMIT);
    }

    /** {@code 0} or absent means keep forever. Negative values are treated as absent. */
    int retentionDays(String key) {
        if (propertyManager == null) {
            return 0;
        }
        try {
            String raw = propertyManager.readValue(key);
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            int days = Integer.parseInt(raw.trim());
            return Math.max(days, 0);
        } catch (Exception e) {
            // An unreadable retention setting must not be read as "delete on some other
            // schedule". Keeping everything is the safe direction for evidence.
            logger.warn("Could not read {}: {} — keeping rows indefinitely", key, e.toString());
            return 0;
        }
    }

    private int positiveInt(String key, int fallback) {
        if (propertyManager == null) {
            return fallback;
        }
        try {
            String raw = propertyManager.readValue(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // Setters for tests and for XML wiring.

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public void setJournalStore(LineageJournalStore journalStore) {
        this.journalStore = journalStore;
    }

    public void setMaintenanceStore(CaptureMaintenanceStore maintenanceStore) {
        this.maintenanceStore = maintenanceStore;
    }
}
