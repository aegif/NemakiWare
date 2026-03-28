package jp.aegif.nemaki.rest.purview.journal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background loop that projects PENDING lineage events from the journal
 * to configured target sinks (e.g. Purview).
 *
 * <p>Follows the same pattern as {@link LineagePurgeScheduler}: platform
 * threads for scheduling, with a reconcile loop for config changes.
 *
 * <p>Only active when the journal store is active (i.e. the journal DB
 * exists and at least one target sink is configured and available).
 */
@Component
public class LineageProjectionLoop {

    private static final Logger logger = LoggerFactory.getLogger(LineageProjectionLoop.class);
    private static final int CONFIG_CHECK_INTERVAL_SECONDS = 60;
    /** Estimated average event document size in bytes (JSON with snapshot attributes). */
    private static final long ESTIMATED_EVENT_SIZE_BYTES = 2048;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired(required = false)
    private List<LineageTargetSink> targetSinks;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "LineageProjectionLoop");
            t.setDaemon(true);
            return t;
        });

        // Main polling loop
        int pollInterval = lineageConfig.getProjectionPollIntervalSeconds();
        scheduler.scheduleWithFixedDelay(
                this::pollAndProject,
                pollInterval,
                pollInterval,
                TimeUnit.SECONDS);

        // Config change detection
        scheduler.scheduleWithFixedDelay(
                this::logConfigStatus,
                CONFIG_CHECK_INTERVAL_SECONDS,
                CONFIG_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        logger.info("Lineage projection loop initialized (pollInterval={}s)", pollInterval);
    }

    /**
     * Main polling and projection cycle.
     *
     * <p>For each configured target that is available:
     * <ol>
     *   <li>Enforce backlog thresholds (auto-discard overflows)</li>
     *   <li>Reap stale PROJECTING events</li>
     *   <li>Project PENDING events</li>
     *   <li>Retry FAILED events</li>
     * </ol>
     */
    void pollAndProject() {
        try {
            if (!journalStore.isActive()) {
                return;
            }

            List<String> configuredTargets = lineageConfig.getTargets();
            if (configuredTargets.isEmpty()) {
                return;
            }

            if (targetSinks == null || targetSinks.isEmpty()) {
                return;
            }

            running.set(true);

            for (String targetName : configuredTargets) {
                LineageTargetSink sink = findSink(targetName);
                if (sink == null || !sink.isAvailable()) {
                    continue;
                }

                try {
                    enforceBacklogThresholds(targetName);
                    reapStaleProjecting(targetName);
                    projectEvents(targetName, sink, LineagePublishStatus.PENDING);
                    projectEvents(targetName, sink, LineagePublishStatus.FAILED);
                } catch (Exception e) {
                    logger.warn("Error during projection for target '{}': {}", targetName, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error in projection poll cycle: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * Projects events with the given status to the target sink.
     */
    private void projectEvents(String targetName, LineageTargetSink sink, LineagePublishStatus sourceStatus) {
        int batchSize = lineageConfig.getProjectionBatchSize();
        List<LineageEvent> candidates = journalStore.findByTargetAndStatus(targetName, sourceStatus, batchSize);

        for (LineageEvent event : candidates) {
            try {
                // CAS: claim the event by transitioning to PROJECTING
                int claimed = journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.PROJECTING);
                if (claimed == 0) {
                    // Another node claimed this event — skip
                    continue;
                }

                // Publish to the target
                LineageTargetSinkResult result = sink.publish(event);

                if (result.success()) {
                    journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.PUBLISHED);
                    logger.debug("Published event to '{}': eventKey={}, entities={}",
                            targetName, event.eventKey(), result.entityCount());
                } else {
                    handlePublishFailure(event, targetName, result.message());
                }
            } catch (Exception e) {
                handlePublishFailure(event, targetName, e.getMessage());
            }
        }
    }

    /**
     * Handles a publish failure: transitions to FAILED, checks retry limits,
     * and writes to dead-letter if needed. Auto-discards if max retries exceeded.
     */
    private void handlePublishFailure(LineageEvent event, String targetName, String errorMessage) {
        journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.FAILED);

        // Check retry count limit — auto-discard if exceeded
        int maxRetries = lineageConfig.getBacklogMaxRetryCount();
        if (maxRetries > 0) {
            int retryCount = journalStore.getRetryCount(event.eventId(), targetName);
            if (retryCount >= maxRetries) {
                logger.warn("Retry count {} exceeds max {} for event {} on target '{}' — auto-discarding",
                        retryCount, maxRetries, event.eventKey(), targetName);
                LineageDeadLetterSink.record(event,
                        "auto-discard:max-retry-count:" + retryCount + ":" + targetName);
                journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.DISCARDED);
                return;
            }
        }

        logger.warn("Publish failed for event {} to '{}': {}", event.eventKey(), targetName, errorMessage);
        LineageDeadLetterSink.record(event, "publish-failed:" + targetName + ":" + errorMessage);
    }

    /**
     * Enforces backlog thresholds by auto-discarding oldest non-terminal events.
     */
    void enforceBacklogThresholds(String targetName) {
        // 1. Max retry age
        int maxAgeHours = lineageConfig.getBacklogMaxRetryAgeHours();
        if (maxAgeHours > 0) {
            Instant ageCutoff = Instant.now().minus(Duration.ofHours(maxAgeHours));
            String ageCutoffStr = ageCutoff.toString();

            // Check FAILED events that are too old
            List<LineageEvent> failedEvents = journalStore.findByTargetAndStatus(
                    targetName, LineagePublishStatus.FAILED, lineageConfig.getProjectionBatchSize());
            for (LineageEvent event : failedEvents) {
                if (event.occurredAt().compareTo(ageCutoffStr) < 0) {
                    journalStore.discardEvent(event.eventId(), targetName);
                    LineageDeadLetterSink.record(event, "auto-discard: retry-age-exceeded (" + maxAgeHours + "h)");
                    logger.info("Auto-discarded aged event: eventKey={}, target={}, age={}",
                            event.eventKey(), targetName, event.occurredAt());
                }
            }

            // Also check PENDING events that are too old
            List<LineageEvent> pendingEvents = journalStore.findByTargetAndStatus(
                    targetName, LineagePublishStatus.PENDING, lineageConfig.getProjectionBatchSize());
            for (LineageEvent event : pendingEvents) {
                if (event.occurredAt().compareTo(ageCutoffStr) < 0) {
                    journalStore.discardEvent(event.eventId(), targetName);
                    LineageDeadLetterSink.record(event, "auto-discard: retry-age-exceeded (" + maxAgeHours + "h)");
                    logger.info("Auto-discarded aged event: eventKey={}, target={}, age={}",
                            event.eventKey(), targetName, event.occurredAt());
                }
            }
        }

        // 2. Max docs
        int maxDocs = lineageConfig.getBacklogMaxDocs();
        if (maxDocs > 0) {
            long count = journalStore.countNonTerminalByTarget(targetName);
            if (count > maxDocs) {
                long excess = count - maxDocs;
                logger.warn("Backlog for target '{}' exceeds max-docs ({}/{}), discarding {} oldest",
                        targetName, count, maxDocs, excess);
                // Discard oldest PENDING first, then FAILED
                discardOldest(targetName, LineagePublishStatus.PENDING, (int) excess);
                // Recount; if still over, discard FAILED
                count = journalStore.countNonTerminalByTarget(targetName);
                if (count > maxDocs) {
                    discardOldest(targetName, LineagePublishStatus.FAILED, (int) (count - maxDocs));
                }
            }
        }

        // 3. Max size (MB) — estimated from document count × average event size
        int maxSizeMb = lineageConfig.getBacklogMaxSizeMb();
        if (maxSizeMb > 0) {
            long count = journalStore.countNonTerminalByTarget(targetName);
            // Estimate ~2KB per event document (JSON with snapshot attributes)
            long estimatedSizeBytes = count * ESTIMATED_EVENT_SIZE_BYTES;
            long maxSizeBytes = (long) maxSizeMb * 1024 * 1024;
            if (estimatedSizeBytes > maxSizeBytes) {
                long targetCount = maxSizeBytes / ESTIMATED_EVENT_SIZE_BYTES;
                long excess = count - targetCount;
                logger.warn("Backlog for target '{}' exceeds max-size ({} MB est. / {} MB limit), discarding {} oldest",
                        targetName, estimatedSizeBytes / (1024 * 1024), maxSizeMb, excess);
                discardOldest(targetName, LineagePublishStatus.PENDING, (int) excess);
                count = journalStore.countNonTerminalByTarget(targetName);
                long newEstimate = count * ESTIMATED_EVENT_SIZE_BYTES;
                if (newEstimate > maxSizeBytes) {
                    long remaining = count - (maxSizeBytes / ESTIMATED_EVENT_SIZE_BYTES);
                    discardOldest(targetName, LineagePublishStatus.FAILED, (int) remaining);
                }
            }
        }
    }

    private void discardOldest(String targetName, LineagePublishStatus status, int count) {
        if (count <= 0) return;
        List<LineageEvent> events = journalStore.findByTargetAndStatus(targetName, status, count);
        for (LineageEvent event : events) {
            journalStore.discardEvent(event.eventId(), targetName);
            LineageDeadLetterSink.record(event, "auto-discard: backlog-overflow");
        }
    }

    /**
     * Reaps stale PROJECTING events (stuck > stale threshold).
     *
     * <p>Delegates to the journal store which checks {@code claimedAtByTarget}
     * timestamps for accurate staleness detection.
     */
    void reapStaleProjecting(String targetName) {
        int staleMinutes = lineageConfig.getProjectionStaleThresholdMinutes();
        int reaped = journalStore.reapStaleProjecting(targetName, staleMinutes);
        if (reaped > 0) {
            logger.info("Reaped {} stale PROJECTING events for target={}", reaped, targetName);
        }
    }

    private LineageTargetSink findSink(String targetName) {
        if (targetSinks == null) return null;
        for (LineageTargetSink sink : targetSinks) {
            if (targetName.equals(sink.targetName())) {
                return sink;
            }
        }
        return null;
    }

    private void logConfigStatus() {
        try {
            if (journalStore.isActive()) {
                List<String> targets = lineageConfig.getTargets();
                if (!targets.isEmpty()) {
                    logger.debug("Projection loop active: targets={}, pollInterval={}s, batchSize={}",
                            targets, lineageConfig.getProjectionPollIntervalSeconds(),
                            lineageConfig.getProjectionBatchSize());
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking projection config: {}", e.getMessage());
        }
    }

    /** Returns {@code true} if the projection loop is currently processing events. */
    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    public void destroy() {
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
        logger.info("Lineage projection loop stopped");
    }
}
