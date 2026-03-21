package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneState;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneStateService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Document;

public class PurviewDeleteResolutionServiceImplTest {

    private PurviewConfig purviewConfig;
    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewTombstoneStateService tombstoneStateService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private ContentDaoService contentDaoService;
    private PurviewDeleteResolutionServiceImpl service;

    @BeforeEach
    public void setUp() {
        purviewConfig = mock(PurviewConfig.class);
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        tombstoneStateService = mock(PurviewTombstoneStateService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        contentDaoService = mock(ContentDaoService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(tombstoneStateService.saveTombstoneState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(purviewConfig.getEndpoint()).thenReturn("https://example.purview.azure.com");
        when(purviewConfig.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(purviewConfig.getTenantId()).thenReturn("tenant-id");
        when(purviewConfig.getClientId()).thenReturn("client-id");
        when(purviewConfig.getClientSecret()).thenReturn("client-secret");
        when(purviewConfig.getConnectTimeoutMs()).thenReturn(5000);
        when(purviewConfig.getReadTimeoutMs()).thenReturn(30000);

        service = new PurviewDeleteResolutionServiceImpl(
                purviewConfig,
                schemaPlannerService,
                lockStateService,
                jobStateService,
                tombstoneStateService,
                entityRegistryClient,
                contentDaoService);
    }

    @Test
    public void testStartDeleteResolutionRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartDeleteResolutionFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "2", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
    }

    @Test
    public void testStartDeleteResolutionDeletesPurviewEntityWhenContentAndArchiveAreMissing() throws Exception {
        PurviewTombstoneState tombstone = tombstone("doc-001");
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listDueTombstoneStates(eq("bedroom"), any(Instant.class))).thenReturn(List.of(tombstone));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), eq("nemaki_document"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001")))
                .thenReturn(PurviewEntityPublishResult.success(1, "entity deleted"));

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        assertEquals(0, result.getFailedCount());
        verify(entityRegistryClient).deleteByUniqueAttribute(any(), eq("nemaki_document"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001"));
        verify(tombstoneStateService).deleteTombstoneState("bedroom", "doc-001");
    }

    @Test
    public void testStartDeleteResolutionDeletesFolderTombstoneWithoutArchiveLookup() throws Exception {
        PurviewTombstoneState tombstone = tombstone("folder-001", "nemaki_folder");
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listDueTombstoneStates(eq("bedroom"), any(Instant.class))).thenReturn(List.of(tombstone));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), eq("nemaki_folder"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/folder-001")))
                .thenReturn(PurviewEntityPublishResult.success(1, "entity deleted"));

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        verify(entityRegistryClient).deleteByUniqueAttribute(any(), eq("nemaki_folder"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/folder-001"));
        verify(contentDaoService, never()).getArchiveByOriginalId("bedroom", "folder-001");
        verify(tombstoneStateService).deleteTombstoneState("bedroom", "folder-001");
    }

    @Test
    public void testStartDeleteResolutionMarksArchivedTombstoneWhenArchiveExists() throws Exception {
        PurviewTombstoneState tombstone = tombstone("doc-002");
        Archive archive = new Archive();
        archive.setOriginalId("doc-002");

        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listDueTombstoneStates(eq("bedroom"), any(Instant.class))).thenReturn(List.of(tombstone));
        when(contentDaoService.getArchiveByOriginalId("bedroom", "doc-002")).thenReturn(archive);

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        verify(tombstoneStateService).saveTombstoneState(any());
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
        verify(tombstoneStateService, never()).deleteTombstoneState("bedroom", "doc-002");
    }

    @Test
    public void testStartDeleteResolutionDropsTombstoneWhenLiveContentExistsAgain() throws Exception {
        PurviewTombstoneState tombstone = tombstone("doc-003");
        Document liveDocument = new Document();
        liveDocument.setId("doc-003");

        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listDueTombstoneStates(eq("bedroom"), any(Instant.class))).thenReturn(List.of(tombstone));
        when(contentDaoService.getContentFresh("bedroom", "doc-003")).thenReturn(liveDocument);

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        verify(tombstoneStateService).deleteTombstoneState("bedroom", "doc-003");
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    public void testStartDeleteResolutionStoresErrorStatusWhenPurviewDeleteFails() throws Exception {
        PurviewTombstoneState tombstone = tombstone("doc-004");
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "2", "current-hash", "2", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listDueTombstoneStates(eq("bedroom"), any(Instant.class))).thenReturn(List.of(tombstone));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenThrow(new PurviewClientException("purview unavailable"));

        PurviewJobState result = service.startDeleteResolution("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getFailedCount());
        assertTrue(result.getErrorSummary().contains("doc-004"));
        verify(tombstoneStateService).saveTombstoneState(any());
        verify(tombstoneStateService, never()).deleteTombstoneState("bedroom", "doc-004");
    }

    private PurviewTombstoneState tombstone(String objectId) {
        return tombstone(objectId, "nemaki_document");
    }

    private PurviewTombstoneState tombstone(String objectId, String typeName) {
        return new PurviewTombstoneState(
                "bedroom",
                objectId,
                typeName,
                "nemaki://bedroom/objects/" + objectId,
                "token-" + objectId,
                "2026-03-20T05:00:00Z",
                "2026-03-20T05:00:05Z",
                "PENDING");
    }
}
