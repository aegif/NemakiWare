package jp.aegif.nemaki.rest.purview.journal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cron-based scheduler for automatic purge of terminal lineage events.
 *
 * <p>Follows the same reconcile-based pattern as
 * {@link jp.aegif.nemaki.archive.RetentionScheduler}: a 60-second polling
 * loop reads the current cron from {@link LineageConfig} and reschedules
 * if it changes.
 *
 * <p>Only active when {@code lineage.mode=journaled} and
 * {@code lineage.purge.cron} is a valid cron expression.
 */
@Component
public class LineagePurgeScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LineagePurgeScheduler.class);
    private static final int CONFIG_CHECK_INTERVAL_SECONDS = 60;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired
    private LineageJournalStore journalStore;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> purgeTask;
    private volatile String activeCron;
    private final AtomicLong generation = new AtomicLong(0);

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "LineagePurgeScheduler");
            t.setDaemon(true);
            return t;
        });

        reconcileSchedule();

        scheduler.scheduleWithFixedDelay(
                this::reconcileSchedule,
                CONFIG_CHECK_INTERVAL_SECONDS,
                CONFIG_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        logger.info("Lineage purge scheduler initialized (activeCron={})", activeCron);
    }

    void reconcileSchedule() {
        try {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }

            // Purge only makes sense in JOURNALED mode
            String currentCron = null;
            if (lineageConfig.getMode() == LineageMode.JOURNALED) {
                currentCron = resolveValidCron();
            }

            if (!Objects.equals(currentCron, activeCron)) {
                cancelTask();
                long gen = generation.incrementAndGet();

                if (currentCron == null) {
                    if (activeCron != null) {
                        logger.info("Lineage purge schedule stopped (was: {})", activeCron);
                    }
                    activeCron = null;
                } else {
                    logger.info("Lineage purge schedule updated: {} -> {}", activeCron, currentCron);
                    activeCron = currentCron;
                    scheduleNextPurge(currentCron, gen);
                }
            }
        } catch (Exception e) {
            logger.warn("Error during lineage purge schedule reconciliation, will retry on next poll: {}", e.getMessage());
        }
    }

    private String resolveValidCron() {
        String cron = lineageConfig.getPurgeCron();
        if (cron == null || cron.isBlank()) {
            return null;
        }
        cron = cron.trim();
        if (!CronExpression.isValidExpression(cron)) {
            logger.warn("Invalid lineage purge cron expression: {}", cron);
            return null;
        }
        return cron;
    }

    private void scheduleNextPurge(String cronExpression, long gen) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.next(now);

        if (next == null) {
            logger.warn("Could not determine next lineage purge time for cron: {}", cronExpression);
            return;
        }

        long delayMillis = Duration.between(now, next).toMillis();

        try {
            purgeTask = scheduler.schedule(() -> {
                try {
                    executePurge();
                } finally {
                    try {
                        if (generation.get() != gen) {
                            logger.debug("Lineage purge generation changed, not re-arming");
                            return;
                        }
                        if (lineageConfig.getMode() == LineageMode.JOURNALED) {
                            String effectiveCron = resolveValidCron();
                            if (effectiveCron != null) {
                                activeCron = effectiveCron;
                                scheduleNextPurge(effectiveCron, gen);
                            } else {
                                activeCron = null;
                                logger.info("Lineage purge cron cleared after execution");
                            }
                        } else {
                            activeCron = null;
                        }
                    } catch (Exception e) {
                        logger.warn("Error re-arming lineage purge schedule, next poll will recover: {}", e.getMessage());
                    }
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            logger.debug("Next lineage purge scheduled for: {} (delay: {}ms)", next, delayMillis);
        } catch (RejectedExecutionException e) {
            logger.debug("Lineage purge schedule rejected (scheduler shutting down)");
        }
    }

    private void executePurge() {
        try {
            int days = lineageConfig.getRetentionDays();
            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            int purged = journalStore.purgeOlderThan(cutoff);
            logger.info("Lineage purge completed: {} events purged (retention={} days)", purged, days);
        } catch (Exception e) {
            logger.error("Lineage purge failed: {}", e.getMessage(), e);
        }
    }

    private void cancelTask() {
        if (purgeTask != null) {
            purgeTask.cancel(false);
            purgeTask = null;
        }
    }

    @PreDestroy
    public void destroy() {
        cancelTask();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("Lineage purge scheduler stopped");
    }
}
