package jp.aegif.nemaki.archive;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.RetentionLogDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * Scheduler for retention lifecycle management.
 *
 * Two jobs:
 * - Job A (Live→Archive): Archives documents in two phases:
 *   1. Expiration-based: documents whose cmis:rm_expirationDate has passed.
 *   2. Inactivity-based: documents without cmis:rm_expirationDate that have not been
 *      modified for retention.archive.local.after.days days.
 * - Job B (Archive→Cold): Moves ARCHIVED_LOCAL archives to cold storage after retention.archive.cold.after.days.
 *
 * Follows the DirectorySyncScheduler pattern for Spring cron scheduling.
 */
public class RetentionScheduler {

    private static final Log log = LogFactory.getLog(RetentionScheduler.class);

    private ContentService contentService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;
    private LongTermStorageAdapterFactory longTermStorageAdapterFactory;
    private RetentionLogDaoService retentionLogDaoService;
    private ThreadLockService threadLockService;
    private NemakiCachePool nemakiCachePool;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> coldMoveTask;
    private ScheduledFuture<?> localArchiveTask;
    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    public void init() {
        if (initialized) {
            return;
        }

        synchronized (initLock) {
            if (initialized) {
                return;
            }

            boolean retentionEnabled = propertyManager.readBoolean(PropertyKey.RETENTION_ENABLED);
            if (!retentionEnabled) {
                log.info("Retention scheduling is disabled");
                initialized = true;
                return;
            }

            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "RetentionScheduler");
                t.setDaemon(true);
                return t;
            });

            // Schedule local archive job (Live→Archive)
            String localCron = propertyManager.readValue(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_LOCAL);
            if (localCron != null && !localCron.trim().isEmpty() && CronExpression.isValidExpression(localCron)) {
                scheduleNextLocalArchive(localCron);
                log.info("Retention scheduler initialized with local-archive cron: " + localCron);
            } else {
                log.info("Retention local-archive cron not configured or invalid, skipping local archive scheduling");
            }

            // Schedule cold move job (Archive→Cold)
            String coldCron = propertyManager.readValue(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_COLD);
            if (coldCron != null && !coldCron.trim().isEmpty() && CronExpression.isValidExpression(coldCron)) {
                scheduleNextColdMove(coldCron);
                log.info("Retention scheduler initialized with cold-move cron: " + coldCron);
            } else {
                log.info("Retention cold-move cron not configured or invalid, skipping cold move scheduling");
            }

            initialized = true;
        }
    }

    // ========================
    // Local Archive Job (Live→Archive)
    // ========================

    private void scheduleNextLocalArchive(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = cron.next(now);

        if (next == null) {
            log.warn("Could not determine next local-archive execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = java.time.Duration.between(now, next).toMillis();

        localArchiveTask = scheduler.schedule(() -> {
            try {
                executeLocalArchive();
            } finally {
                scheduleNextLocalArchive(cronExpression);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        log.debug("Next local-archive scheduled for: " + next);
    }

    private void executeLocalArchive() {
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

    private void scheduleNextColdMove(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = cron.next(now);

        if (next == null) {
            log.warn("Could not determine next cold-move execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = java.time.Duration.between(now, next).toMillis();

        coldMoveTask = scheduler.schedule(() -> {
            try {
                executeColdMove();
            } finally {
                scheduleNextColdMove(cronExpression);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        log.debug("Next cold-move scheduled for: " + next);
    }

    private void executeColdMove() {
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
            contentService.deleteDocument(systemContext, repositoryId, documentId, true, false);
            nemakiCachePool.get(repositoryId).removeCmisCache(documentId);
            result.incrementSucceeded();
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
                    boolean deleted = contentService.deleteArchiveContent(repositoryId, archiveId);
                    if (!deleted) {
                        // Local blob still exists — clean up the cold storage object
                        // to prevent orphaned/duplicate versions on retry.
                        // removeProtection and delete are in separate try blocks so that
                        // delete is always attempted even if removeProtection fails
                        // (e.g. when Legal Hold was never applied due to misconfiguration).
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

                log.info("Successfully moved archive to cold storage: " + archiveId
                        + " (mode: " + coldMoveMode + ")");
                return true;
            } finally {
                contentStream.close();
            }

        } catch (Exception e) {
            // Clean up orphaned cold storage blob.
            // removeProtection and delete are in separate try blocks so that
            // delete is always attempted even if removeProtection fails
            // (e.g. when enforceImmutability failed because Legal Hold is not configured).
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

            // Reset all cold-move metadata so the archive is eligible for retry
            try {
                contentService.resetColdMoveMetadata(repositoryId, archiveId);
            } catch (Exception revertEx) {
                log.error("Failed to reset cold-move metadata after failure: " + revertEx.getMessage());
            }
            throw new RuntimeException("Cold move failed for archive: " + archiveId, e);
        }
    }

    public void destroy() {
        if (localArchiveTask != null) {
            localArchiveTask.cancel(false);
        }
        if (coldMoveTask != null) {
            coldMoveTask.cancel(false);
        }
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

    public boolean isSchedulerActive() {
        return scheduler != null && !scheduler.isShutdown();
    }

    // Setters for Spring DI
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
