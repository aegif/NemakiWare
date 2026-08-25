package jp.aegif.nemaki.archive;

import java.io.InputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

import java.time.Duration;
import java.time.LocalDateTime;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageMode;
import jp.aegif.nemaki.rest.purview.journal.LineageEmitter;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.EndpointKind;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.journal.LineageFact;
import jp.aegif.nemaki.rest.purview.journal.LineageFactEmission;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineageTargetSink;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.RetentionLogDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Cron-based scheduler for retention lifecycle management.
 * Dynamically reads cron configuration every 60 seconds,
 * so changes made via the admin UI or CouchDB take effect without restart.
 *
 * <p>Two independent jobs, each with its own generation token to prevent
 * double-scheduling when cron changes occur while a job is in-flight:</p>
 * <ul>
 *   <li>Job A (Live→Archive): Archives documents in two phases:
 *       expiration-based and inactivity-based.</li>
 *   <li>Job B (Archive→Cold): Moves ARCHIVED_LOCAL archives to cold storage.</li>
 * </ul>
 */
public class RetentionScheduler {

    private static final Log log = LogFactory.getLog(RetentionScheduler.class);
    private static final long CONFIG_CHECK_INTERVAL_SECONDS = 60;

    private ContentService contentService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;
    private LongTermStorageAdapterFactory longTermStorageAdapterFactory;
    private RetentionLogDaoService retentionLogDaoService;
    private ThreadLockService threadLockService;
    private NemakiCachePool nemakiCachePool;
    /**
     * Optional. When wired and lineage.leader-election.enabled=true,
     * retention runs only on the leader. Otherwise behaves single-replica.
     */
    private jp.aegif.nemaki.rest.purview.journal.LeaderElection leaderElection;

    public void setLeaderElection(jp.aegif.nemaki.rest.purview.journal.LeaderElection leaderElection) {
        this.leaderElection = leaderElection;
    }

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> localArchiveTask;
    private ScheduledFuture<?> coldMoveTask;
    private volatile String activeLocalCron;
    private volatile String activeColdCron;
    private final AtomicLong localArchiveGeneration = new AtomicLong(0);
    private final AtomicLong coldMoveGeneration = new AtomicLong(0);

    public void init() {
        // Pool size 3: local archive job + cold move job + config polling
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "RetentionScheduler");
            t.setDaemon(true);
            return t;
        });

        reconcileSchedule();

        scheduler.scheduleWithFixedDelay(
                this::reconcileSchedule,
                CONFIG_CHECK_INTERVAL_SECONDS,
                CONFIG_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("Retention scheduler initialized (activeLocalCron=" + activeLocalCron
                + ", activeColdCron=" + activeColdCron
                + ", leaderElection=" + (leaderElection != null && leaderElection.isEnabled() ? "enabled" : "disabled")
                + ")");
    }

    void reconcileSchedule() {
        try {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }

            boolean retentionEnabled = propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED);

            // Reconcile local archive cron
            String currentLocalCron = retentionEnabled ? resolveValidCron(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_LOCAL) : null;
            if (!Objects.equals(currentLocalCron, activeLocalCron)) {
                cancelTask(localArchiveTask);
                localArchiveTask = null;
                long gen = localArchiveGeneration.incrementAndGet();

                if (currentLocalCron == null) {
                    if (activeLocalCron != null) {
                        log.info("Retention local-archive schedule stopped (was: " + activeLocalCron + ")");
                    }
                    activeLocalCron = null;
                } else {
                    log.info("Retention local-archive schedule updated: " + activeLocalCron + " -> " + currentLocalCron);
                    activeLocalCron = currentLocalCron;
                    scheduleNextLocalArchive(currentLocalCron, gen);
                }
            }

            // Reconcile cold move cron
            String currentColdCron = retentionEnabled ? resolveValidCron(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_COLD) : null;
            if (!Objects.equals(currentColdCron, activeColdCron)) {
                cancelTask(coldMoveTask);
                coldMoveTask = null;
                long gen = coldMoveGeneration.incrementAndGet();

                if (currentColdCron == null) {
                    if (activeColdCron != null) {
                        log.info("Retention cold-move schedule stopped (was: " + activeColdCron + ")");
                    }
                    activeColdCron = null;
                } else {
                    log.info("Retention cold-move schedule updated: " + activeColdCron + " -> " + currentColdCron);
                    activeColdCron = currentColdCron;
                    scheduleNextColdMove(currentColdCron, gen);
                }
            }
        } catch (Exception e) {
            log.warn("Error during retention schedule reconciliation, will retry on next poll: " + e.getMessage());
        }
    }

    private String resolveValidCron(String propertyKey) {
        String cron = propertyManager.readValue(propertyKey);
        if (cron == null || cron.isBlank()) {
            return null;
        }
        cron = cron.trim();
        if (!CronExpression.isValidExpression(cron)) {
            log.warn("Invalid retention cron expression for " + propertyKey + ": " + cron);
            return null;
        }
        return cron;
    }

    private void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    // ========================
    // Local Archive Job (Live→Archive)
    // ========================

    private void scheduleNextLocalArchive(String cronExpression, long gen) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.next(now);

        if (next == null) {
            log.warn("Could not determine next local-archive execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = Duration.between(now, next).toMillis();

        try {
            localArchiveTask = scheduler.schedule(() -> {
                try {
                    executeLocalArchive();
                } finally {
                    try {
                        if (localArchiveGeneration.get() != gen) {
                            log.debug("Retention local-archive generation changed, not re-arming");
                            return;
                        }
                        boolean enabled = propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED);
                        String effectiveCron = enabled ? resolveValidCron(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_LOCAL) : null;
                        if (effectiveCron != null) {
                            activeLocalCron = effectiveCron;
                            scheduleNextLocalArchive(effectiveCron, gen);
                        } else {
                            activeLocalCron = null;
                            log.info("Retention local-archive cron cleared after execution");
                        }
                    } catch (Exception e) {
                        log.warn("Error re-arming retention local-archive schedule, next poll will recover: " + e.getMessage());
                    }
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            log.debug("Next local-archive scheduled for: " + next + " (delay: " + delayMillis + "ms)");
        } catch (RejectedExecutionException e) {
            log.debug("Retention local-archive schedule rejected (scheduler shutting down)");
        }
    }

    private void executeLocalArchive() {
        if (!propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED)) {
            log.debug("Retention local-archive skipped: disabled (dynamic check)");
            return;
        }
        // Multi-replica safety: only the leader performs retention. Without
        // this gate every replica would race on archive moves / deletes.
        if (leaderElection != null && !leaderElection.isLeader("retention-local-archive")) {
            log.debug("Retention local-archive skipped: not leader for 'retention-local-archive'");
            return;
        }

        log.info("Starting scheduled local-archive job");

        try {
            Set<String> repositoryIds = repositoryInfoMap.keys();

            for (String repositoryId : repositoryIds) {
                RetentionJobResult result = new RetentionJobResult("local-archive", repositoryId);
                GregorianCalendar jobStartedAt = new GregorianCalendar();

                try {
                    // Safety check: refuse to run if archive creation is disabled.
                    // Without archive creation, deleteDocument permanently destroys data.
                    boolean archiveEnabled = propertyManager.readBoolean(
                            repositoryId, PropertyKey.ARCHIVE_CREATE_ENABLED);
                    if (!archiveEnabled) {
                        log.error("Retention local-archive job ABORTED for repository " + repositoryId
                                + ": archive.create.enabled=false — expired documents would be permanently deleted"
                                + " without an archive copy. Enable archive creation or disable retention scheduling.");
                        continue;
                    }

                    // Phase 1: Expiration-based archiving (cmis:rm_expirationDate)
                    GregorianCalendar now = new GregorianCalendar();
                    List<String> expiredIds = contentService.getExpiredDocumentIds(repositoryId, now);
                    log.info("Expiration-based archive candidates for repository " + repositoryId + ": " + expiredIds.size());

                    for (String documentId : expiredIds) {
                        archiveDocument(repositoryId, documentId, "expired", result);
                    }

                    // Phase 2: Inactivity-based archiving (retention.archive.local.after.days)
                    String localAfterDaysStr = propertyManager.readValue(
                            repositoryId, PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS);
                    if (localAfterDaysStr != null && !localAfterDaysStr.trim().isEmpty()) {
                        try {
                            int localAfterDays = Integer.parseInt(localAfterDaysStr.trim());
                            if (localAfterDays > 0) {
                                GregorianCalendar cutoff = new GregorianCalendar();
                                cutoff.add(Calendar.DAY_OF_YEAR, -localAfterDays);

                                List<String> staleIds = contentService.getStaleDocumentIds(repositoryId, cutoff);
                                log.info("Inactivity-based archive candidates for repository " + repositoryId
                                        + " (>" + localAfterDays + " days): " + staleIds.size());

                                for (String documentId : staleIds) {
                                    archiveDocument(repositoryId, documentId, "stale", result);
                                }
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Invalid retention.archive.local.after.days value for repository "
                                    + repositoryId + ": " + localAfterDaysStr);
                        }
                    }

                } catch (Exception e) {
                    log.error("Error during local-archive for repository " + repositoryId + ": " + e.getMessage(), e);
                }

                log.info("Local-archive completed: " + result);
                persistMigrationLog(repositoryId, result, jobStartedAt);
            }
        } catch (Exception e) {
            log.error("Error during local-archive job: " + e.getMessage(), e);
        }
    }

    // ========================
    // Cold Move Job (Archive→Cold)
    // ========================

    private void scheduleNextColdMove(String cronExpression, long gen) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.next(now);

        if (next == null) {
            log.warn("Could not determine next cold-move execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = Duration.between(now, next).toMillis();

        try {
            coldMoveTask = scheduler.schedule(() -> {
                try {
                    executeColdMove();
                } finally {
                    try {
                        if (coldMoveGeneration.get() != gen) {
                            log.debug("Retention cold-move generation changed, not re-arming");
                            return;
                        }
                        boolean enabled = propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED);
                        String effectiveCron = enabled ? resolveValidCron(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_COLD) : null;
                        if (effectiveCron != null) {
                            activeColdCron = effectiveCron;
                            scheduleNextColdMove(effectiveCron, gen);
                        } else {
                            activeColdCron = null;
                            log.info("Retention cold-move cron cleared after execution");
                        }
                    } catch (Exception e) {
                        log.warn("Error re-arming retention cold-move schedule, next poll will recover: " + e.getMessage());
                    }
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            log.debug("Next cold-move scheduled for: " + next + " (delay: " + delayMillis + "ms)");
        } catch (RejectedExecutionException e) {
            log.debug("Retention cold-move schedule rejected (scheduler shutting down)");
        }
    }

    private void executeColdMove() {
        if (leaderElection != null && !leaderElection.isLeader("retention-cold-move")) {
            log.debug("Retention cold-move skipped: not leader for 'retention-cold-move'");
            return;
        }
        executeColdMoveInternal();
    }

    private void executeColdMoveInternal() {
        if (!propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED)) {
            log.debug("Retention cold-move skipped: disabled (dynamic check)");
            return;
        }

        log.info("Starting scheduled cold-move job");

        String coldAfterDaysStr = propertyManager.readValue(PropertyKey.RETENTION_ARCHIVE_COLD_AFTER_DAYS);
        int coldAfterDays = 90;
        try {
            if (coldAfterDaysStr != null) {
                coldAfterDays = Integer.parseInt(coldAfterDaysStr.trim());
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid retention.archive.cold.after.days value, using default 90");
        }

        GregorianCalendar cutoffDate = new GregorianCalendar();
        cutoffDate.add(Calendar.DAY_OF_YEAR, -coldAfterDays);

        LongTermStorageAdapter adapter = longTermStorageAdapterFactory.getAdapter();
        if (adapter == null) {
            log.error("Long-term storage adapter is not configured, skipping cold-move");
            return;
        }

        try {
            Set<String> repositoryIds = repositoryInfoMap.keys();

            for (String repositoryId : repositoryIds) {
                RetentionJobResult result = new RetentionJobResult("cold-move", repositoryId);
                GregorianCalendar jobStartedAt = new GregorianCalendar();

                try {
                    List<Archive> candidates = contentService.getArchivesForColdTransition(repositoryId, cutoffDate);
                    log.info("Cold-move candidates for repository " + repositoryId + ": " + candidates.size());

                    for (Archive archive : candidates) {
                        result.incrementProcessed();

                        try {
                            boolean moved = moveToCold(repositoryId, archive, adapter);
                            if (moved) {
                                result.incrementSucceeded();
                            } else {
                                result.incrementSkipped();
                                result.addSkippedDocumentId(archive.getId());
                            }
                        } catch (Exception e) {
                            log.error("Failed to cold-move archive " + archive.getId() + ": " + e.getMessage(), e);
                            result.incrementFailed();
                        }
                    }
                } catch (Exception e) {
                    log.error("Error during cold-move for repository " + repositoryId + ": " + e.getMessage(), e);
                }

                log.info("Cold-move completed: " + result);

                // Persist migration log
                persistMigrationLog(repositoryId, result, jobStartedAt);
            }
        } catch (Exception e) {
            log.error("Error during cold-move job: " + e.getMessage(), e);
        }
    }

    private void persistMigrationLog(String repositoryId, RetentionJobResult result, GregorianCalendar startedAt) {
        if (retentionLogDaoService == null) {
            return;
        }

        try {
            RetentionMigrationLog migrationLog = new RetentionMigrationLog(result.getJobName(), repositoryId);
            migrationLog.setStartedAt(startedAt);
            migrationLog.setCompletedAt(new GregorianCalendar());
            migrationLog.setProcessed(result.getProcessed());
            migrationLog.setSucceeded(result.getSucceeded());
            migrationLog.setFailed(result.getFailed());
            migrationLog.setStatus(migrationLog.computeStatus());

            if (!result.getSkippedDocumentIds().isEmpty()) {
                migrationLog.setDetails("Skipped document IDs: "
                        + String.join(", ", result.getSkippedDocumentIds()));
            }

            retentionLogDaoService.createLog(repositoryId, migrationLog);

            // Clean up old logs, keep at most 100
            retentionLogDaoService.deleteOldLogs(repositoryId, 100);
        } catch (Exception e) {
            log.warn("Failed to persist migration log for " + repositoryId + ": " + e.getMessage());
        }
    }

    /**
     * Archive a single document with lock acquisition, error handling, and result tracking.
     */
    private void archiveDocument(String repositoryId, String documentId, String reason, RetentionJobResult result) {
        result.incrementProcessed();
        Lock lock = threadLockService.getWriteLock(repositoryId, documentId);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring lock for " + reason + " document: " + documentId);
            result.incrementSkipped();
            result.addSkippedDocumentId(documentId);
            return;
        }
        if (!acquired) {
            log.warn("Skipping " + reason + " document (lock not acquired): " + documentId);
            result.incrementSkipped();
            result.addSkippedDocumentId(documentId);
            return;
        }
        try {
            CallContext systemContext = new SystemCallContext(repositoryId);
            // §3: the operation id is issued when the business operation starts, and the typed
            // ARCHIVE endpoint needs the document's name — readable only before the deletion
            // that archives it. The read is lineage-only and guarded: a null name loses this
            // one fact inside the facade (loudly), never the archival.
            String operationId = java.util.UUID.randomUUID().toString();
            String documentName = null;
            try {
                jp.aegif.nemaki.model.Document documentBeforeArchive =
                        contentService.getDocument(repositoryId, documentId);
                documentName = documentBeforeArchive != null
                        ? documentBeforeArchive.getName() : null;
            } catch (Exception e) {
                log.warn("Could not read document name for lineage (archival continues): "
                        + e.getMessage());
            }

            contentService.deleteDocument(systemContext, repositoryId, documentId, true, false);
            nemakiCachePool.get(repositoryId).removeCmisCache(documentId);
            result.incrementSucceeded();

            // Lineage: one version-free fact; the emitter projects it (v1 today, v2 after the
            // §6-a flip). The v1 strings ride along verbatim — they are the eventKey.
            {
                final String lineageDocumentName = documentName;
                LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () -> {
                    String occurredAt = java.time.Instant.now().toString();
                    return new LineageFact(
                            repositoryId,
                            LineageProcessType.ARCHIVE_LOCAL,
                            operationId,
                            occurredAt,
                            java.util.List.of(LineageEndpoint.document(
                                    repositoryId, documentId, lineageDocumentName)),
                            java.util.List.of(LineageEndpoint.archive(
                                    repositoryId, documentId, documentId,
                                    java.time.Instant.parse(occurredAt).toEpochMilli())),
                            lineageTargets(),
                            null,
                            new LineageFact.LegacyV1Projection(
                                    LineageProcessType.ARCHIVE_LOCAL,
                                    java.util.List.of(LineageEvent.qualifiedName(
                                            repositoryId, documentId)),
                                    java.util.List.of("nemaki://" + repositoryId
                                            + "/archives/" + documentId),
                                    java.util.Map.of("reason", reason)));
                }, "repo=" + repositoryId + " op=" + operationId + " type=ARCHIVE_LOCAL");
            }

            log.info("Archived " + reason + " document: " + documentId);
        } catch (Exception e) {
            log.error("Failed to archive " + reason + " document " + documentId + ": " + e.getMessage(), e);
            result.incrementFailed();
        } finally {
            lock.unlock();
        }
    }

    private boolean moveToCold(String repositoryId, Archive archive, LongTermStorageAdapter adapter) {
        String archiveId = archive.getId();
        String originalId = archive.getOriginalId();
        // §3: the lineage operation id is issued when the business operation starts (the state
        // transition below is already part of it), not when the emission happens afterwards.
        final String lineageOperationId = java.util.UUID.randomUUID().toString();
        boolean coldPutSucceeded = false;
        String storageRef = null;

        // Set transitional state
        contentService.updateArchiveState(repositoryId, archiveId,
                Archive.STATE_COLD_MOVING, null, null);

        try {
            InputStream contentStream = contentService.getArchiveContentStream(repositoryId, archiveId);

            if (contentStream == null) {
                log.warn("No content stream available for archive: " + archiveId + " - skipping cold move");
                contentService.updateArchiveState(repositoryId, archiveId,
                        Archive.STATE_ARCHIVED_LOCAL, null, null);
                return false;
            }

            try {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("name", archive.getName() != null ? archive.getName() : "");
                metadata.put("mimeType", archive.getMimeType() != null ? archive.getMimeType() : "");
                metadata.put("originalId", originalId != null ? originalId : "");

                storageRef = adapter.put(repositoryId, originalId, contentStream, metadata);
                coldPutSucceeded = true;
                adapter.enforceImmutability(repositoryId, originalId);

                Map<String, String> contentRef = new HashMap<>();
                if (storageRef != null) {
                    contentRef.put("ref", storageRef);
                }
                contentRef.put("type", propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE));

                boolean keepLocalCopy = propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY);
                String coldMoveMode = keepLocalCopy ? "COPY" : "MOVE";
                GregorianCalendar now = new GregorianCalendar();

                if (keepLocalCopy) {
                    contentService.updateArchiveState(repositoryId, archiveId,
                            Archive.STATE_ARCHIVED_LOCAL, contentRef, now);
                } else {
                    contentService.updateArchiveState(repositoryId, archiveId,
                            Archive.STATE_ARCHIVED_COLD, contentRef, now);
                }

                contentService.updateArchiveColdMoveMode(repositoryId, archiveId, coldMoveMode);

                // Delete local content only in MOVE mode
                if (!keepLocalCopy) {
                    // P3-3: the disposition is recorded BEFORE the bytes go, and a ledger that
                    // cannot be written refuses the deletion. The asymmetry with the capture
                    // boundary is deliberate — there a refusal would destroy content already
                    // fetched, here it costs a delay and nothing else. Recording afterwards
                    // would leave, on a crash in between, content deleted that nothing records
                    // disposing of, with no object left for anyone to notice.
                    jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation authorisation =
                            authoriseColdDisposition(repositoryId, archiveId, originalId);
                    if (!authorisation.mayProceed()) {
                        log.warn("MOVE mode: not deleting local archive content for " + archiveId
                                + " — " + authorisation.refusedReason());
                        // The cold copy is already written and immutable. Leaving the local
                        // copy is the COPY-mode state, which is a real state this product
                        // supports, so the archive is marked accordingly rather than left
                        // saying MOVE with content still present.
                        contentService.updateArchiveState(repositoryId, archiveId,
                                Archive.STATE_ARCHIVED_LOCAL, contentRef, now);
                        contentService.updateArchiveColdMoveMode(repositoryId, archiveId, "COPY");
                        return false;
                    }
                    boolean deleted = contentService.deleteArchiveContent(repositoryId, archiveId);
                    if (!deleted) {
                        log.error("MOVE mode: failed to delete local archive content for " + archiveId
                                + " — cleaning up cold storage and resetting for retry");
                        try {
                            adapter.removeProtection(repositoryId, originalId);
                        } catch (Exception rpEx) {
                            log.warn("removeProtection failed during cleanup (will still attempt delete): "
                                    + rpEx.getMessage());
                        }
                        try {
                            adapter.delete(repositoryId, originalId, storageRef);
                        } catch (Exception delEx) {
                            log.error("Failed to delete cold object after local delete failure: "
                                    + delEx.getMessage());
                        }
                        contentService.resetColdMoveMetadata(repositoryId, archiveId);
                        return false;
                    }
                    log.info("Move mode: deleted local archive content after cold storage write: " + archiveId);
                }

                // Lineage: one version-free fact. The typed ARCHIVE endpoint requires the
                // original object id — an archive without one cannot make a valid catalog
                // entity (the Atlas type marks it mandatory), so such a row loses this one
                // fact inside the facade, with a WARN naming it, rather than publishing a
                // blank. archivedAt comes from the archive record itself.
                {
                    // storageRef is reassigned earlier in this method; the lambda needs an
                    // effectively-final copy of the value as it stands now. Everything else
                    // lineage-only (config, archive getters) runs inside the boundary.
                    final String finalStorageRef = storageRef;
                    final String finalOriginalId = originalId;
                    LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () -> {
                        String archiveName = archive.getName();
                        java.util.GregorianCalendar archivedAtCal = archive.getArchivedAt();
                        String occurredAt = java.time.Instant.now().toString();
                        long archivedAtMillis = archivedAtCal != null
                                ? archivedAtCal.getTimeInMillis()
                                : java.time.Instant.parse(occurredAt).toEpochMilli();
                        return new LineageFact(
                                repositoryId,
                                LineageProcessType.ARCHIVE_COLD,
                                lineageOperationId,
                                occurredAt,
                                // Through the producer factory, not the canonical constructor:
                                // §2's attribute limits are applied at that boundary, and a
                                // hand-built endpoint would skip them (v2.3.26).
                                java.util.List.of(LineageEndpoint.archive(
                                        repositoryId, archiveId, finalOriginalId,
                                        archivedAtMillis, archiveName)),
                                java.util.List.of(LineageEndpoint.coldStorage(
                                        repositoryId,
                                        finalStorageRef != null ? finalStorageRef : archiveId,
                                        adapter.getClass().getSimpleName())),
                                lineageTargets(),
                                null,
                                new LineageFact.LegacyV1Projection(
                                        LineageProcessType.ARCHIVE_COLD,
                                        java.util.List.of("nemaki://" + repositoryId
                                                + "/archives/" + archiveId),
                                        java.util.List.of("cold://" + (finalStorageRef != null
                                                ? finalStorageRef : archiveId)),
                                        java.util.Map.of(
                                                "originalId", finalOriginalId != null ? finalOriginalId : "",
                                                "coldMoveMode", coldMoveMode)));
                    }, "repo=" + repositoryId + " op=" + lineageOperationId + " type=ARCHIVE_COLD");
                }

                log.info("Successfully moved archive to cold storage: " + archiveId
                        + " (mode: " + coldMoveMode + ")");
                return true;
            } finally {
                contentStream.close();
            }

        } catch (Exception e) {
            if (coldPutSucceeded && originalId != null) {
                try {
                    adapter.removeProtection(repositoryId, originalId);
                } catch (Exception rpEx) {
                    log.warn("removeProtection failed during cleanup (will still attempt delete): "
                            + rpEx.getMessage());
                }
                try {
                    adapter.delete(repositoryId, originalId, storageRef);
                    log.info("Cleaned up orphaned cold storage blob: originalId=" + originalId
                            + ", storageRef=" + storageRef);
                } catch (Exception delEx) {
                    log.error("Failed to clean up orphaned cold storage blob originalId=" + originalId
                            + ", storageRef=" + storageRef + " — manual cleanup may be required: "
                            + delEx.getMessage());
                }
            }

            try {
                contentService.resetColdMoveMetadata(repositoryId, archiveId);
            } catch (Exception revertEx) {
                log.error("Failed to reset cold-move metadata after failure: " + revertEx.getMessage());
            }
            throw new RuntimeException("Cold move failed for archive: " + archiveId, e);
        }
    }

    public void destroy() {
        cancelTask(localArchiveTask);
        cancelTask(coldMoveTask);
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
        log.info("Retention scheduler stopped");
    }

    /** Lineage-only config read; never throws (fail-open — a producer must not fail the op). */
    private java.util.List<String> lineageTargets() {
        try {
            LineageConfig lc = getLineageConfig();
            return lc != null ? lc.getTargets() : java.util.List.of();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private LineageConfig getLineageConfig() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean(LineageConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The active emitter for this repository, or {@code null} when lineage is off. */
    private LineageEmitter resolveLineageEmitter(String repositoryId) {
        try {
            LineageConfig config = getLineageConfig();
            if (config == null) return null;
            LineageMode mode = config.getModeForRepository(repositoryId);
            if (mode == LineageMode.DISABLED) return null;

            LineageJournalStore store = SpringContext.getApplicationContext()
                    .getBean(LineageJournalStore.class);
            @SuppressWarnings("unchecked")
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) SpringContext.getApplicationContext()
                    .getBeansOfType(LineageTargetSink.class).values().stream().toList();
            return config.createEmitterForMode(mode, store, sinks);
        } catch (Exception e) {
            log.warn("Lineage emitter resolution failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    public boolean isSchedulerActive() {
        return scheduler != null && !scheduler.isShutdown();
    }

    String getActiveLocalCron() {
        return activeLocalCron;
    }

    String getActiveColdCron() {
        return activeColdCron;
    }

    // Setters for Spring DI
    /**
     * Builds the disposition entry for a cold MOVE and says whether the delete may proceed.
     *
     * <p>The rule is read HERE, from the same property manager the job read it from, so the
     * entry commits to the settings this run actually acted under rather than to whatever is
     * configured when somebody later looks.
     */
    private jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation authoriseColdDisposition(
            String repositoryId, String archiveId, String originalId) {
        if (dispositionRecorder == null) {
            // Same refusal as an unreachable ledger, for the same reason: an irreversible act
            // with no record of it is the outcome this whole path exists to prevent.
            return new jp.aegif.nemaki.evidence.DispositionRecorder.Authorisation(false,
                    "the disposition recorder is not wired on this node");
        }
        Map<String, String> rule = jp.aegif.nemaki.evidence.DispositionRecorder.coldMoveRule(
                propertyManager.readValue(PropertyKey.RETENTION_ARCHIVE_COLD_AFTER_DAYS),
                propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY),
                propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE),
                propertyManager.readValue(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_COLD));
        // The ORIGINAL object id is the subject, not the archive row id: that is the id a
        // reader looking for "what happened to this record" would have.
        String subject = originalId != null && !originalId.isBlank() ? originalId : archiveId;
        return dispositionRecorder.authoriseDisposition(repositoryId,
                jp.aegif.nemaki.evidence.DispositionRecorder.Act
                        .LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE,
                subject, rule, java.time.Instant.now().toString());
    }

    private jp.aegif.nemaki.evidence.DispositionRecorder dispositionRecorder;

    /**
     * Wired explicitly in {@code serviceContext.xml}, deliberately NOT by annotation.
     *
     * <p>Every other dependency of this bean is an explicit {@code <property>}, and this one has
     * to be too: a null recorder makes {@code moveToCold} refuse every MOVE-mode deletion, and
     * it refuses QUIETLY because refusing is the right answer when a disposition cannot be
     * recorded. An annotation that stopped firing would look exactly like a healthy deployment
     * that simply never disposes of anything.
     */
    public void setDispositionRecorder(
            jp.aegif.nemaki.evidence.DispositionRecorder dispositionRecorder) {
        this.dispositionRecorder = dispositionRecorder;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    public void setLongTermStorageAdapterFactory(LongTermStorageAdapterFactory longTermStorageAdapterFactory) {
        this.longTermStorageAdapterFactory = longTermStorageAdapterFactory;
    }

    public void setRetentionLogDaoService(RetentionLogDaoService retentionLogDaoService) {
        this.retentionLogDaoService = retentionLogDaoService;
    }

    public void setThreadLockService(ThreadLockService threadLockService) {
        this.threadLockService = threadLockService;
    }

    public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
        this.nemakiCachePool = nemakiCachePool;
    }
}
