package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewSchemaBootstrapServiceImplTest {

    private PurviewConfig config;
    private PurviewSchemaApplyService schemaApplyService;
    private PurviewJobStateService jobStateService;
    private PurviewLockStateService lockStateService;
    private PurviewSchemaBootstrapServiceImpl service;

    @BeforeEach
    public void setUp() {
        config = mock(PurviewConfig.class);
        schemaApplyService = mock(PurviewSchemaApplyService.class);
        jobStateService = mock(PurviewJobStateService.class);
        lockStateService = mock(PurviewLockStateService.class);

        when(config.getCollection()).thenReturn("NemakiWare");
        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);

        service = new PurviewSchemaBootstrapServiceImpl(
                config,
                schemaApplyService,
                jobStateService,
                lockStateService);
    }

    @Test
    public void testStartTypeBootstrapRejectsWhenCollectionLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewSchemaBootstrapResult result = service.startTypeBootstrap("admin");

        assertEquals("REJECTED", result.getJobState().getStatus());
        assertFalse(result.getApplyResult().isApplied());
        assertTrue(result.getApplyResult().getMessage().contains("already running"));
        verify(schemaApplyService, never()).applySchema(any());
    }

    @Test
    public void testStartTypeBootstrapCompletesWhenSchemaIsAlreadyUpToDate() {
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "1", "schema-hash", "2026-03-20T00:00:00Z", "admin", "up to date");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "1", "schema-hash", "1", "schema-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        when(schemaApplyService.applySchema("admin"))
                .thenReturn(new PurviewSchemaApplyResult(false, "up to date", currentState, diff));

        PurviewSchemaBootstrapResult result = service.startTypeBootstrap("admin");

        assertEquals("COMPLETED", result.getJobState().getStatus());
        assertEquals("schema-hash", result.getJobState().getCheckpoint());
        assertFalse(result.getApplyResult().isApplied());
        assertEquals("up to date", result.getApplyResult().getMessage());
        verify(lockStateService).releaseRepositoryLock("collection:NemakiWare", "TYPE_BOOTSTRAP", result.getJobState().getJobId());
    }

    @Test
    public void testStartTypeBootstrapCompletesWhenSchemaApplySucceeds() {
        PurviewSchemaState nextState = new PurviewSchemaState(
                "NemakiWare", "2", "desired-hash", "2026-03-20T01:00:00Z", "admin", "applied");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "2", "desired-hash", true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of("nemakiGovernance"));
        when(schemaApplyService.applySchema("admin"))
                .thenReturn(new PurviewSchemaApplyResult(true, "schema applied", nextState, diff));

        PurviewSchemaBootstrapResult result = service.startTypeBootstrap("admin");

        assertEquals("COMPLETED", result.getJobState().getStatus());
        assertEquals(3, result.getJobState().getProcessedCount());
        assertEquals("desired-hash", result.getJobState().getCheckpoint());
        assertTrue(result.getApplyResult().isApplied());
    }

    @Test
    public void testStartTypeBootstrapFailsWhenSchemaApplyFails() {
        PurviewSchemaState currentState = new PurviewSchemaState(
                "NemakiWare", "1", "current-hash", "2026-03-20T00:00:00Z", "admin", "stale");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "2", "desired-hash", true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of(), java.util.List.of());
        when(schemaApplyService.applySchema("admin"))
                .thenReturn(new PurviewSchemaApplyResult(false, "registry unavailable", currentState, diff));

        PurviewSchemaBootstrapResult result = service.startTypeBootstrap("admin");

        assertEquals("FAILED", result.getJobState().getStatus());
        assertEquals("current-hash", result.getJobState().getCheckpoint());
        assertEquals(1, result.getJobState().getFailedCount());
        assertTrue(result.getJobState().getErrorSummary().contains("registry unavailable"));
    }
}
