package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.publish.PurviewCloudMetadataPublishService;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewCloudMetadataReconciliationServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewCloudMetadataReconciliationServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        cloudMetadataPublishService = mock(PurviewCloudMetadataPublishService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);

        service = new PurviewCloudMetadataReconciliationServiceImpl(
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                cloudMetadataPublishService,
                deadLetterStateService);
    }

    @Test
    public void testStartCloudMetadataReconciliationRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startCloudMetadataReconciliation("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartCloudMetadataReconciliationFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "5", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startCloudMetadataReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
        verify(cloudMetadataPublishService, never()).syncRepositoryCloudMetadataIfChanged("bedroom", "");
    }

    @Test
    public void testStartCloudMetadataReconciliationSyncsSnapshotAndSeedsCursor() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cursorStateService.getCursorState("bedroom", "cloud-metadata-snapshot")).thenReturn(new PurviewCursorState(
                "bedroom", "cloud-metadata-snapshot", "cloud-old", "snapshot",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:00Z", "", "", 0, 0));
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old"))
                .thenReturn(new PurviewCloudMetadataSyncResult("cloud-new", true, 2, 1));
        when(deadLetterStateService.getDeadLetterState("bedroom", "cloud-sync-lineage", "bedroom")).thenReturn(null);

        PurviewJobState result = service.startCloudMetadataReconciliation("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        verify(cloudMetadataPublishService).syncRepositoryCloudMetadataIfChanged("bedroom", "cloud-old");
        verify(cursorStateService).saveCursorState(any());
        verify(lockStateService).releaseRepositoryLock("bedroom", "CLOUD_METADATA_RECONCILIATION", result.getJobId());
    }

    @Test
    public void testStartCloudMetadataReconciliationFailsWhenSyncFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", ""))
                .thenThrow(new IllegalStateException("purview unavailable"));

        PurviewJobState result = service.startCloudMetadataReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getErrorSummary().contains("purview unavailable"));
    }

    @Test
    public void testCompletedWithErrorsWhenCloudLineageDeadLetterExists() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "5", "current-hash", "5", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(cloudMetadataPublishService.syncRepositoryCloudMetadataIfChanged("bedroom", ""))
                .thenReturn(new PurviewCloudMetadataSyncResult("snap1", true, 3, 0));
        PurviewDeadLetterState dl = new PurviewDeadLetterState(
                "bedroom", "cloud-sync-lineage", "bedroom",
                "nemaki_cloud_sync_process", "nemaki://bedroom/purview/cloud-sync-lineage",
                "2026-03-29T10:00:00Z", "2026-03-29T10:00:00Z", 1, "", "Atlas 404");
        when(deadLetterStateService.getDeadLetterState("bedroom", "cloud-sync-lineage", "bedroom")).thenReturn(dl);

        PurviewJobState result = service.startCloudMetadataReconciliation("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(3, result.getProcessedCount());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getErrorSummary().contains("cloud-sync-lineage"));
    }
}
