package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;

@Service
public class PurviewFullSyncServiceImpl implements PurviewFullSyncService {

    private static final String JOB_KIND = "FULL_SYNC";
    private static final String STREAM_KIND = "content-change-log";
    private static final String CURSOR_KIND = "changeToken";

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewJobStateService jobStateService;
    private final PurviewLockStateService lockStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewDocumentPublishService documentPublishService;
    private final ContentDaoService contentDaoService;

    public PurviewFullSyncServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewJobStateService jobStateService,
            PurviewLockStateService lockStateService,
            PurviewCursorStateService cursorStateService,
            PurviewDocumentPublishService documentPublishService,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.schemaPlannerService = schemaPlannerService;
        this.jobStateService = jobStateService;
        this.lockStateService = lockStateService;
        this.cursorStateService = cursorStateService;
        this.documentPublishService = documentPublishService;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public PurviewJobState startFullSync(String repositoryId, String requestedBy) {
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
                    "Purview full sync is already running for repository " + repositoryId);
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
                        "Purview schema bootstrap is required before full sync");
                return jobStateService.saveJobState(failedJob);
            }

            try {
                int processedCount = documentPublishService.publishRepositoryHierarchy(repositoryId);
                String checkpoint = seedCursorFromLatestChange(repositoryId, now);
                PurviewJobState completedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "COMPLETED",
                        now,
                        now,
                        processedCount,
                        0,
                        checkpoint,
                        "");
                return jobStateService.saveJobState(completedJob);
            } catch (RuntimeException e) {
                PurviewJobState failedJob = new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        now,
                        0,
                        1,
                        "",
                        e.getMessage() == null ? "Failed to seed full-sync cursor" : e.getMessage());
                return jobStateService.saveJobState(failedJob);
            }
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private String seedCursorFromLatestChange(String repositoryId, String now) {
        Change latestChange = contentDaoService.getLatestChange(repositoryId);
        String checkpoint = resolveChangeToken(latestChange);
        PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
        if (currentCursorState == null) {
            currentCursorState = new PurviewCursorState(repositoryId, STREAM_KIND, "", CURSOR_KIND, "", "", "", "", 0, 0);
        }
        cursorStateService.saveCursorState(new PurviewCursorState(
                repositoryId,
                STREAM_KIND,
                checkpoint,
                CURSOR_KIND,
                now,
                now,
                "",
                "",
                0,
                currentCursorState.getDeadLetterCount()));
        return checkpoint;
    }

    private String resolveChangeToken(Change latestChange) {
        if (latestChange == null || latestChange.getToken() == null || latestChange.getToken().isBlank()) {
            return "";
        }
        return latestChange.getToken();
    }
}
