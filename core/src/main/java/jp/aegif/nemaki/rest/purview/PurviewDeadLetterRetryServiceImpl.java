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

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewDeadLetterStateService deadLetterStateService;
    private final PurviewDocumentPublishService documentPublishService;
    private final ContentDaoService contentDaoService;

    public PurviewDeadLetterRetryServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewDeadLetterStateService deadLetterStateService,
            PurviewDocumentPublishService documentPublishService,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.deadLetterStateService = deadLetterStateService;
        this.documentPublishService = documentPublishService;
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

            PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
            if (currentCursorState == null) {
                currentCursorState = new PurviewCursorState(repositoryId, STREAM_KIND, "", CURSOR_KIND, "", "", "", "", 0, 0);
            }

            List<PurviewDeadLetterState> deadLetters = deadLetterStateService.listDeadLetterStates(repositoryId);
            int processedCount = 0;
            int failedCount = 0;
            List<String> failures = new ArrayList<>();

            for (PurviewDeadLetterState deadLetterState : deadLetters) {
                if (!STREAM_KIND.equals(deadLetterState.getStreamKind())) {
                    continue;
                }
                try {
                    retryDeadLetter(repositoryId, deadLetterState);
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
                }
            }

            int deadLetterCount = deadLetterStateService.countDeadLetterStates(repositoryId, STREAM_KIND);
            cursorStateService.saveCursorState(new PurviewCursorState(
                    currentCursorState.getRepositoryId(),
                    currentCursorState.getStreamKind(),
                    currentCursorState.getCursor(),
                    resolveCursorKind(currentCursorState),
                    now,
                    failedCount == 0 ? now : currentCursorState.getLastSuccessAt(),
                    failedCount == 0 ? "" : now,
                    failedCount == 0 ? "" : summarizeFailures(failures),
                    failedCount == 0 ? 0 : currentCursorState.getConsecutiveFailureCount() + 1,
                    deadLetterCount));

            return jobStateService.saveJobState(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    repositoryId,
                    failedCount == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS",
                    now,
                    Instant.now().toString(),
                    processedCount,
                    failedCount,
                    currentCursorState.getCursor(),
                    summarizeFailures(failures)));
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private void retryDeadLetter(String repositoryId, PurviewDeadLetterState deadLetterState) {
        Content content = contentDaoService.getContent(repositoryId, deadLetterState.getEntryKey());
        if (content == null || (!content.isDocument() && !content.isFolder())) {
            throw new IllegalStateException("Content not found for dead-letter entry " + deadLetterState.getEntryKey());
        }

        documentPublishService.upsertContents(repositoryId, List.of(content));
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
            return CURSOR_KIND;
        }
        return currentCursorState.getCursorKind();
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
