package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PurviewContainmentReconciliationServiceImpl implements PurviewContainmentReconciliationService {

    private static final String JOB_KIND = "CONTAINMENT_RECONCILIATION";
    private static final String STREAM_KIND = "containment-snapshot";
    private static final String CURSOR_KIND = "snapshot";

    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewLockStateService lockStateService;
    private final PurviewJobStateService jobStateService;
    private final PurviewCursorStateService cursorStateService;
    private final PurviewContainmentRelationshipService containmentRelationshipService;

    public PurviewContainmentReconciliationServiceImpl(
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewLockStateService lockStateService,
            PurviewJobStateService jobStateService,
            PurviewCursorStateService cursorStateService,
            PurviewContainmentRelationshipService containmentRelationshipService) {
        this.schemaPlannerService = schemaPlannerService;
        this.lockStateService = lockStateService;
        this.jobStateService = jobStateService;
        this.cursorStateService = cursorStateService;
        this.containmentRelationshipService = containmentRelationshipService;
    }

    @Override
    public PurviewJobState startContainmentReconciliation(String repositoryId, String requestedBy) {
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
                    "Purview containment reconciliation is already running for repository " + repositoryId));
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
                        "Purview schema bootstrap is required before containment reconciliation"));
            }

            try {
                PurviewCursorState currentCursorState = cursorStateService.getCursorState(repositoryId, STREAM_KIND);
                String previousSnapshot = currentCursorState == null ? "" : currentCursorState.getCursor();
                PurviewContainmentSyncResult syncResult = containmentRelationshipService
                        .syncRepositoryContainmentRelationshipsIfChanged(repositoryId, previousSnapshot);
                seedContainmentCursor(repositoryId, syncResult.getSnapshot(), now, currentCursorState);
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

    private void seedContainmentCursor(
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
