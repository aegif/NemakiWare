package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.publish.PurviewCloudMetadataPublishService;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PurviewCloudMetadataReconciliationServiceImpl implements PurviewCloudMetadataReconciliationService {

    private static final String JOB_KIND = "CLOUD_METADATA_RECONCILIATION";
    private static final String STREAM_KIND = "cloud-metadata-snapshot";
    private static final String CURSOR_KIND = "snapshot";

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewCloudMetadataPublishService cloudMetadataPublishService;

    public PurviewCloudMetadataReconciliationServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewCloudMetadataPublishService cloudMetadataPublishService) {
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.cloudMetadataPublishService = cloudMetadataPublishService;
    }

    @Override
    public PurviewJobState startCloudMetadataReconciliation(String repositoryId, String requestedBy) {
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
                    "Purview cloud metadata reconciliation is already running for repository " + repositoryId));
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
                        "Purview schema bootstrap is required before cloud metadata reconciliation"));
            }

            try {
                PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
                String previousSnapshot = currentCursorState == null ? "" : currentCursorState.getCursor();
                PurviewCloudMetadataSyncResult syncResult = cloudMetadataPublishService
                        .syncRepositoryCloudMetadataIfChanged(repositoryId, previousSnapshot);
                seedCloudMetadataCursor(repositoryId, syncResult.getSnapshot(), now, currentCursorState);
                return jobStateService.saveJobState(new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "COMPLETED",
                        now,
                        Instant.now().toString(),
                        syncResult.getProcessedCount(),
                        0,
                        "",
                        ""));
            } catch (RuntimeException e) {
                return jobStateService.saveJobState(new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "FAILED",
                        now,
                        Instant.now().toString(),
                        0,
                        1,
                        "",
                        buildErrorSummary(e)));
            }
        } finally {
            lockStateService.releaseRepositoryLock(repositoryId, JOB_KIND, jobId);
        }
    }

    private void seedCloudMetadataCursor(
            String repositoryId,
            String snapshot,
            String now,
            PurviewCursorState currentCursorState) {
        PurviewCursorState cursorState = currentCursorState;
        if (cursorState == null) {
            cursorState = new PurviewCursorState(repositoryId, STREAM_KIND, "", CURSOR_KIND, "", "", "", "", 0, 0);
        }
        cursorStateService.saveCursorState(new PurviewCursorState(
                repositoryId,
                STREAM_KIND,
                snapshot == null ? "" : snapshot,
                CURSOR_KIND,
                now,
                now,
                "",
                "",
                0,
                cursorState.getDeadLetterCount()));
    }

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }
}
