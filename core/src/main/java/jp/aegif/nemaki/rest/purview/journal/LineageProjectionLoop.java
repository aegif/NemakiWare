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

    @Autowired(required = false)
    private LineageDrestReadiness drestReadiness;

    @Autowired(required = false)
    private LineageReplayService replayService;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * The claimant's own tokens (v2.3.19 R4): key {@code recordId|target} → claim token.
     * §8-b's VERIFYING→VERIFYING (same token + renew) is the claimant continuing ITS OWN
     * claim; this registry is what "its own" means across poll cycles. Tokens are only ever
     * minted in claimForProjection and never adopted from storage, so two APs can never share
     * one. Entries are removed at every exit (terminal transition, CAS/token loss, max-age,
     * malformed row, structural fault, renewal failure, reap observed, shutdown); a JVM
     * restart empties it, and the orphaned claims recover through lease expiry + the reaper.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> ownedClaims =
            new java.util.concurrent.ConcurrentHashMap<>();

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

            // §8-d crash recovery (D-rest-3, B1): once per gated leader poll, BEFORE the
            // empty-target early returns — a stranded request whose only target was removed
            // is still visited and refused loudly. Red gate → recoverUnacked is dormant.
            if (replayService != null) {
                try {
                    int recovered = replayService.recoverUnacked(
                            lineageConfig.getProjectionBatchSize());
                    if (recovered > 0) {
                        logger.info("Replay crash recovery drove {} requests to ACKED",
                                recovered);
                    }
                } catch (RuntimeException e) {
                    logger.error("Replay crash recovery pass failed: {}", e.getMessage());
                }
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
                        // D-rest activation boundary: ONE readiness evaluation per target per
                        // poll (B2). Not ready → the v2 branch is fully dormant (C2: no claim,
                        // no renewal, no verify, no transition, no reap; registry retained
                        // untouched) and the legacy v1-only path runs byte-identical.
                        boolean drestActive = drestReadiness != null
                                && journalStore instanceof LineageV2TransitionStore
                                && drestReadiness.evaluate().ready();
                        if (drestActive) {
                            projectEventsOrderedDrest(targetName, sink);
                        } else {
                            projectEventsOrdered(targetName, sink);
                        }
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

                // §7's last gate, after the claim and immediately before the sink call.
                String violation = preSinkViolation(entry);
                if (violation != null) {
                    rejectAtGate(record, targetName, violation);
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

                // §7's last gate. REJECTED is a terminal decision about this row, so on a
                // confirmed persist the cursor may advance over it and the repository continues;
                // an unconfirmed persist (raced by another node) halts, like any other
                // uncertainty in the ordered path.
                String violation = preSinkViolation(entry);
                if (violation != null) {
                    if (rejectAtGate(record, targetName, violation)) {
                        advanceCursor(targetName, repositoryId, record.sequenceNumber());
                        continue;
                    }
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

    // ==================================================================
    // D-rest ordered walk (§8-b/§8-c, v2.3.19). Runs ONLY when LineageDrestReadiness is
    // ready; the legacy methods above are byte-identical and run otherwise.
    // ==================================================================

    /**
     * The D-rest ordered walk for one target: reaper first (C2 ordering), then per-repository
     * merged v1+v2 processing with uniform zero-means-stop (§8-c) and monotonic cursor CAS.
     */
    private void projectEventsOrderedDrest(String targetName, LineageTargetSink sink) {
        LineageV2TransitionStore v2store = (LineageV2TransitionStore) journalStore;
        // Reaper before anything else: expired claims become FAILED (token-fenced, reap-by-CAS)
        // so this cycle's claims see clean state. One pass per target per poll.
        try {
            int reaped = v2store.reapExpiredClaims(targetName, Instant.now());
            if (reaped > 0) {
                logger.info("v2 reaper transitioned {} expired claims to FAILED for '{}'",
                        reaped, targetName);
            }
        } catch (RuntimeException e) {
            logger.error("v2 reaper failed for '{}' — halting this target's poll: {}",
                    targetName, e.getMessage());
            return;
        }

        Set<String> repositoryIds = new LinkedHashSet<>();
        for (ProjectionCursor c : cursorStore.getAllCursors()) {
            if (targetName.equals(c.target())) {
                repositoryIds.add(c.repositoryId());
            }
        }
        repositoryIds.addAll(journalStore.findDistinctNonTerminalRepositoryIds(targetName));
        try {
            repositoryIds.addAll(v2store.findV2NonTerminalRepositoryIds(targetName));
        } catch (RuntimeException e) {
            logger.error("v2 repository discovery failed for '{}' — halting this target's"
                    + " poll: {}", targetName, e.getMessage());
            return;
        }

        int batchSize = lineageConfig.getProjectionBatchSize();
        for (String repositoryId : repositoryIds) {
            try {
                projectMergedForRepo(targetName, sink, v2store, repositoryId, batchSize);
            } catch (RuntimeException e) {
                logger.error("D-rest ordered projection halted for repo '{}': {}",
                        repositoryId, e.getMessage());
            }
        }
    }

    /** One merged (v1 ∪ v2) sequence-ordered pass over a repository, capped at batchSize. */
    private void projectMergedForRepo(String targetName, LineageTargetSink sink,
                                      LineageV2TransitionStore v2store, String repositoryId,
                                      int batchSize) {
        ProjectionCursor cursor = cursorStore.getCursor(targetName, repositoryId);
        long fromSeq = (cursor != null) ? cursor.lastProcessedSequence() : 0;

        // STRICT v1 fetch (parallel review, D-rest-2 tip): the legacy method returns an
        // empty list on failure/inactivity and clamps its limit — either would make the merge
        // window read a silent shortfall as coverage-to-infinity and let the v2 side advance
        // the cursor past unseen v1 rows. The strict variant throws on failure and fetches
        // exactly batchSize, so "fewer than a full batch" really means exhaustion.
        List<LineageJournalRow> v1rows =
                v2store.findV1ByRepositoryAndSequenceRangeStrict(repositoryId, fromSeq,
                        batchSize);
        List<LineageJournalRowV2> v2rows =
                v2store.findV2ByRepositoryAndSequenceRange(repositoryId, fromSeq, batchSize);

        // Coverage bounds (A1): a side that returned a FULL batch covers only up to its last
        // sequence; a side with fewer rows covers to infinity. Processing past min(bounds)
        // could skip a sequence the truncated side has not shown yet.
        long v1Bound = v1rows.size() < batchSize ? Long.MAX_VALUE : v1SequenceOf(v1rows.get(v1rows.size() - 1));
        long v2Bound = v2rows.size() < batchSize ? Long.MAX_VALUE
                : v2rows.get(v2rows.size() - 1).event().sequenceNumber();
        long bound = Math.min(v1Bound, v2Bound);

        int i1 = 0;
        int i2 = 0;
        int processed = 0;
        while (processed < batchSize) {
            LineageJournalRow v1 = i1 < v1rows.size() ? v1rows.get(i1) : null;
            LineageJournalRowV2 v2 = i2 < v2rows.size() ? v2rows.get(i2) : null;
            if (v1 == null && v2 == null) {
                return;
            }
            long s1 = v1 != null ? v1SequenceOf(v1) : Long.MAX_VALUE;
            long s2 = v2 != null ? v2.event().sequenceNumber() : Long.MAX_VALUE;
            boolean takeV1 = s1 <= s2;
            long seq = takeV1 ? s1 : s2;
            if (seq > bound) {
                return; // beyond the merge window — the next poll refetches from the cursor
            }
            boolean advanced = takeV1
                    ? processV1RowDrest(targetName, sink, repositoryId, v1)
                    : processV2RowDrest(targetName, sink, v2store, repositoryId, v2);
            if (!advanced) {
                return; // zero-means-stop, uniformly (§8-c)
            }
            if (takeV1) {
                i1++;
            } else {
                i2++;
            }
            processed++;
        }
    }

    /**
     * The v1 row's sequence, halting (via exception caught by the repo loop) on an
     * undecodable row — the merged walk inherits the legacy rule: the cursor never passes an
     * unprocessed event.
     */
    private long v1SequenceOf(LineageJournalRow row) {
        if (!(row instanceof LineageJournalRow.Decoded decoded)) {
            LineageJournalRow.Undecodable u = (LineageJournalRow.Undecodable) row;
            throw new IllegalStateException("undecodable v1 row " + u.documentId()
                    + " blocks the ordered stream: " + u.reason());
        }
        return decoded.entry().record().sequenceNumber();
    }

    /**
     * One v1 row under D-rest: the v1 claim/status machinery unchanged, but B4's uniform
     * zero-means-stop — every status persistence is CONFIRMED before the monotonic cursor
     * advances.
     *
     * @return {@code true} to continue the walk
     */
    private boolean processV1RowDrest(String targetName, LineageTargetSink sink,
                                      String repositoryId, LineageJournalRow row) {
        LineageJournalRow.Decoded decoded = (LineageJournalRow.Decoded) row; // v1SequenceOf vetted
        LineageJournalEntry entry = decoded.entry();
        LineageRecord record = entry.record();
        LineagePublishStatus status = record.publishStatusByTarget()
                .getOrDefault(targetName, LineagePublishStatus.PENDING);
        if (status.isTerminal()) {
            return advanceCursorMonotonicChecked(targetName, repositoryId,
                    record.sequenceNumber());
        }
        if (status == LineagePublishStatus.PROJECTING) {
            logger.debug("v1 row {} is PROJECTING by another node — stopping repo '{}'",
                    record.recordId(), repositoryId);
            return false;
        }
        try {
            int claimed = journalStore.updatePublishStatus(record.recordId(), targetName,
                    LineagePublishStatus.PROJECTING);
            if (claimed == 0) {
                return false;
            }
            String violation = preSinkViolation(entry);
            if (violation != null) {
                if (rejectAtGate(record, targetName, violation)) {
                    return advanceCursorMonotonicChecked(targetName, repositoryId,
                            record.sequenceNumber());
                }
                return false;
            }
            LineageTargetSinkResult result = sink.publish(record);
            if (result.success()) {
                int persisted = journalStore.updatePublishStatus(record.recordId(), targetName,
                        LineagePublishStatus.PUBLISHED);
                if (persisted == 0) {
                    // B4: an unconfirmed PUBLISHED must not advance the cursor — the legacy
                    // path's ignore-and-advance is exactly the §8-c defect this walk removes.
                    logger.warn("PUBLISHED persist unconfirmed for v1 row {} — halting repo"
                            + " '{}'", record.recordId(), repositoryId);
                    return false;
                }
                if (lineageMetrics != null) lineageMetrics.recordPublish(targetName);
                return advanceCursorMonotonicChecked(targetName, repositoryId,
                        record.sequenceNumber());
            }
            handlePublishFailure(entry, targetName, result.message());
            return false;
        } catch (Exception e) {
            handlePublishFailure(entry, targetName, e.getMessage());
            return false;
        }
    }

    /**
     * One v2 row under D-rest: the token-fenced §8-b machine.
     *
     * @return {@code true} to continue the walk
     */
    private boolean processV2RowDrest(String targetName, LineageTargetSink sink,
                                      LineageV2TransitionStore v2store, String repositoryId,
                                      LineageJournalRowV2 row) {
        String recordId = row.event().deliveryId();
        String registryKey = recordId + "|" + targetName;
        long seq = row.event().sequenceNumber();
        LineageTargetLifecycle lifecycle = row.targetLifecycles().get(targetName);
        LineagePublishStatus status = lifecycle == null
                ? LineagePublishStatus.PENDING : lifecycle.status();

        if (status.isTerminal()) {
            ownedClaims.remove(registryKey);
            return advanceCursorMonotonicChecked(targetName, repositoryId, seq);
        }
        if (status == LineagePublishStatus.WAITING_FOR_CATALOG) {
            logger.debug("v2 row {} awaits a catalog obligation — halting repo '{}'",
                    recordId, repositoryId);
            return false;
        }
        long nowMs = Instant.now().toEpochMilli();
        if (status == LineagePublishStatus.PROJECTING
                || status == LineagePublishStatus.VERIFYING) {
            String ownedToken = ownedClaims.get(registryKey);
            boolean owned = ownedToken != null && lifecycle != null
                    && ownedToken.equals(lifecycle.claimToken());
            boolean expired = lifecycle == null || lifecycle.leaseExpiresAtMs() == null
                    || lifecycle.leaseExpiresAtMs() <= nowMs;
            if (!owned || expired) {
                // Not ours, or expired (the reaper owns expiry — an own expired claim never
                // renews from memory, C2). Either way the walk cannot pass a live sequence.
                ownedClaims.remove(registryKey);
                if (lineageMetrics != null) {
                    lineageMetrics.recordV2RoutingHalt(expired ? "expired-claim"
                            : "foreign-claim");
                }
                return false;
            }
            if (status == LineagePublishStatus.PROJECTING) {
                // Defensive: an owned PROJECTING re-encounter means a previous encounter broke
                // between claim and settle. Whether the POST happened is unknowable here, so
                // no blind re-POST: drop ownership and let lease expiry + reaper recover it.
                logger.error("Owned v2 claim {} re-encountered in PROJECTING — dropping"
                        + " ownership for reaper recovery", recordId);
                ownedClaims.remove(registryKey);
                return false;
            }
            return verifyOwnedV2(targetName, sink, v2store, repositoryId, row, ownedToken);
        }

        // PENDING or FAILED → claim
        java.time.Duration lease =
                java.time.Duration.ofSeconds(lineageConfig.getProjectionClaimLeaseSeconds());
        LineageV2TransitionStore.V2ClaimGrant grant;
        try {
            grant = v2store.claimForProjection(recordId, targetName, lease);
        } catch (RuntimeException e) {
            logger.error("v2 claim failed for {} — halting repo '{}': {}", recordId,
                    repositoryId, e.getMessage());
            return false;
        }
        if (grant == null) {
            return false;
        }
        ownedClaims.put(registryKey, grant.claimToken());
        if (lineageMetrics != null) lineageMetrics.recordV2Claimed(targetName);

        LineageRecord record = recordOf(row);
        if (record == null) {
            // Cannot rebuild the projection — treat like an undecodable row: drop ownership,
            // halt; the claim recovers via the reaper.
            ownedClaims.remove(registryKey);
            return false;
        }
        String violation = preSinkViolationV2(row, record);
        if (violation != null) {
            boolean persisted = v2store.transitionV2(recordId, targetName,
                    LineagePublishStatus.PROJECTING, LineagePublishStatus.REJECTED,
                    grant.claimToken(),
                    new LineageTargetLifecycle.TerminalReason("PRE_SINK_GATE",
                            violation, nowMs));
            ownedClaims.remove(registryKey);
            if (lineageMetrics != null) lineageMetrics.recordFail(targetName);
            if (persisted) {
                logger.warn("Pre-sink gate refused v2 record {} for '{}': {}", recordId,
                        targetName, violation);
                return advanceCursorMonotonicChecked(targetName, repositoryId, seq);
            }
            return false;
        }
        try {
            LineageTargetSinkResult result = sink.publish(record);
            if (!result.success()) {
                settleV2PublishFailure(v2store, recordId, targetName, grant.claimToken(),
                        registryKey, result.message());
                return false;
            }
        } catch (Exception e) {
            settleV2PublishFailure(v2store, recordId, targetName, grant.claimToken(),
                    registryKey, e.getMessage());
            return false;
        }
        boolean toVerifying = v2store.transitionV2(recordId, targetName,
                LineagePublishStatus.PROJECTING, LineagePublishStatus.VERIFYING,
                grant.claimToken(), null);
        if (!toVerifying) {
            ownedClaims.remove(registryKey);
            return false;
        }
        return verifyOwnedV2(targetName, sink, v2store, repositoryId, row, grant.claimToken());
    }

    private void settleV2PublishFailure(LineageV2TransitionStore v2store, String recordId,
                                        String targetName, String token, String registryKey,
                                        String message) {
        logger.warn("v2 publish failed for {} to '{}': {}", recordId, targetName, message);
        try {
            v2store.transitionV2(recordId, targetName, LineagePublishStatus.PROJECTING,
                    LineagePublishStatus.FAILED, token, null);
        } catch (RuntimeException e) {
            logger.error("v2 FAILED settle also failed for {}: {}", recordId, e.getMessage());
        }
        ownedClaims.remove(registryKey);
        if (lineageMetrics != null) lineageMetrics.recordFail(targetName);
    }

    /**
     * The owned bounded verify poll (R4/B3): interval steps inside the per-encounter budget,
     * renewals with a safety margin, every verify call bounded by the REMAINING budget.
     *
     * @return {@code true} iff the row settled terminal AND the cursor advanced
     */
    private boolean verifyOwnedV2(String targetName, LineageTargetSink sink,
                                  LineageV2TransitionStore v2store, String repositoryId,
                                  LineageJournalRowV2 row, String token) {
        String recordId = row.event().deliveryId();
        String registryKey = recordId + "|" + targetName;
        long seq = row.event().sequenceNumber();
        LineageRecord record = recordOf(row);
        if (record == null) {
            ownedClaims.remove(registryKey);
            return false;
        }
        java.time.Duration lease =
                java.time.Duration.ofSeconds(lineageConfig.getProjectionClaimLeaseSeconds());
        long intervalMs = lineageConfig.getVerifyIntervalSeconds() * 1000L;
        long budgetDeadline = System.nanoTime()
                + java.time.Duration.ofSeconds(lineageConfig.getVerifyTimeoutSeconds()).toNanos();
        long maxAgeMs = lineageConfig.getVerifyMaxAgeMinutes() * 60_000L;
        // Renew before the first attempt (B3): the encounter must never outlive its lease.
        if (!renewOrFence(v2store, recordId, targetName, token, lease, registryKey)) {
            return false;
        }
        // The lease clock is tracked as an ESTIMATE from actual renewals (F4/F6): it resets
        // only when a renewal really happened — never silently per iteration.
        long leaseExpiryEstimate = System.nanoTime() + lease.toNanos();
        long marginNs = Math.max(2 * intervalMs * 1_000_000L, lease.toNanos() / 4);
        while (true) {
            LineageTargetLifecycle current = rereadLifecycle(v2store, recordId, targetName);
            if (current == null || current.status() != LineagePublishStatus.VERIFYING
                    || !token.equals(current.claimToken())) {
                ownedClaims.remove(registryKey);
                return false;
            }
            long nowMs = Instant.now().toEpochMilli();
            Long since = current.verifyingSinceMs();
            if (since != null && nowMs - since > maxAgeMs) {
                boolean persisted = v2store.transitionV2(recordId, targetName,
                        LineagePublishStatus.VERIFYING, LineagePublishStatus.FAILED, token,
                        null);
                ownedClaims.remove(registryKey);
                logger.warn("v2 verify max-age exceeded for {} ({} ms) — FAILED{}", recordId,
                        nowMs - since, persisted ? "" : " (persist unconfirmed)");
                return false;
            }
            long remainingNs = budgetDeadline - System.nanoTime();
            if (remainingNs <= 0) {
                // Budget exhausted: renew, keep VERIFYING, resume next cycle from the
                // registry (the owner recognizes its own claim — no stall, no re-POST).
                renewOrFence(v2store, recordId, targetName, token, lease, registryKey);
                if (lineageMetrics != null) lineageMetrics.recordV2VerifyRetry(targetName);
                return false;
            }
            if (leaseExpiryEstimate - System.nanoTime() < marginNs) {
                if (!renewOrFence(v2store, recordId, targetName, token, lease, registryKey)) {
                    return false;
                }
                leaseExpiryEstimate = System.nanoTime() + lease.toNanos();
            }
            // B3 exactly: each verify call receives min(interval slice, budget left) — never
            // the full timeout afresh.
            long callBudgetMs = Math.min(intervalMs,
                    Math.max(1, remainingNs / 1_000_000));
            LineageTargetSink.VerifyResult verdict;
            try {
                verdict = sink.verify(record, java.time.Duration.ofMillis(callBudgetMs));
            } catch (RuntimeException e) {
                logger.warn("v2 verify attempt errored for {}: {} — treated as retryable",
                        recordId, e.getMessage());
                verdict = LineageTargetSink.VerifyResult.RETRYABLE;
            }
            switch (verdict) {
                case VERIFIED -> {
                    boolean persisted = v2store.transitionV2(recordId, targetName,
                            LineagePublishStatus.VERIFYING, LineagePublishStatus.PUBLISHED,
                            token, null);
                    ownedClaims.remove(registryKey);
                    if (!persisted) {
                        return false;
                    }
                    if (lineageMetrics != null) {
                        lineageMetrics.recordV2Published(targetName);
                        lineageMetrics.recordPublish(targetName);
                    }
                    return advanceCursorMonotonicChecked(targetName, repositoryId, seq);
                }
                case MISMATCH -> {
                    boolean persisted = v2store.transitionV2(recordId, targetName,
                            LineagePublishStatus.VERIFYING,
                            LineagePublishStatus.UNPROJECTABLE, token,
                            new LineageTargetLifecycle.TerminalReason("VERIFY_MISMATCH",
                                    "deterministic semantic mismatch at target '" + targetName
                                            + "'", nowMs));
                    ownedClaims.remove(registryKey);
                    if (lineageMetrics != null) lineageMetrics.recordV2Unprojectable(targetName);
                    if (!persisted) {
                        return false;
                    }
                    return advanceCursorMonotonicChecked(targetName, repositoryId, seq);
                }
                case UNSUPPORTED -> {
                    // Structural fault: the sink advertised supportsVerification() (readiness
                    // required it) yet answered UNSUPPORTED. No publish, loud halt (B3);
                    // the claim recovers via lease expiry + reaper.
                    logger.error("Sink '{}' answered UNSUPPORTED despite advertising"
                            + " verification — structural fault, halting repo '{}'",
                            targetName, repositoryId);
                    ownedClaims.remove(registryKey);
                    if (lineageMetrics != null) {
                        lineageMetrics.recordV2RoutingHalt("verify-unsupported");
                    }
                    return false;
                }
                case RETRYABLE -> {
                    if (lineageMetrics != null) lineageMetrics.recordV2VerifyRetry(targetName);
                    long sleepMs = Math.min(intervalMs,
                            Math.max(1, (budgetDeadline - System.nanoTime()) / 1_000_000));
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        // Shutdown-adjacent exit (F6): ownership is dropped like every other
                        // exit — the claim recovers via lease expiry + reaper.
                        ownedClaims.remove(registryKey);
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
    }

    /** Renewal failure is a per-row fence: drop ownership, never write with the dead token. */
    private boolean renewOrFence(LineageV2TransitionStore v2store, String recordId,
                                 String targetName, String token, java.time.Duration lease,
                                 String registryKey) {
        boolean renewed;
        try {
            renewed = v2store.renewClaim(recordId, targetName, token, lease);
        } catch (RuntimeException e) {
            logger.error("v2 lease renewal errored for {}: {}", recordId, e.getMessage());
            renewed = false;
        }
        if (!renewed) {
            ownedClaims.remove(registryKey);
            logger.warn("v2 lease renewal failed for {} — ownership dropped (fence latch)",
                    recordId);
        }
        return renewed;
    }

    /** Strict per-row reread; {@code null} on absence, malformed row, or infra failure. */
    private LineageTargetLifecycle rereadLifecycle(LineageV2TransitionStore v2store,
                                                   String recordId, String targetName) {
        try {
            LineageJournalRowV2 row = v2store.findV2ByRecordId(recordId);
            return row == null ? null : row.targetLifecycles().get(targetName);
        } catch (RuntimeException e) {
            logger.error("v2 lifecycle reread failed for {}: {}", recordId, e.getMessage());
            return null;
        }
    }

    /** The record projection of a v2 row, or {@code null} when it cannot be rebuilt. */
    private LineageRecord recordOf(LineageJournalRowV2 row) {
        try {
            return LineageRecord.fromV2(row.event());
        } catch (RuntimeException e) {
            logger.error("v2 row {} cannot project to a record: {}",
                    row.event().deliveryId(), e.getMessage());
            return null;
        }
    }

    /** §7's last gate for the v2 route (persisted through the fenced transition, never 3-arg). */
    private String preSinkViolationV2(LineageJournalRowV2 row, LineageRecord record) {
        try {
            LineageRepositoryScope.validate(record.repositoryId(),
                    row.event().inputs(), row.event().outputs());
            LineageRepositoryScope.validateArtifactOperation(row.event().operationId(),
                    row.event().inputs(), row.event().outputs());
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** §8-c: the D-rest walk's only cursor advance — monotonic CAS, zero-means-stop. */
    private boolean advanceCursorMonotonicChecked(String targetName, String repositoryId,
                                                  long sequenceNumber) {
        ProjectionCursor updated = new ProjectionCursor(targetName, repositoryId,
                sequenceNumber, java.time.Instant.now());
        boolean advanced;
        try {
            advanced = cursorStore.advanceCursorMonotonic(updated);
        } catch (RuntimeException e) {
            logger.error("Monotonic cursor advance errored for repo '{}': {}", repositoryId,
                    e.getMessage());
            advanced = false;
        }
        if (!advanced) {
            logger.warn("Monotonic cursor advance unconfirmed for repo '{}' seq {} — halting",
                    repositoryId, sequenceNumber);
        }
        return advanced;
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
     * §7's last gate: the same repository-scope and artifact-operation checks the canonical
     * constructor ran, re-run against the <b>record</b> the sink is about to receive.
     *
     * <p>Decode already re-verified the envelope, so for a codec-produced entry the only way
     * this fires is a record/envelope pairing that {@link LineageJournalEntry}'s constructor
     * does not cross-check (it verifies recordId and schemaVersion, not repository scope) — or,
     * after Slice 4, an in-memory path that never went through the codec. That is precisely what
     * a last gate is for: it guards the boundary, not the happy path. v1 rows pass through — no
     * typed endpoints to check; legacy stays best-effort (§8).
     *
     * @return the violation, or {@code null} if the record may be published
     */
    private String preSinkViolation(LineageJournalEntry entry) {
        if (!(entry.envelope() instanceof LineageJournalEntry.V2 v2)) {
            return null;
        }
        try {
            LineageRepositoryScope.validate(entry.record().repositoryId(),
                    v2.event().inputs(), v2.event().outputs());
            LineageRepositoryScope.validateArtifactOperation(v2.event().operationId(),
                    v2.event().inputs(), v2.event().outputs());
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Persists the gate's verdict. REJECTED, not FAILED: a validation failure is a property of
     * the data and retrying cannot change the data. REJECTED is terminal but deliberately not
     * purge-eligible ({@link LineagePublishStatus#isPurgeEligible()}) — the row is the only
     * record of the violation until increment E's durable record exists.
     *
     * @return {@code true} if the transition was persisted
     */
    private boolean rejectAtGate(LineageRecord record, String targetName, String violation) {
        logger.warn("Pre-sink gate refused record {} for '{}': {}",
                record.recordId(), targetName, violation);
        int updated = journalStore.updatePublishStatus(record.recordId(), targetName,
                LineagePublishStatus.REJECTED);
        if (updated == 0) {
            logger.warn("REJECTED transition for {} was not persisted (raced?)",
                    record.recordId());
        }
        if (lineageMetrics != null) lineageMetrics.recordFail(targetName);
        return updated > 0;
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
        // Every registry exit includes shutdown (B3): orphaned claims recover via lease
        // expiry + the reaper on the next start.
        ownedClaims.clear();
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
