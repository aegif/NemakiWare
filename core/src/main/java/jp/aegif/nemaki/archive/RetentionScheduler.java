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
 * Three jobs:
 * - Job A (Live→Archive): Archives documents whose cmis:rm_expirationDate has passed,
 *   with fallback to retention.archive.local.after.days for documents without expiration date.
 * - Job B (Archive→Cold): Moves ARCHIVED_LOCAL archives to cold storage after retention.archive.cold.after.days
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
                    // Phase 1: Expiration-based archiving (cmis:rm_expirationDate)
                    GregorianCalendar now = new GregorianCalendar();
                    List<String> expiredIds = contentService.getExpiredDocumentIds(repositoryId, now);
                    log.info("Expiration-based archive candidates for repository " + repositoryId + ": " + expiredIds.size());

                    for (String documentId : expiredIds) {
                        result.incrementProcessed();
                        Lock lock = threadLockService.getWriteLock(repositoryId, documentId);
                        boolean acquired = false;
                        try {
                            acquired = lock.tryLock(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("Interrupted while acquiring lock for expired document: " + documentId);
                            result.incrementSkipped();
                            result.addSkippedDocumentId(documentId);
                            continue;
                        }
                        if (!acquired) {
                            log.warn("Skipping expired document (lock not acquired): " + documentId);
                            result.incrementSkipped();
                            result.addSkippedDocumentId(documentId);
                            continue;
                        }
                        try {
                            CallContext systemContext = new SystemCallContext(repositoryId);
                            // Use deleteDocument to properly handle attachments, renditions, and version series
                            contentService.deleteDocument(systemContext, repositoryId, documentId, true, false);
                            nemakiCachePool.get(repositoryId).removeCmisCache(documentId);
                            result.incrementSucceeded();
                            log.info("Archived expired document: " + documentId);
                        } catch (Exception e) {
                            log.error("Failed to archive expired document " + documentId + ": " + e.getMessage(), e);
                            result.incrementFailed();
                        } finally {
                            lock.unlock();
                        }
                    }

                    // Phase 2: Fallback - lastModificationDate-based archiving
                    // For documents without cmis:rm_expirationDate
                    String localAfterDaysStr = propertyManager.readValue(PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS);
                    if (localAfterDaysStr != null && !localAfterDaysStr.trim().isEmpty()) {
                        int localAfterDays;
                        try {
                            localAfterDays = Integer.parseInt(localAfterDaysStr.trim());
                        } catch (NumberFormatException e) {
                            log.warn("Invalid retention.archive.local.after.days value: " + localAfterDaysStr);
                            localAfterDays = -1;
                        }

                        if (localAfterDays > 0) {
                            log.info("Fallback lastModificationDate-based archiving with days=" + localAfterDays
                                    + " is configured but not automatically executed (requires explicit trigger)");
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
                migrationLog.setDetails("Skipped document IDs (lock not acquired): "
                        + String.join(", ", result.getSkippedDocumentIds()));
            }

            retentionLogDaoService.createLog(repositoryId, migrationLog);

            // Clean up old logs, keep at most 100
            retentionLogDaoService.deleteOldLogs(repositoryId, 100);
        } catch (Exception e) {
            log.warn("Failed to persist migration log for " + repositoryId + ": " + e.getMessage());
        }
    }

    private boolean moveToCold(String repositoryId, Archive archive, LongTermStorageAdapter adapter) {
        String archiveId = archive.getId();

        // Set transitional state
        contentService.updateArchiveState(repositoryId, archiveId,
                Archive.STATE_COLD_MOVING, null, null);

        try {
            // Get content stream via ContentService (delegates to DAO which reads from closet DB)
            InputStream contentStream = contentService.getArchiveContentStream(repositoryId, archiveId);

            if (contentStream == null) {
                log.warn("No content stream available for archive: " + archiveId + " - skipping cold move");
                // Revert to ARCHIVED_LOCAL since we cannot move without content
                contentService.updateArchiveState(repositoryId, archiveId,
                        Archive.STATE_ARCHIVED_LOCAL, null, null);
                return false;
            }

            try {
                // Build metadata
                Map<String, String> metadata = new HashMap<>();
                metadata.put("name", archive.getName() != null ? archive.getName() : "");
                metadata.put("mimeType", archive.getMimeType() != null ? archive.getMimeType() : "");
                metadata.put("originalId", archive.getOriginalId() != null ? archive.getOriginalId() : "");

                // Store in cold storage
                String storageRef = adapter.put(repositoryId, archive.getOriginalId(), contentStream, metadata);
                adapter.enforceImmutability(repositoryId, archive.getOriginalId());

                // Build contentRef
                Map<String, String> contentRef = new HashMap<>();
                if (storageRef != null) {
                    contentRef.put("ref", storageRef);
                }
                contentRef.put("type", propertyManager.readValue(PropertyKey.LONGTERM_STORAGE_TYPE));

                // Determine cold move mode (COPY or MOVE)
                boolean keepLocalCopy = propertyManager.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY);
                String coldMoveMode = keepLocalCopy ? "COPY" : "MOVE";
                GregorianCalendar now = new GregorianCalendar();

                if (keepLocalCopy) {
                    // COPY mode: S3 has independent copy, local content remains.
                    // State stays ARCHIVED_LOCAL but coldArchivedAt and contentRef are recorded.
                    contentService.updateArchiveState(repositoryId, archiveId,
                            Archive.STATE_ARCHIVED_LOCAL, contentRef, now);
                } else {
                    // MOVE mode: Content is transferred to S3, local content will be deleted.
                    // State becomes ARCHIVED_COLD (metadata-only record in NemakiWare).
                    contentService.updateArchiveState(repositoryId, archiveId,
                            Archive.STATE_ARCHIVED_COLD, contentRef, now);
                }

                // Record cold move mode
                contentService.updateArchiveColdMoveMode(repositoryId, archiveId, coldMoveMode);

                // Delete local content only in MOVE mode
                if (!keepLocalCopy) {
                    boolean deleted = contentService.deleteArchiveContent(repositoryId, archiveId);
                    if (deleted) {
                        log.info("Move mode: deleted local archive content after cold storage write: " + archiveId);
                    } else {
                        log.warn("Move mode: failed to delete local archive content: " + archiveId);
                    }
                }

                log.info("Successfully moved archive to cold storage: " + archiveId
                        + " (mode: " + coldMoveMode + ")");
                return true;
            } finally {
                contentStream.close();
            }

        } catch (Exception e) {
            // Revert to ARCHIVED_LOCAL on failure
            try {
                contentService.updateArchiveState(repositoryId, archiveId,
                        Archive.STATE_ARCHIVED_LOCAL, null, null);
            } catch (Exception revertEx) {
                log.error("Failed to revert archive state after cold-move failure: " + revertEx.getMessage());
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
