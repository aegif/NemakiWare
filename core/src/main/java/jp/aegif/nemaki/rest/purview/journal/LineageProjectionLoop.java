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
        List<LineageJournalRow> candidates = journalStore.findByTargetAndStatus(targetName, sourceStatus, batchSize);

        for (LineageJournalRow row : candidates) {
            // Decode and projection already happened in the store; a row that failed either
            // arrives as a value. Unordered processing owes the other candidates nothing, so the
            // broken row is surfaced and the loop moves on. It is deliberately NOT mutated: see
            // surfaceUndecodable.
            if (!(row instanceof LineageJournalRow.Decoded decoded)) {
                surfaceUndecodable((LineageJournalRow.Undecodable) row, targetName);
                continue;
            }
            LineageJournalEntry entry = decoded.entry();
            LineageRecord record = entry.record();
            try {
                // CAS: claim the event by transitioning to PROJECTING
                int claimed = journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.PROJECTING);
                if (claimed == 0) {
                    // Another node claimed this event — skip
                    continue;
                }

                // Publish to the target
                LineageTargetSinkResult result = sink.publish(record);

                if (result.success()) {
                    journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.PUBLISHED);
                    if (lineageMetrics != null) lineageMetrics.recordPublish(targetName);
                    logger.debug("Published event to '{}': processIdentity={}, entities={}",
                            targetName, record.processIdentity(), result.entityCount());
                } else {
                    handlePublishFailure(entry, targetName, result.message());
                }
            } catch (Exception e) {
                handlePublishFailure(entry, targetName, e.getMessage());
            }
        }
    }

    /**
     * A stored row that could not be decoded into an entry.
     *
     * <p>Surfaced loudly and mutated <b>not at all</b>. The obvious tidy-up — DISCARD it so it
     * leaves the poll set — is destruction: DISCARDED is terminal, {@code purgeOlderThan} deletes
     * terminal rows after retention, and for an undecodable row the stored document is the only
     * evidence of what it was. The earlier slice discarded such rows while it still held a
     * decoded envelope to preserve in the dead letter; this loop no longer has one, so nothing
     * durable can be kept yet. Until the version-neutral quarantine record exists (D-spool), the
     * row stays where it is, the log names it every cycle, and the admin API renders it as a
     * diagnostic row — annoying on purpose.
     */
    private void surfaceUndecodable(LineageJournalRow.Undecodable row, String targetName) {
        logger.warn("Journal row cannot be decoded: id={}, type={}, schemaVersion={}, target={},"
                        + " reason={} — left in place (discarding would purge the only evidence)",
                row.documentId(), row.documentType(), row.schemaVersion(), targetName,
                row.reason());
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

        List<LineageJournalRow> rows = journalStore.findByRepositoryAndSequenceRange(repositoryId, fromSeq, batchSize);

        for (LineageJournalRow row : rows) {
            // An undecodable row STOPS this repository, and the cursor does not move. Skipping it
            // would publish the rows behind it and advance the cursor past a row that was never
            // processed — ordered delivery violated by a catch block. The repository stays halted,
            // loudly, until the row is repaired; that is the contract, not a failure of it.
            //
            // Deliberately including a row whose status WAS terminal before it went corrupt: an
            // Undecodable carries no status (the status map is part of what failed to decode), so
            // "it was already published, advance past it" would be trusting one field of a
            // document whose other fields just failed verification. Halting on corrupt-terminal
            // costs availability; advancing on a guess costs integrity. Repair fixes the former.
            if (!(row instanceof LineageJournalRow.Decoded decoded)) {
                surfaceUndecodable((LineageJournalRow.Undecodable) row, targetName);
                logger.warn("Ordered projection for repo '{}' halted at undecodable row {} —"
                                + " the cursor must not pass an unprocessed event",
                        repositoryId, ((LineageJournalRow.Undecodable) row).documentId());
                break;
            }
            LineageJournalEntry entry = decoded.entry();
            LineageRecord record = entry.record();

            // Check the status for this target
            LineagePublishStatus status = record.publishStatusByTarget().getOrDefault(targetName, LineagePublishStatus.PENDING);

            // Already terminal → advance cursor, continue
            if (status.isTerminal()) {
                advanceCursor(targetName, repositoryId, record.sequenceNumber());
                continue;
            }

            // PROJECTING by another node → stop for this repo
            if (status == LineagePublishStatus.PROJECTING) {
                logger.debug("Row {} is PROJECTING by another node — stopping ordered projection for repo '{}'",
                        record.recordId(), repositoryId);
                break;
            }

            // PENDING or FAILED → try to claim and publish
            try {
                int claimed = journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.PROJECTING);
                if (claimed == 0) {
                    // Another node claimed it — stop to preserve order
                    break;
                }

                LineageTargetSinkResult result = sink.publish(record);

                if (result.success()) {
                    journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.PUBLISHED);
                    if (lineageMetrics != null) lineageMetrics.recordPublish(targetName);
                    advanceCursor(targetName, repositoryId, record.sequenceNumber());
                    logger.debug("Published event (ordered) to '{}': processIdentity={}, seq={}",
                            targetName, record.processIdentity(), record.sequenceNumber());
                } else {
                    // Publish failed — stop processing this repo to preserve order
                    handlePublishFailure(entry, targetName, result.message());
                    break;
                }
            } catch (Exception e) {
                handlePublishFailure(entry, targetName, e.getMessage());
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
     * Handles a publish failure: transitions to FAILED, checks retry limits, and — for a v1 row —
     * writes to dead-letter and auto-discards past max retries.
     *
     * <h2>A v2 row is never auto-discarded and never dead-lettered here</h2>
     *
     * <p>Not caution — arithmetic. {@link LineageDeadLetterSink} records v1 envelopes only, so a
     * v2 row reaching max retries would be DISCARDED with no dead-letter copy; DISCARDED is
     * terminal; {@code purgeOlderThan} deletes terminal rows after retention. Sum: the only
     * lossless copy of the event, deleted, with nothing to replay from. Until the
     * version-neutral durable dead-letter exists (D-spool / §9), a failing v2 row stays FAILED —
     * retried, visible in the backlog metric, never destroyed. Nothing writes v2 before Slice 4,
     * and D-spool lands before 4a, so this branch is a fence, not a running cost.
     */
    private void handlePublishFailure(LineageJournalEntry entry, String targetName,
                                      String errorMessage) {
        LineageRecord record = entry.record();
        journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.FAILED);
        if (lineageMetrics != null) lineageMetrics.recordFail(targetName);

        if (!(entry.envelope() instanceof LineageJournalEntry.V1 v1)) {
            logger.warn("Publish failed for v2 row {} to '{}': {} — kept FAILED; auto-discard and"
                            + " dead-letter are v1-only until the version-neutral dead letter"
                            + " exists (D-spool)",
                    record.recordId(), targetName, errorMessage);
            return;
        }

        // Check retry count limit — auto-discard if exceeded
        int maxRetries = lineageConfig.getBacklogMaxRetryCount();
        if (maxRetries > 0) {
            int retryCount = journalStore.getRetryCount(record.recordId(), targetName);
            if (retryCount >= maxRetries) {
                logger.warn("Retry count {} exceeds max {} for event {} on target '{}' — auto-discarding",
                        retryCount, maxRetries, record.processIdentity(), targetName);
                LineageDeadLetterSink.record(v1.event(),
                        "auto-discard:max-retry-count:" + retryCount + ":" + targetName);
                journalStore.updatePublishStatus(record.recordId(), targetName, LineagePublishStatus.DISCARDED);
                if (lineageMetrics != null) lineageMetrics.recordDiscard();
                return;
            }
        }

        logger.warn("Publish failed for event {} to '{}': {}", record.processIdentity(), targetName, errorMessage);
        LineageDeadLetterSink.record(v1.event(), "publish-failed:" + targetName + ":" + errorMessage);
    }

    /**
     * Enforces backlog thresholds by auto-discarding oldest non-terminal events.
     */
    void enforceBacklogThresholds(String targetName) {
        // 1. Max retry age
        int maxAgeHours = lineageConfig.getBacklogMaxRetryAgeHours();
        if (maxAgeHours > 0) {
            Instant ageCutoff = Instant.now().minus(Duration.ofHours(maxAgeHours));

            // Check FAILED events that are too old
            List<LineageJournalRow> failedRows = journalStore.findByTargetAndStatus(
                    targetName, LineagePublishStatus.FAILED, lineageConfig.getProjectionBatchSize());
            for (LineageJournalRow row : failedRows) {
                discardIfOlderThan(row, targetName, ageCutoff, maxAgeHours);
            }

            // Also check PENDING events that are too old
            List<LineageJournalRow> pendingRows = journalStore.findByTargetAndStatus(
                    targetName, LineagePublishStatus.PENDING, lineageConfig.getProjectionBatchSize());
            for (LineageJournalRow row : pendingRows) {
                discardIfOlderThan(row, targetName, ageCutoff, maxAgeHours);
            }
        }

        // 2. Max docs
        int maxDocs = lineageConfig.getBacklogMaxDocs();
        enforceMaxDocsAndBytes(targetName, maxDocs);
    }

    /**
     * Age comparison by parsed {@link Instant}, not by string.
     *
     * <p>The strings compared here used to be compared lexically, and that is wrong for the very
     * values {@code Instant.toString()} produces: the fractional second is variable-width, so
     * {@code ...:00.500Z} sorts <em>before</em> {@code ...:00Z} — half a second later, judged
     * older. v1 events all come from {@code Instant.now().toString()} and carry millis most of the
     * time, so the mis-ordering was rare but real; v2's {@code occurredAt} is builder-supplied and
     * only promises to be {@code Instant.parse}-compatible.
     *
     * <p>An unparseable {@code occurredAt} is skipped rather than discarded: age is the one thing
     * we cannot know about it, and the max-docs drain below still bounds the backlog without
     * reading the timestamp, so junk cannot accumulate forever either way.
     */
    private void discardIfOlderThan(LineageJournalRow row, String targetName, Instant ageCutoff,
                                    int maxAgeHours) {
        // Undecodable: age is one of the things we cannot know about it, and discarding it would
        // make it purge-eligible — see surfaceUndecodable. The max-docs drain below bounds the
        // backlog without reading a timestamp, so junk cannot accumulate forever either way.
        if (!(row instanceof LineageJournalRow.Decoded decoded)) {
            return;
        }
        LineageJournalEntry entry = decoded.entry();
        LineageRecord record = entry.record();
        // A v2 row is exempt for the same arithmetic as handlePublishFailure: discard without a
        // dead-letter copy plus retention purge equals deletion of the only lossless copy.
        if (!(entry.envelope() instanceof LineageJournalEntry.V1 v1)) {
            logger.warn("Aged v2 row {} not auto-discarded — discard is v1-only until the"
                    + " version-neutral dead letter exists (D-spool)", record.recordId());
            return;
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(record.occurredAt());
        } catch (RuntimeException e) {
            logger.warn("Event {} has unparseable occurredAt '{}' — age unknown, not discarding",
                    record.recordId(), record.occurredAt());
            return;
        }
        if (occurredAt.isBefore(ageCutoff)) {
            journalStore.discardEvent(record.recordId(), targetName);
            LineageDeadLetterSink.record(v1.event(), "auto-discard: retry-age-exceeded (" + maxAgeHours + "h)");
            logger.info("Auto-discarded aged event: recordId={}, target={}, age={}",
                    record.recordId(), targetName, record.occurredAt());
        }
    }

    private void enforceMaxDocsAndBytes(String targetName, int maxDocs) {
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
        List<LineageJournalRow> rows = journalStore.findByTargetAndStatusOldestFirst(targetName, status, count);
        for (LineageJournalRow row : rows) {
            if (!(row instanceof LineageJournalRow.Decoded decoded)) {
                continue; // see surfaceUndecodable: discarding the unclassifiable purges evidence
            }
            if (!(decoded.entry().envelope() instanceof LineageJournalEntry.V1 v1)) {
                logger.warn("Overflow drain skipping v2 row {} — discard is v1-only until the"
                        + " version-neutral dead letter exists (D-spool)",
                        decoded.entry().record().recordId());
                continue;
            }
            journalStore.discardEvent(decoded.entry().record().recordId(), targetName);
            LineageDeadLetterSink.record(v1.event(), "auto-discard: backlog-overflow");
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
