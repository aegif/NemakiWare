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

public class PurviewTypeReconciliationServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewTypeDefinitionPublishService typeDefinitionPublishService;
    private PurviewTypeReconciliationServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        typeDefinitionPublishService = mock(PurviewTypeDefinitionPublishService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");

        service = new PurviewTypeReconciliationServiceImpl(
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                typeDefinitionPublishService);
    }

    @Test
    public void testStartTypeReconciliationRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startTypeReconciliation("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartTypeReconciliationFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "5", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startTypeReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
        verify(typeDefinitionPublishService, never()).publishRepositoryTypeDefinitions("bedroom");
    }

    @Test
    public void testStartTypeReconciliationPublishesRepositoryTypeDefinitions() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(typeDefinitionPublishService.publishRepositoryTypeDefinitions("bedroom")).thenReturn(4);

        PurviewJobState result = service.startTypeReconciliation("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(4, result.getProcessedCount());
        verify(typeDefinitionPublishService).publishRepositoryTypeDefinitions("bedroom");
        verify(typeDefinitionPublishService).buildRepositoryTypeDefinitionSnapshot("bedroom");
        verify(cursorStateService).saveCursorState(any());
        verify(lockStateService).releaseRepositoryLock("bedroom", "TYPE_RECONCILIATION", result.getJobId());
    }

    @Test
    public void testStartTypeReconciliationFailsWhenPublishFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(typeDefinitionPublishService.publishRepositoryTypeDefinitions("bedroom"))
                .thenThrow(new IllegalStateException("purview unavailable"));

        PurviewJobState result = service.startTypeReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getErrorSummary().contains("purview unavailable"));
    }
}
