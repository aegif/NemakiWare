package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewContainmentReconciliationServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewContainmentRelationshipService containmentRelationshipService;
    private PurviewContainmentReconciliationServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        containmentRelationshipService = mock(PurviewContainmentRelationshipService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);

        service = new PurviewContainmentReconciliationServiceImpl(
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                containmentRelationshipService);
    }

    @Test
    public void testStartContainmentReconciliationRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startContainmentReconciliation("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartContainmentReconciliationFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "5", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startContainmentReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
        verify(containmentRelationshipService, never()).syncRepositoryContainmentRelationshipsIfChanged("bedroom", "");
    }

    @Test
    public void testStartContainmentReconciliationSyncsSnapshotAndSeedsCursor() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "containment-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "containment-snapshot", "old-snapshot", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "", "", 0, 0));
        when(containmentRelationshipService.syncRepositoryContainmentRelationshipsIfChanged("bedroom", "old-snapshot"))
                .thenReturn(new PurviewContainmentSyncResult("new-snapshot", true, 2, 1));

        PurviewJobState result = service.startContainmentReconciliation("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        verify(containmentRelationshipService).syncRepositoryContainmentRelationshipsIfChanged("bedroom", "old-snapshot");
        verify(cursorStateService).saveCursorState(any());
        verify(lockStateService).releaseRepositoryLock("bedroom", "CONTAINMENT_RECONCILIATION", result.getJobId());
    }

    @Test
    public void testStartContainmentReconciliationFailsWhenSyncFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(containmentRelationshipService.syncRepositoryContainmentRelationshipsIfChanged("bedroom", ""))
                .thenThrow(new IllegalStateException("relationship sync unavailable"));

        PurviewJobState result = service.startContainmentReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getErrorSummary().contains("relationship sync unavailable"));
    }
}
