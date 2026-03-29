package jp.aegif.nemaki.rest.purview.journal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired(required = false)
    private List<LineageTargetSink> targetSinks;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    @Autowired(required = false)
    private LineageDeadLetterStore deadLetterStore;

    @Autowired(required = false)
    private LeaderElection leaderElection;

    @Autowired(required = false)
    private ProjectionCursorStore cursorStore;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (lineageMetrics != null) {
            LineageDeadLetterSink.setMetrics(lineageMetrics);
        }
        if (deadLetterStore != null) {
            LineageDeadLetterSink.setStore(deadLetterStore);
        }

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
     *   <li>Project PENDING events (ordered if cursor store available)</li>
     *   <li>Retry FAILED events</li>
     * </ol>
     */
    void pollAndProject() {
        try {
            if (!journalStore.isActive()) {
                return;
            }

            // Leader election guard: only the leader node runs projection
            if (leaderElection != null && leaderElection.isEnabled()
                    && !leaderElection.isLeader("projection")) {
                logger.debug("Not the leader for 'projection' — skipping poll cycle");
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

                    // Use cursor-based ordered processing if cursor store is available
                    if (cursorStore != null && cursorStore.isActive()) {
                        projectEventsOrdered(targetName, sink);
                    } else {
                        projectEvents(targetName, sink, LineagePublishStatus.PENDING);
                        projectEvents(targetName, sink, LineagePublishStatus.FAILED);
                    }
                } catch (Exception e) {
                    logger.warn("Error during projection for target '{}': {}", targetName, e.getMessage());
                }
            }
            if (lineageMetrics != null) {
                lineageMetrics.recordPollComplete(configuredTargets.size());
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
                    if (lineageMetrics != null) lineageMetrics.recordPublish(targetName);
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
     * Cursor-based ordered projection for a target.
     *
     * <p>For each repository, fetches events in sequence order starting from
     * the cursor position. Processing stops at the first failure to maintain
     * strict ordering guarantees.
     *
     * <p>Sequence order:
     * <ol>
     *   <li>Get all cursor positions for this target</li>
     *   <li>For each (target, repositoryId) pair, fetch events after cursor</li>
     *   <li>Terminal events → advance cursor, skip</li>
     *   <li>PENDING/FAILED → CAS claim → publish → advance cursor</li>
     *   <li>Failure → stop processing this repository (preserve order)</li>
     *   <li>PROJECTING by other node → stop processing this repository</li>
     * </ol>
     */
    private void projectEventsOrdered(String targetName, LineageTargetSink sink) {
        int batchSize = lineageConfig.getProjectionBatchSize();

        // Collect distinct repository IDs from cursor store + all non-terminal events.
        // Uses a dedicated view query (no batchSize limit) to avoid starving
        // repositories that happen to fall outside the first N events.
        Set<String> repositoryIds = new LinkedHashSet<>();
        List<ProjectionCursor> cursors = cursorStore.getAllCursors();
        for (ProjectionCursor c : cursors) {
            if (targetName.equals(c.target())) {
                repositoryIds.add(c.repositoryId());
            }
        }

        // Discover all repositories with non-terminal events via grouped view query
        List<String> nonTerminalRepos = journalStore.findDistinctNonTerminalRepositoryIds(targetName);
        repositoryIds.addAll(nonTerminalRepos);

        for (String repositoryId : repositoryIds) {
            projectEventsOrderedForRepo(targetName, sink, repositoryId, batchSize);
        }
    }

    /**
     * Process events in sequence order for a single (target, repository) pair.
     */
    private void projectEventsOrderedForRepo(String targetName, LineageTargetSink sink,
                                              String repositoryId, int batchSize) {
        ProjectionCursor cursor = cursorStore.getCursor(targetName, repositoryId);
        long fromSeq = (cursor != null) ? cursor.lastProcessedSequence() : 0;

        List<LineageEvent> events = journalStore.findByRepositoryAndSequenceRange(repositoryId, fromSeq, batchSize);

        for (LineageEvent event : events) {
            // Check the status for this target
            LineagePublishStatus status = event.publishStatusByTarget().getOrDefault(targetName, LineagePublishStatus.PENDING);

            // Already terminal → advance cursor, continue
            if (status.isTerminal()) {
                advanceCursor(targetName, repositoryId, event.sequenceNumber());
                continue;
            }

            // PROJECTING by another node → stop for this repo
            if (status == LineagePublishStatus.PROJECTING) {
                logger.debug("Event {} is PROJECTING by another node — stopping ordered projection for repo '{}'",
                        event.eventKey(), repositoryId);
                break;
            }

            // PENDING or FAILED → try to claim and publish
            try {
                int claimed = journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.PROJECTING);
                if (claimed == 0) {
                    // Another node claimed it — stop to preserve order
                    break;
                }

                LineageTargetSinkResult result = sink.publish(event);

                if (result.success()) {
                    journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.PUBLISHED);
                    if (lineageMetrics != null) lineageMetrics.recordPublish(targetName);
                    advanceCursor(targetName, repositoryId, event.sequenceNumber());
                    logger.debug("Published event (ordered) to '{}': eventKey={}, seq={}",
                            targetName, event.eventKey(), event.sequenceNumber());
                } else {
                    // Publish failed — stop processing this repo to preserve order
                    handlePublishFailure(event, targetName, result.message());
                    break;
                }
            } catch (Exception e) {
                handlePublishFailure(event, targetName, e.getMessage());
                break;
            }
        }
    }

    private void advanceCursor(String targetName, String repositoryId, long sequenceNumber) {
        try {
            ProjectionCursor updated = new ProjectionCursor(targetName, repositoryId, sequenceNumber, java.time.Instant.now());
            cursorStore.updateCursor(updated);
        } catch (Exception e) {
            logger.warn("Failed to advance cursor for target='{}', repo='{}': {}", targetName, repositoryId, e.getMessage());
        }
    }

    /**
     * Handles a publish failure: transitions to FAILED, checks retry limits,
     * and writes to dead-letter if needed. Auto-discards if max retries exceeded.
     */
    private void handlePublishFailure(LineageEvent event, String targetName, String errorMessage) {
        journalStore.updatePublishStatus(event.eventId(), targetName, LineagePublishStatus.FAILED);
        if (lineageMetrics != null) lineageMetrics.recordFail(targetName);

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
                if (lineageMetrics != null) lineageMetrics.recordDiscard();
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

        // 3. Max size (MB) — uses real DB size proportional estimation
        int maxSizeMb = lineageConfig.getBacklogMaxSizeMb();
        if (maxSizeMb > 0) {
            long estimatedSizeBytes = journalStore.getEstimatedNonTerminalSizeBytes(targetName);
            long maxSizeBytes = (long) maxSizeMb * 1024 * 1024;
            if (estimatedSizeBytes > maxSizeBytes) {
                long count = journalStore.countNonTerminalByTarget(targetName);
                long avgPerDoc = count > 0 ? estimatedSizeBytes / count : 2048;
                long targetCount = avgPerDoc > 0 ? maxSizeBytes / avgPerDoc : count;
                long excess = count - targetCount;
                if (excess > 0) {
                    logger.warn("Backlog for target '{}' exceeds max-size ({} MB est. / {} MB limit), discarding {} oldest",
                            targetName, estimatedSizeBytes / (1024 * 1024), maxSizeMb, excess);
                    discardOldest(targetName, LineagePublishStatus.PENDING, (int) excess);
                    // Recheck after discard
                    long newEstimate = journalStore.getEstimatedNonTerminalSizeBytes(targetName);
                    if (newEstimate > maxSizeBytes) {
                        long newCount = journalStore.countNonTerminalByTarget(targetName);
                        long newAvg = newCount > 0 ? newEstimate / newCount : 2048;
                        long remaining = newAvg > 0 ? newCount - (maxSizeBytes / newAvg) : 0;
                        if (remaining > 0) {
                            discardOldest(targetName, LineagePublishStatus.FAILED, (int) remaining);
                        }
                    }
                }
            }
        }
    }

    private void discardOldest(String targetName, LineagePublishStatus status, int count) {
        if (count <= 0) return;
        List<LineageEvent> events = journalStore.findByTargetAndStatusOldestFirst(targetName, status, count);
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
