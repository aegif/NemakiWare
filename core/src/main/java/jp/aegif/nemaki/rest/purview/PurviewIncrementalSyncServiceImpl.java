package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewIncrementalSyncServiceImpl implements PurviewIncrementalSyncService {

    private static final String JOB_KIND = "INCREMENTAL_SYNC";
    private static final String STREAM_KIND = "content-change-log";
    private static final String CURSOR_KIND = "changeToken";
    private static final int CHANGE_LOG_PAGE_SIZE = 100;
    private static final String TOMBSTONE_STATUS_PENDING = "PENDING";
    private static final String PURVIEW_DOCUMENT_TYPE_NAME = "nemaki_document";
    private static final String PURVIEW_FOLDER_TYPE_NAME = "nemaki_folder";

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewTombstoneStateService tombstoneStateService;
    private final PurviewDocumentPublishService documentPublishService;
    private final ContentDaoService contentDaoService;

    public PurviewIncrementalSyncServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewTombstoneStateService tombstoneStateService,
            PurviewDocumentPublishService documentPublishService,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.tombstoneStateService = tombstoneStateService;
        this.documentPublishService = documentPublishService;
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

            PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
            try {
                List<Change> changes = loadChanges(repositoryId, currentCursorState);
                processChanges(repositoryId, changes);
                String nextCursor = resolveNextCursor(currentCursorState.getCursor(), changes);

                cursorStateService.saveCursorState(buildSuccessCursorState(repositoryId, currentCursorState, nextCursor, now));

                PurviewJobState completedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "COMPLETED",
                        now,
                        now,
                        changes.size(),
                        0,
                        nextCursor,
                        "");
                return jobStateService.saveJobState(completedJob);
            } catch (RuntimeException e) {
                String errorSummary = buildErrorSummary(e);
                String checkpoint = currentCursorState.getCursor();
                cursorStateService.saveCursorState(buildFailureCursorState(
                        repositoryId,
                        currentCursorState,
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

    private void processChanges(String repositoryId, List<Change> changes) {
        stageDeleteTombstones(repositoryId, changes);

        List<String> objectIds = collectUpsertObjectIds(changes);
        if (objectIds.isEmpty()) {
            return;
        }

        Map<String, Content> contents = contentDaoService.getContentsByIds(repositoryId, objectIds);
        if (contents == null || contents.isEmpty()) {
            return;
        }

        List<Content> contentsToPublish = objectIds.stream()
                .map(contents::get)
                .filter(Objects::nonNull)
                .filter(content -> content.isDocument() || content.isFolder())
                .toList();
        if (contentsToPublish.isEmpty()) {
            return;
        }

        documentPublishService.upsertContents(repositoryId, contentsToPublish);
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

    private PurviewCursorState buildSuccessCursorState(
            String repositoryId,
            PurviewCursorState currentCursorState,
            String nextCursor,
            String now) {
        return new PurviewCursorState(
                repositoryId,
                STREAM_KIND,
                nextCursor,
                resolveCursorKind(currentCursorState),
                now,
                now,
                "",
                "",
                0,
                currentCursorState.getDeadLetterCount());
    }

    private PurviewCursorState buildFailureCursorState(
            String repositoryId,
            PurviewCursorState currentCursorState,
            String now,
            String errorSummary) {
        return new PurviewCursorState(
                repositoryId,
                STREAM_KIND,
                currentCursorState.getCursor(),
                resolveCursorKind(currentCursorState),
                now,
                currentCursorState.getLastSuccessAt(),
                now,
                errorSummary,
                currentCursorState.getConsecutiveFailureCount() + 1,
                currentCursorState.getDeadLetterCount());
    }

    private String resolveCursorKind(PurviewCursorState currentCursorState) {
        if (currentCursorState.getCursorKind() == null || currentCursorState.getCursorKind().isBlank()) {
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
}
