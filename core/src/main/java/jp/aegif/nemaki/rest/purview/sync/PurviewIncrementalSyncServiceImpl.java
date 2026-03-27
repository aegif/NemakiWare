package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.publish.PurviewArchivePublishService;
import jp.aegif.nemaki.rest.purview.publish.PurviewCloudMetadataPublishService;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.publish.PurviewDocumentPublishService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneState;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneStateService;
import jp.aegif.nemaki.rest.purview.publish.PurviewTypeDefinitionPublishService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewIncrementalSyncServiceImpl implements PurviewIncrementalSyncService {

    private static final Log log = LogFactory.getLog(PurviewIncrementalSyncServiceImpl.class);
    private static final String JOB_KIND = "INCREMENTAL_SYNC";
    private static final String STREAM_KIND = "content-change-log";
    private static final String CURSOR_KIND = "changeToken";
    private static final String ARCHIVE_STREAM_KIND = "archive-snapshot";
    private static final String ARCHIVE_CURSOR_KIND = "snapshot";
    private static final String CLOUD_METADATA_STREAM_KIND = "cloud-metadata-snapshot";
    private static final String CLOUD_METADATA_CURSOR_KIND = "snapshot";
    private static final String TYPE_DEFINITION_STREAM_KIND = "type-definition-snapshot";
    private static final String TYPE_DEFINITION_CURSOR_KIND = "snapshot";
    private static final int CHANGE_LOG_PAGE_SIZE = 100;
    private static final int CONTENT_BATCH_SIZE = 50;
    private static final String TOMBSTONE_STATUS_PENDING = "PENDING";
    private static final String PURVIEW_DOCUMENT_TYPE_NAME = "nemaki_document";
    private static final String PURVIEW_FOLDER_TYPE_NAME = "nemaki_folder";
    private static final int MAX_DESCENDANT_COUNT = 5000;

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewTombstoneStateService tombstoneStateService;
    private final PurviewDeadLetterStateService deadLetterStateService;
    private final PurviewDocumentPublishService documentPublishService;
    private final PurviewArchivePublishService archivePublishService;
    private final PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private final PurviewTypeDefinitionPublishService typeDefinitionPublishService;
    private final ContentDaoService contentDaoService;

    public PurviewIncrementalSyncServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewTombstoneStateService tombstoneStateService,
            PurviewDeadLetterStateService deadLetterStateService,
            PurviewDocumentPublishService documentPublishService,
            PurviewArchivePublishService archivePublishService,
            PurviewCloudMetadataPublishService cloudMetadataPublishService,
            PurviewTypeDefinitionPublishService typeDefinitionPublishService,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.tombstoneStateService = tombstoneStateService;
        this.deadLetterStateService = deadLetterStateService;
        this.documentPublishService = documentPublishService;
        this.archivePublishService = archivePublishService;
        this.cloudMetadataPublishService = cloudMetadataPublishService;
        this.typeDefinitionPublishService = typeDefinitionPublishService;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public PurviewJobState startIncrementalSync(String repositoryId, String requestedBy) {
        String now = Instant.now().toString();
        String jobId = UUID.randomUUID().toString();
        if (!lockStateService.tryAcquireRepositoryLock(repositoryId, JOB_KIND, jobId, now)) {
            PurviewJobState rejectedJob = new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    "REJECTED",
                    now,
                    now,
                    0,
                    0,
                    "",
                    "Purview incremental sync is already running for repository " + repositoryId);
            return jobStateService.saveJobState(rejectedJob);
        }

        try {
            PurviewSchemaDiff diff = schemaPlannerService.getSchemaDiff();
            if (diff.isApplyRequired()) {
                PurviewJobState failedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        now,
                        0,
                        0,
                        "",
                        "Purview schema bootstrap is required before incremental sync");
                return jobStateService.saveJobState(failedJob);
            }

            PurviewCursorState currentCursorState = getCursorStateOrDefault(
                    repositoryId,
                    STREAM_KIND,
                    CURSOR_KIND);
            PurviewCursorState currentTypeDefinitionCursorState = getCursorStateOrDefault(
                    repositoryId,
                    TYPE_DEFINITION_STREAM_KIND,
                    TYPE_DEFINITION_CURSOR_KIND);
            PurviewCursorState currentArchiveCursorState = getCursorStateOrDefault(
                    repositoryId,
                    ARCHIVE_STREAM_KIND,
                    ARCHIVE_CURSOR_KIND);
            PurviewCursorState currentCloudMetadataCursorState = getCursorStateOrDefault(
                    repositoryId,
                    CLOUD_METADATA_STREAM_KIND,
                    CLOUD_METADATA_CURSOR_KIND);
            try {
                List<Change> changes = loadChanges(repositoryId, currentCursorState);
                ChangeProcessingResult changeProcessingResult = processChanges(repositoryId, changes, now);
                StreamSyncResult archiveResult = syncArchiveStream(
                        repositoryId,
                        currentArchiveCursorState,
                        now);
                StreamSyncResult cloudMetadataResult = syncCloudMetadataStream(
                        repositoryId,
                        currentCloudMetadataCursorState,
                        now);
                PurviewTypeDefinitionSyncResult typeDefinitionSyncResult = typeDefinitionPublishService
                        .syncRepositoryTypeDefinitionsIfChanged(repositoryId, currentTypeDefinitionCursorState.getCursor());
                String nextCursor = resolveNextCursor(currentCursorState.getCursor(), changes);
                int changeDeadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, STREAM_KIND);
                int archiveDeadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, ARCHIVE_STREAM_KIND);
                int cloudDeadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, CLOUD_METADATA_STREAM_KIND);

                cursorStateService.saveCursorState(buildSuccessCursorState(
                        currentCursorState,
                        nextCursor,
                        now,
                        changeDeadLetterCount));
                cursorStateService.saveCursorState(buildStreamCursorState(
                        currentArchiveCursorState,
                        archiveResult,
                        now,
                        archiveDeadLetterCount));
                cursorStateService.saveCursorState(buildStreamCursorState(
                        currentCloudMetadataCursorState,
                        cloudMetadataResult,
                        now,
                        cloudDeadLetterCount));
                cursorStateService.saveCursorState(buildSuccessCursorState(
                        currentTypeDefinitionCursorState,
                        typeDefinitionSyncResult.getSnapshot(),
                        now,
                        currentTypeDefinitionCursorState.getDeadLetterCount()));

                List<String> failures = new ArrayList<>(changeProcessingResult.failures());
                if (archiveResult.failed()) {
                    failures.add(ARCHIVE_STREAM_KIND + ": " + archiveResult.errorSummary());
                }
                if (cloudMetadataResult.failed()) {
                    failures.add(CLOUD_METADATA_STREAM_KIND + ": " + cloudMetadataResult.errorSummary());
                }
                int failedCount = changeProcessingResult.failedCount()
                        + (archiveResult.failed() ? 1 : 0)
                        + (cloudMetadataResult.failed() ? 1 : 0);
                PurviewJobState completedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        failedCount == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS",
                        now,
                        now,
                        changes.size()
                                + archiveResult.processedCount()
                                + cloudMetadataResult.processedCount()
                                + typeDefinitionSyncResult.getProcessedCount(),
                        failedCount,
                        nextCursor,
                        summarizeFailures(failures));
                return jobStateService.saveJobState(completedJob);
            } catch (RuntimeException e) {
                String errorSummary = buildErrorSummary(e);
                String checkpoint = currentCursorState.getCursor();
                cursorStateService.saveCursorState(buildFailureCursorState(currentCursorState, now, errorSummary));
                cursorStateService.saveCursorState(buildFailureCursorState(
                        currentArchiveCursorState,
                        now,
                        errorSummary));
                cursorStateService.saveCursorState(buildFailureCursorState(
                        currentCloudMetadataCursorState,
                        now,
                        errorSummary));
                cursorStateService.saveCursorState(buildFailureCursorState(
                        currentTypeDefinitionCursorState,
                        now,
                        errorSummary));

                PurviewJobState failedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        now,
                        0,
                        1,
                        checkpoint,
                        errorSummary);
                return jobStateService.saveJobState(failedJob);
            }
        } finally {
            try {
                int purged = jobStateService.purgeOldJobStates(50);
                if (purged > 0) {
                    log.info("Auto-purged " + purged + " old job states");
                }
            } catch (Exception e) {
                log.warn("Auto-purge of old job states failed (non-fatal)", e);
            }
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private List<Change> loadChanges(String repositoryId, PurviewCursorState currentCursorState) {
        String startToken = currentCursorState.getCursor().isBlank() ? null : currentCursorState.getCursor();
        boolean skipFirst = startToken != null;
        int fetchLimit = skipFirst ? CHANGE_LOG_PAGE_SIZE + 1 : CHANGE_LOG_PAGE_SIZE;
        List<Change> rawChanges = contentDaoService.getLatestChanges(repositoryId, startToken, fetchLimit);
        return normalizeChanges(startToken, rawChanges);
    }

    private List<Change> normalizeChanges(String startToken, List<Change> rawChanges) {
        if (rawChanges == null || rawChanges.isEmpty()) {
            return List.of();
        }

        List<Change> changes = new ArrayList<>(rawChanges);
        if (startToken != null && !changes.isEmpty()) {
            Change firstChange = changes.get(0);
            if (firstChange != null && startToken.equals(firstChange.getToken())) {
                changes.remove(0);
            }
        }

        if (changes.size() <= CHANGE_LOG_PAGE_SIZE) {
            return changes;
        }
        return new ArrayList<>(changes.subList(0, CHANGE_LOG_PAGE_SIZE));
    }

    private ChangeProcessingResult processChanges(String repositoryId, List<Change> changes, String now) {
        stageDeleteTombstones(repositoryId, changes);

        List<String> objectIds = collectUpsertObjectIds(changes);
        if (objectIds.isEmpty()) {
            return new ChangeProcessingResult(0, List.of());
        }

        Map<String, Content> contents = getContentsInBatches(repositoryId, objectIds);
        if (contents.isEmpty()) {
            return new ChangeProcessingResult(0, List.of());
        }

        // When a folder is renamed/moved, its descendants' folderPath values become stale.
        // Enqueue all descendants of changed folders so their paths are recalculated.
        List<String> descendantIds = collectDescendantIdsForChangedFolders(repositoryId, contents);
        if (!descendantIds.isEmpty()) {
            contents.putAll(getContentsInBatches(repositoryId, descendantIds));
            Set<String> existing = new LinkedHashSet<>(objectIds);
            for (String descId : descendantIds) {
                if (existing.add(descId)) {
                    objectIds.add(descId);
                }
            }
        }

        List<Content> contentsToPublish = objectIds.stream()
                .map(contents::get)
                .filter(Objects::nonNull)
                .filter(content -> content.isDocument() || content.isFolder())
                .toList();
        if (contentsToPublish.isEmpty()) {
            return new ChangeProcessingResult(0, List.of());
        }

        Map<String, Change> changeByObjectId = buildChangeByObjectId(changes);
        List<String> failures = new ArrayList<>();
        for (Content content : contentsToPublish) {
            try {
                documentPublishService.upsertContents(repositoryId, List.of(content));
                deadLetterStateService.deleteDeadLetterState(repositoryId, STREAM_KIND, content.getId());
            } catch (RuntimeException e) {
                String errorSummary = buildErrorSummary(e);
                failures.add(content.getId() + ": " + errorSummary);
                deadLetterStateService.saveDeadLetterState(buildDeadLetterState(
                        repositoryId,
                        content,
                        changeByObjectId.get(content.getId()),
                        now,
                        errorSummary));
            }
        }
        return new ChangeProcessingResult(failures.size(), failures);
    }

    /**
     * Fetches contents by IDs in batches to avoid oversized CouchDB requests.
     * Always returns a mutable, non-null map.
     */
    private Map<String, Content> getContentsInBatches(String repositoryId, List<String> objectIds) {
        Map<String, Content> merged = new HashMap<>();
        for (int i = 0; i < objectIds.size(); i += CONTENT_BATCH_SIZE) {
            List<String> batch = objectIds.subList(i, Math.min(i + CONTENT_BATCH_SIZE, objectIds.size()));
            Map<String, Content> batchResult = contentDaoService.getContentsByIds(repositoryId, batch);
            if (batchResult != null) {
                merged.putAll(batchResult);
            }
        }
        return merged;
    }

    /**
     * For each changed folder in the contents map, collects all descendant
     * document and folder IDs via BFS. This ensures that when a folder is
     * renamed or moved, all descendants have their folderPath recalculated.
     * Expansion is bounded by MAX_DESCENDANT_COUNT (total collected items).
     */
    private List<String> collectDescendantIdsForChangedFolders(String repositoryId, Map<String, Content> contents) {
        List<String> changedFolderIds = new ArrayList<>();
        for (Content content : contents.values()) {
            if (content != null && content.isFolder()) {
                changedFolderIds.add(content.getId());
            }
        }
        if (changedFolderIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> descendantIds = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(changedFolderIds);

        while (!queue.isEmpty() && descendantIds.size() < MAX_DESCENDANT_COUNT) {
            String folderId = queue.removeFirst();

            List<Content> children = contentDaoService.getChildren(repositoryId, folderId);
            if (children == null) {
                continue;
            }
            for (Content child : children) {
                if (child == null || child.getId() == null || child.getId().isBlank()) {
                    continue;
                }
                if (contents.containsKey(child.getId())) {
                    continue; // Already in the change set
                }
                if (descendantIds.add(child.getId())) {
                    if (child.isFolder()) {
                        queue.addLast(child.getId());
                    }
                }
                if (descendantIds.size() >= MAX_DESCENDANT_COUNT) {
                    log.warn("Purview folder descendant expansion reached limit ("
                            + MAX_DESCENDANT_COUNT + ") for repository " + repositoryId);
                    break;
                }
            }
        }

        if (!descendantIds.isEmpty()) {
            log.info("Purview folder rename/move: enqueuing " + descendantIds.size()
                    + " descendants for path recalculation");
        }
        return new ArrayList<>(descendantIds);
    }

    private void stageDeleteTombstones(String repositoryId, List<Change> changes) {
        String firstSeenAt = Instant.now().toString();
        String dueAt = Instant.parse(firstSeenAt)
                .plusMillis(purviewConfig.getDeleteResolutionDelayMs())
                .toString();
        for (Change change : changes) {
            if (change == null || !ChangeType.DELETED.equals(change.getChangeType())
                    || change.getObjectId() == null || change.getObjectId().isBlank()) {
                continue;
            }

            tombstoneStateService.saveTombstoneState(new PurviewTombstoneState(
                    repositoryId,
                    change.getObjectId(),
                    resolvePurviewTypeName(change),
                    "nemaki://" + repositoryId + "/objects/" + change.getObjectId(),
                    change.getToken() == null ? "" : change.getToken(),
                    firstSeenAt,
                    dueAt,
                    TOMBSTONE_STATUS_PENDING));
        }
    }

    private List<String> collectUpsertObjectIds(List<Change> changes) {
        LinkedHashSet<String> objectIds = new LinkedHashSet<>();
        for (Change change : changes) {
            if (change == null || change.getObjectId() == null || change.getObjectId().isBlank()) {
                continue;
            }
            if (ChangeType.DELETED.equals(change.getChangeType())) {
                continue;
            }
            objectIds.add(change.getObjectId());
        }
        return new ArrayList<>(objectIds);
    }

    private String resolvePurviewTypeName(Change change) {
        if (change != null && "cmis:folder".equals(change.getBaseType())) {
            return PURVIEW_FOLDER_TYPE_NAME;
        }
        return PURVIEW_DOCUMENT_TYPE_NAME;
    }

    private String resolveNextCursor(String currentCursor, List<Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return currentCursor == null ? "" : currentCursor;
        }

        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            if (change != null && change.getToken() != null && !change.getToken().isBlank()) {
                return change.getToken();
            }
        }

        return currentCursor == null ? "" : currentCursor;
    }

    private PurviewCursorState getCursorStateOrDefault(String repositoryId, String streamKind, String cursorKind) {
        PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, streamKind);
        if (currentCursorState != null) {
            return currentCursorState;
        }
        return new PurviewCursorState(repositoryId, streamKind, "", cursorKind, "", "", "", "", 0, 0);
    }

    private PurviewCursorState buildSuccessCursorState(
            PurviewCursorState currentCursorState,
            String nextCursor,
            String now,
            int deadLetterCount) {
        return new PurviewCursorState(
                currentCursorState.getRepositoryId(),
                currentCursorState.getStreamKind(),
                nextCursor,
                resolveCursorKind(currentCursorState),
                now,
                now,
                "",
                "",
                0,
                deadLetterCount);
    }

    private PurviewCursorState buildFailureCursorState(
            PurviewCursorState currentCursorState,
            String now,
            String errorSummary) {
        return buildFailureCursorState(currentCursorState, now, errorSummary, currentCursorState.getDeadLetterCount());
    }

    private PurviewCursorState buildFailureCursorState(
            PurviewCursorState currentCursorState,
            String now,
            String errorSummary,
            int deadLetterCount) {
        return new PurviewCursorState(
                currentCursorState.getRepositoryId(),
                currentCursorState.getStreamKind(),
                currentCursorState.getCursor(),
                resolveCursorKind(currentCursorState),
                now,
                currentCursorState.getLastSuccessAt(),
                now,
                errorSummary,
                currentCursorState.getConsecutiveFailureCount() + 1,
                deadLetterCount);
    }

    private PurviewCursorState buildStreamCursorState(
            PurviewCursorState currentCursorState,
            StreamSyncResult streamSyncResult,
            String now,
            int deadLetterCount) {
        if (streamSyncResult.failed()) {
            return buildFailureCursorState(
                    currentCursorState,
                    now,
                    streamSyncResult.errorSummary(),
                    deadLetterCount);
        }
        return buildSuccessCursorState(
                currentCursorState,
                streamSyncResult.snapshot(),
                now,
                deadLetterCount);
    }

    private String resolveCursorKind(PurviewCursorState currentCursorState) {
        if (currentCursorState.getCursorKind() == null || currentCursorState.getCursorKind().isBlank()) {
            if (ARCHIVE_STREAM_KIND.equals(currentCursorState.getStreamKind())) {
                return ARCHIVE_CURSOR_KIND;
            }
            if (CLOUD_METADATA_STREAM_KIND.equals(currentCursorState.getStreamKind())) {
                return CLOUD_METADATA_CURSOR_KIND;
            }
            if (TYPE_DEFINITION_STREAM_KIND.equals(currentCursorState.getStreamKind())) {
                return TYPE_DEFINITION_CURSOR_KIND;
            }
            return CURSOR_KIND;
        }
        return currentCursorState.getCursorKind();
    }

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return "Purview incremental sync failed with " + e.getClass().getSimpleName();
        }
        return e.getMessage();
    }

    private Map<String, Change> buildChangeByObjectId(List<Change> changes) {
        java.util.LinkedHashMap<String, Change> changeByObjectId = new java.util.LinkedHashMap<>();
        if (changes == null) {
            return changeByObjectId;
        }
        for (Change change : changes) {
            if (change == null || change.getObjectId() == null || change.getObjectId().isBlank()
                    || ChangeType.DELETED.equals(change.getChangeType())) {
                continue;
            }
            changeByObjectId.put(change.getObjectId(), change);
        }
        return changeByObjectId;
    }

    private PurviewDeadLetterState buildDeadLetterState(
            String repositoryId,
            Content content,
            Change change,
            String now,
            String errorSummary) {
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(repositoryId, STREAM_KIND, content.getId());
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt().isBlank()
                ? now
                : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : existingState.getFailureCount() + 1;
        String checkpoint = change == null || change.getToken() == null ? "" : change.getToken();
        return new PurviewDeadLetterState(
                repositoryId,
                STREAM_KIND,
                content.getId(),
                content.isFolder() ? PURVIEW_FOLDER_TYPE_NAME : PURVIEW_DOCUMENT_TYPE_NAME,
                "nemaki://" + repositoryId + "/objects/" + content.getId(),
                firstFailedAt,
                now,
                failureCount,
                checkpoint,
                errorSummary);
    }

    private StreamSyncResult syncCloudMetadataStream(
            String repositoryId,
            PurviewCursorState currentCursorState,
            String now) {
        try {
            PurviewCloudMetadataSyncResult syncResult = cloudMetadataPublishService
                    .syncRepositoryCloudMetadataIfChanged(repositoryId, currentCursorState.getCursor());
            deadLetterStateService.deleteDeadLetterState(repositoryId, CLOUD_METADATA_STREAM_KIND, repositoryId);
            return StreamSyncResult.success(syncResult.getSnapshot(), syncResult.getProcessedCount());
        } catch (RuntimeException e) {
            String errorSummary = buildErrorSummary(e);
            deadLetterStateService.saveDeadLetterState(buildRepositoryStreamDeadLetterState(
                    repositoryId,
                    CLOUD_METADATA_STREAM_KIND,
                    repositoryId,
                    currentCursorState.getCursor(),
                    now,
                    errorSummary));
            return StreamSyncResult.failure(currentCursorState.getCursor(), errorSummary);
        }
    }

    private StreamSyncResult syncArchiveStream(
            String repositoryId,
            PurviewCursorState currentCursorState,
            String now) {
        try {
            PurviewArchiveSyncResult syncResult = archivePublishService
                    .syncRepositoryArchivesIfChanged(repositoryId, currentCursorState.getCursor());
            deadLetterStateService.deleteDeadLetterState(repositoryId, ARCHIVE_STREAM_KIND, repositoryId);
            return StreamSyncResult.success(syncResult.getSnapshot(), syncResult.getProcessedCount());
        } catch (RuntimeException e) {
            String errorSummary = buildErrorSummary(e);
            deadLetterStateService.saveDeadLetterState(buildRepositoryStreamDeadLetterState(
                    repositoryId,
                    ARCHIVE_STREAM_KIND,
                    repositoryId,
                    currentCursorState.getCursor(),
                    now,
                    errorSummary));
            return StreamSyncResult.failure(currentCursorState.getCursor(), errorSummary);
        }
    }

    private PurviewDeadLetterState buildRepositoryStreamDeadLetterState(
            String repositoryId,
            String streamKind,
            String entryKey,
            String checkpoint,
            String now,
            String errorSummary) {
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(repositoryId, streamKind, entryKey);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                streamKind,
                entryKey,
                streamKind,
                "nemaki://" + repositoryId + "/purview/" + streamKind,
                firstFailedAt,
                now,
                failureCount,
                checkpoint == null ? "" : checkpoint,
                errorSummary);
    }

    private String summarizeFailures(List<String> failures) {
        if (failures == null || failures.isEmpty()) {
            return "";
        }
        if (failures.size() == 1) {
            return failures.get(0);
        }
        return failures.size() + " incremental sync operations failed: " + failures.get(0);
    }

    private record ChangeProcessingResult(int failedCount, List<String> failures) {
    }

    private record StreamSyncResult(
            String snapshot,
            int processedCount,
            boolean failed,
            String errorSummary) {

        private static StreamSyncResult success(String snapshot, int processedCount) {
            return new StreamSyncResult(snapshot == null ? "" : snapshot, processedCount, false, "");
        }

        private static StreamSyncResult failure(String snapshot, String errorSummary) {
            return new StreamSyncResult(snapshot == null ? "" : snapshot, 0, true, errorSummary);
        }

    }
}
