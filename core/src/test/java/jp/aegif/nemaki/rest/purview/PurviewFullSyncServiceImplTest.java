package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;

public class PurviewFullSyncServiceImplTest {

    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewJobStateService jobStateService;
    private PurviewLockStateService lockStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchivePublishService archivePublishService;
    private PurviewCloudMetadataPublishService cloudMetadataPublishService;
    private PurviewTypeDefinitionPublishService typeDefinitionPublishService;
    private ContentDaoService contentDaoService;
    private PurviewFullSyncServiceImpl service;

    @BeforeEach
    public void setUp() {
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        jobStateService = mock(PurviewJobStateService.class);
        lockStateService = mock(PurviewLockStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archivePublishService = mock(PurviewArchivePublishService.class);
        cloudMetadataPublishService = mock(PurviewCloudMetadataPublishService.class);
        typeDefinitionPublishService = mock(PurviewTypeDefinitionPublishService.class);
        contentDaoService = mock(ContentDaoService.class);
        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(documentPublishService.publishRepositoryHierarchy(any())).thenReturn(0);
        when(archivePublishService.publishRepositoryArchives(any())).thenReturn(0);
        when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
        when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot(any())).thenReturn("");
        when(typeDefinitionPublishService.publishRepositoryTypeDefinitions(any())).thenReturn(0);
        when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot(any())).thenReturn("");
        service = new PurviewFullSyncServiceImpl(
                schemaPlannerService,
                jobStateService,
                lockStateService,
                cursorStateService,
                documentPublishService,
                archivePublishService,
                cloudMetadataPublishService,
                typeDefinitionPublishService,
                contentDaoService);
    }

    @Test
    public void testStartFullSyncRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
        verify(jobStateService).saveJobState(any());
    }

    @Test
    public void testStartFullSyncFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "1", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
        verify(jobStateService).saveJobState(any());
        verify(lockStateService).releaseRepositoryLock("bedroom", "FULL_SYNC", result.getJobId());
    }

    @Test
    public void testStartFullSyncCreatesCompletedNoopJobWhenSchemaIsReady() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(documentPublishService.publishRepositoryHierarchy("bedroom")).thenReturn(5);
        when(archivePublishService.publishRepositoryArchives("bedroom")).thenReturn(2);
        when(archivePublishService.buildRepositoryArchiveSnapshot("bedroom")).thenReturn("archive-snapshot-1");
        when(cloudMetadataPublishService.buildRepositoryCloudMetadataSnapshot("bedroom")).thenReturn("cloud-snapshot-1");
        when(typeDefinitionPublishService.publishRepositoryTypeDefinitions("bedroom")).thenReturn(3);
        when(typeDefinitionPublishService.buildRepositoryTypeDefinitionSnapshot("bedroom")).thenReturn("snapshot-1");
        when(contentDaoService.getLatestChange("bedroom")).thenReturn(createChange("120"));

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertNotNull(result.getJobId());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals("FULL_SYNC", result.getJobKind());
        assertEquals("bedroom", result.getRepositoryId());
        assertEquals(10, result.getProcessedCount());
        assertEquals("120", result.getCheckpoint());
        verify(documentPublishService).publishRepositoryHierarchy("bedroom");
        verify(archivePublishService).publishRepositoryArchives("bedroom");
        verify(archivePublishService).buildRepositoryArchiveSnapshot("bedroom");
        verify(cloudMetadataPublishService).buildRepositoryCloudMetadataSnapshot("bedroom");
        verify(typeDefinitionPublishService).publishRepositoryTypeDefinitions("bedroom");
        verify(typeDefinitionPublishService).buildRepositoryTypeDefinitionSnapshot("bedroom");
        verify(jobStateService).saveJobState(any());
        verify(cursorStateService, times(4)).saveCursorState(any());
        verify(lockStateService).releaseRepositoryLock("bedroom", "FULL_SYNC", result.getJobId());
    }

    @Test
    public void testStartFullSyncSeedsBlankCursorWhenRepositoryHasNoChangeLog() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(contentDaoService.getLatestChange("bedroom")).thenReturn(null);

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals("", result.getCheckpoint());
        verify(cursorStateService, times(4)).saveCursorState(any());
    }

    @Test
    public void testStartFullSyncFailsWhenCursorSeedCannotBeResolved() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(contentDaoService.getLatestChange("bedroom")).thenThrow(new RuntimeException("change log unavailable"));

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("change log unavailable"));
        verify(lockStateService).releaseRepositoryLock("bedroom", "FULL_SYNC", result.getJobId());
    }

    @Test
    public void testStartFullSyncFailsWhenDocumentPublishFails() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "1", "current-hash", "1", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(documentPublishService.publishRepositoryHierarchy("bedroom"))
                .thenThrow(new IllegalStateException("purview unavailable"));

        PurviewJobState result = service.startFullSync("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("purview unavailable"));
        verify(cursorStateService, never()).saveCursorState(any());
    }

    private Change createChange(String token) {
        Change change = new Change();
        change.setToken(token);
        change.setObjectId("object-" + token);
        change.setChangeType(ChangeType.UPDATED);
        return change;
    }
}
