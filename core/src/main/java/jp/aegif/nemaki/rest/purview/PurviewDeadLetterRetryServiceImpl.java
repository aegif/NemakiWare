package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDeadLetterRetryServiceImpl implements PurviewDeadLetterRetryService {

    private static final String JOB_KIND = "RETRY_FAILED";
    private static final String STREAM_KIND = "content-change-log";
    private static final String CURSOR_KIND = "changeToken";
    private static final String ARCHIVE_STREAM_KIND = "archive-snapshot";
    private static final String ARCHIVE_CURSOR_KIND = "snapshot";
    private static final String ARCHIVE_LINEAGE_STREAM_KIND = "archive-lineage";
    private static final String CLOUD_METADATA_STREAM_KIND = "cloud-metadata-snapshot";
    private static final String CLOUD_METADATA_CURSOR_KIND = "snapshot";
    private static final String CLOUD_LINEAGE_STREAM_KIND = "cloud-sync-lineage";

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewDeadLetterStateService deadLetterStateService;
    private final PurviewDocumentPublishService documentPublishService;
    private final PurviewArchivePublishService archivePublishService;
    private final PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private final ContentDaoService contentDaoService;

    public PurviewDeadLetterRetryServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewDeadLetterStateService deadLetterStateService,
            PurviewDocumentPublishService documentPublishService,
            PurviewArchivePublishService archivePublishService,
            PurviewCloudMetadataPublishService cloudMetadataPublishService,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.deadLetterStateService = deadLetterStateService;
        this.documentPublishService = documentPublishService;
        this.archivePublishService = archivePublishService;
        this.cloudMetadataPublishService = cloudMetadataPublishService;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public PurviewJobState startRetryFailed(String repositoryId, String requestedBy) {
        String now = Instant.now().toString();
        String jobId = UUID.randomUUID().toString();
        if (!lockStateService.tryAcquireRepositoryLock(repositoryId, JOB_KIND, jobId, now)) {
            return jobStateService.saveJobState(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    "REJECTED",
                    now,
                    now,
                    0,
                    0,
                    "",
                    "Purview retry-failed is already running for repository " + repositoryId));
        }

        try {
            PurviewSchemaDiff diff = schemaPlannerService.getSchemaDiff();
            if (diff.isApplyRequired()) {
                return jobStateService.saveJobState(new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        now,
                        0,
                        0,
                        "",
                        "Purview schema bootstrap is required before retry-failed"));
            }

            List<PurviewDeadLetterState> deadLetters = deadLetterStateService.listDeadLetterStates(repositoryId);
            int processedCount = 0;
            int failedCount = 0;
            List<String> failures = new ArrayList<>();

            for (PurviewDeadLetterState deadLetterState : deadLetters) {
                try {
                    retryDeadLetter(repositoryId, deadLetterState, now);
                    processedCount++;
                } catch (RuntimeException e) {
                    failedCount++;
                    failures.add(deadLetterState.getEntryKey() + ": " + buildErrorSummary(e));
                    deadLetterStateService.saveDeadLetterState(new PurviewDeadLetterState(
                            deadLetterState.getRepositoryId(),
                            deadLetterState.getStreamKind(),
                            deadLetterState.getEntryKey(),
                            deadLetterState.getTypeName(),
                            deadLetterState.getQualifiedName(),
                            deadLetterState.getFirstFailedAt(),
                            Instant.now().toString(),
                            deadLetterState.getFailureCount() + 1,
                    deadLetterState.getCheckpoint(),
                    buildErrorSummary(e)));
                    if (isCursorManagedStream(deadLetterState.getStreamKind())) {
                        saveFailureCursorState(repositoryId, deadLetterState.getStreamKind(), now, buildErrorSummary(e));
                    }
                }
            }

            PurviewCursorState contentCursorState = getCursorStateOrDefault(repositoryId, STREAM_KIND, CURSOR_KIND);
            return jobStateService.saveJobState(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    failedCount == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS",
                    now,
                    Instant.now().toString(),
                    processedCount,
                    failedCount,
                    contentCursorState.getCursor(),
                    summarizeFailures(failures)));
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private void retryDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState, String now) {
        if (STREAM_KIND.equals(deadLetterState.getStreamKind())) {
            retryContentDeadLetter(repositoryId, deadLetterState, now);
            return;
        }
        if (ARCHIVE_STREAM_KIND.equals(deadLetterState.getStreamKind())) {
            retryArchiveDeadLetter(repositoryId, deadLetterState, now);
            return;
        }
        if (ARCHIVE_LINEAGE_STREAM_KIND.equals(deadLetterState.getStreamKind())) {
            retryArchiveLineageDeadLetter(repositoryId, deadLetterState);
            return;
        }
        if (CLOUD_METADATA_STREAM_KIND.equals(deadLetterState.getStreamKind())) {
            retryCloudMetadataDeadLetter(repositoryId, deadLetterState, now);
            return;
        }
        if (CLOUD_LINEAGE_STREAM_KIND.equals(deadLetterState.getStreamKind())) {
            retryCloudLineageDeadLetter(repositoryId, deadLetterState);
            return;
        }
        throw new IllegalStateException("Unsupported dead-letter stream kind " + deadLetterState.getStreamKind());
    }

    private void retryContentDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState, String now) {
        Content content = contentDaoService.getContent(repositoryId, deadLetterState.getEntryKey());
        if (content == null || (!content.isDocument() && !content.isFolder())) {
            throw new IllegalStateException("Content not found for dead-letter entry " + deadLetterState.getEntryKey());
        }

        documentPublishService.upsertContents(repositoryId, List.of(content));
        deadLetterStateService.deleteDeadLetterState(repositoryId, deadLetterState.getStreamKind(), deadLetterState.getEntryKey());
        saveSuccessCursorState(repositoryId, deadLetterState.getStreamKind(), now, null);
    }

    private void retryCloudMetadataDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState, String now) {
        PurviewCursorState currentCursorState = getCursorStateOrDefault(repositoryId, CLOUD_METADATA_STREAM_KIND, CLOUD_METADATA_CURSOR_KIND);
        PurviewCloudMetadataSyncResult syncResult = cloudMetadataPublishService
                .syncRepositoryCloudMetadataIfChanged(repositoryId, currentCursorState.getCursor());
        deadLetterStateService.deleteDeadLetterState(repositoryId, deadLetterState.getStreamKind(), deadLetterState.getEntryKey());
        saveSuccessCursorState(repositoryId, deadLetterState.getStreamKind(), now, syncResult.getSnapshot());
    }

    private void retryCloudLineageDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState) {
        cloudMetadataPublishService.retryRepositoryCloudSyncLineage(repositoryId, deadLetterState.getCheckpoint());
        deadLetterStateService.deleteDeadLetterState(repositoryId, deadLetterState.getStreamKind(), deadLetterState.getEntryKey());
    }

    private void retryArchiveDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState, String now) {
        PurviewCursorState currentCursorState = getCursorStateOrDefault(repositoryId, ARCHIVE_STREAM_KIND, ARCHIVE_CURSOR_KIND);
        PurviewArchiveSyncResult syncResult = archivePublishService
                .syncRepositoryArchivesIfChanged(repositoryId, currentCursorState.getCursor());
        deadLetterStateService.deleteDeadLetterState(repositoryId, deadLetterState.getStreamKind(), deadLetterState.getEntryKey());
        saveSuccessCursorState(repositoryId, deadLetterState.getStreamKind(), now, syncResult.getSnapshot());
    }

    private void retryArchiveLineageDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState) {
        archivePublishService.retryRepositoryArchiveLineage(repositoryId, deadLetterState.getCheckpoint());
        deadLetterStateService.deleteDeadLetterState(repositoryId, deadLetterState.getStreamKind(), deadLetterState.getEntryKey());
    }

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }

    private String resolveCursorKind(PurviewCursorState currentCursorState) {
        if (currentCursorState.getCursorKind() == null || currentCursorState.getCursorKind().isBlank()) {
            if (ARCHIVE_STREAM_KIND.equals(currentCursorState.getStreamKind())) {
                return ARCHIVE_CURSOR_KIND;
            }
            if (CLOUD_METADATA_STREAM_KIND.equals(currentCursorState.getStreamKind())) {
                return CLOUD_METADATA_CURSOR_KIND;
            }
            return CURSOR_KIND;
        }
        return currentCursorState.getCursorKind();
    }

    private PurviewCursorState getCursorStateOrDefault(String repositoryId, String streamKind, String cursorKind) {
        PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, streamKind);
        if (currentCursorState != null) {
            return currentCursorState;
        }
        return new PurviewCursorState(repositoryId, streamKind, "", cursorKind, "", "", "", "", 0, 0);
    }

    private void saveSuccessCursorState(String repositoryId, String streamKind, String now, String nextCursor) {
        PurviewCursorState currentCursorState = getCursorStateOrDefault(
                repositoryId,
                streamKind,
                resolveDefaultCursorKind(streamKind));
        int deadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, streamKind);
        cursorStateService.saveCursorState(new PurviewCursorState(
                currentCursorState.getRepositoryId(),
                currentCursorState.getStreamKind(),
                nextCursor == null ? currentCursorState.getCursor() : nextCursor,
                resolveCursorKind(currentCursorState),
                now,
                now,
                "",
                "",
                0,
                deadLetterCount));
    }

    private void saveFailureCursorState(String repositoryId, String streamKind, String now, String errorSummary) {
        PurviewCursorState currentCursorState = getCursorStateOrDefault(
                repositoryId,
                streamKind,
                resolveDefaultCursorKind(streamKind));
        int deadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, streamKind);
        cursorStateService.saveCursorState(new PurviewCursorState(
                currentCursorState.getRepositoryId(),
                currentCursorState.getStreamKind(),
                currentCursorState.getCursor(),
                resolveCursorKind(currentCursorState),
                now,
                currentCursorState.getLastSuccessAt(),
                now,
                errorSummary,
                currentCursorState.getConsecutiveFailureCount() + 1,
                deadLetterCount));
    }

    private String resolveDefaultCursorKind(String streamKind) {
        if (ARCHIVE_STREAM_KIND.equals(streamKind)) {
            return ARCHIVE_CURSOR_KIND;
        }
        if (CLOUD_METADATA_STREAM_KIND.equals(streamKind)) {
            return CLOUD_METADATA_CURSOR_KIND;
        }
        return CURSOR_KIND;
    }

    private boolean isCursorManagedStream(String streamKind) {
        return STREAM_KIND.equals(streamKind)
                || ARCHIVE_STREAM_KIND.equals(streamKind)
                || CLOUD_METADATA_STREAM_KIND.equals(streamKind);
    }

    private String summarizeFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return "";
        }
        if (failures.size() == 1) {
            return failures.get(0);
        }
        return failures.size() + " dead-letter retries failed: " + failures.get(0);
    }
}
