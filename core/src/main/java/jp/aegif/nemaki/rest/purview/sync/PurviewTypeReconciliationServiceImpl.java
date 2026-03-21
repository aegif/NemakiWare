package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.publish.PurviewTypeDefinitionPublishService;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PurviewTypeReconciliationServiceImpl implements PurviewTypeReconciliationService {

    private static final String JOB_KIND = "TYPE_RECONCILIATION";
    private static final String STREAM_KIND = "type-definition-snapshot";
    private static final String CURSOR_KIND = "snapshot";

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewTypeDefinitionPublishService typeDefinitionPublishService;

    public PurviewTypeReconciliationServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewTypeDefinitionPublishService typeDefinitionPublishService) {
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.typeDefinitionPublishService = typeDefinitionPublishService;
    }

    @Override
    public PurviewJobState startTypeReconciliation(String repositoryId, String requestedBy) {
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
                    "Purview type reconciliation is already running for repository " + repositoryId));
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
                        "Purview schema bootstrap is required before type reconciliation"));
            }

            try {
                int processedCount = typeDefinitionPublishService.publishRepositoryTypeDefinitions(repositoryId);
                seedTypeDefinitionCursor(repositoryId, now);
                return jobStateService.saveJobState(new PurviewJobState(
                        jobId,
                        JOB_KIND,
                        repositoryId,
                        "COMPLETED",
                        now,
                        Instant.now().toString(),
                        processedCount,
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

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }

    private void seedTypeDefinitionCursor(String repositoryId, String now) {
        String snapshot = typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(repositoryId);
        PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
        if (currentCursorState == null) {
            currentCursorState = new PurviewCursorState(repositoryId, STREAM_KIND, "", CURSOR_KIND, "", "", "", "", 0, 0);
        }
        cursorStateService.saveCursorState(new PurviewCursorState(
                repositoryId,
                STREAM_KIND,
                snapshot,
                CURSOR_KIND,
                now,
                now,
                "",
                "",
                0,
                currentCursorState.getDeadLetterCount()));
    }
}
