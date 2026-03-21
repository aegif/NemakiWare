package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.rest.purview.publish.PurviewArchivePublishService;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentArchiveRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Document;

public class PurviewArchiveReconciliationServiceImplTest {

    private PurviewConfig purviewConfig;
    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewLockStateService lockStateService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewTombstoneStateService tombstoneStateService;
    private PurviewArchivePublishService archivePublishService;
    private PurviewEntityPayloadFactory entityPayloadFactory;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentArchiveRelationshipService documentArchiveRelationshipService;
    private ContentDaoService contentDaoService;
    private PurviewArchiveReconciliationServiceImpl service;

    @BeforeEach
    public void setUp() {
        purviewConfig = mock(PurviewConfig.class);
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        lockStateService = mock(PurviewLockStateService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        tombstoneStateService = mock(PurviewTombstoneStateService.class);
        archivePublishService = mock(PurviewArchivePublishService.class);
        entityPayloadFactory = new PurviewEntityPayloadFactory();
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        documentArchiveRelationshipService = mock(PurviewDocumentArchiveRelationshipService.class);
        contentDaoService = mock(ContentDaoService.class);

        when(jobStateService.saveJobState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cursorStateService.saveCursorState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(true);
        when(tombstoneStateService.saveTombstoneState(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(archivePublishService.buildRepositoryArchiveSnapshot(any())).thenReturn("");
        when(purviewConfig.getEndpoint()).thenReturn("https://example.purview.azure.com");
        when(purviewConfig.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(purviewConfig.getTenantId()).thenReturn("tenant-id");
        when(purviewConfig.getClientId()).thenReturn("client-id");
        when(purviewConfig.getClientSecret()).thenReturn("client-secret");
        when(purviewConfig.getConnectTimeoutMs()).thenReturn(5000);
        when(purviewConfig.getReadTimeoutMs()).thenReturn(30000);
        when(documentArchiveRelationshipService.upsertDocumentArchiveRelationships(any(), any())).thenReturn(0);

        service = new PurviewArchiveReconciliationServiceImpl(
                purviewConfig,
                schemaPlannerService,
                lockStateService,
                jobStateService,
                cursorStateService,
                tombstoneStateService,
                archivePublishService,
                entityPayloadFactory,
                entityRegistryClient,
                contentDaoService,
                documentArchiveRelationshipService);
    }

    @Test
    public void testStartArchiveReconciliationRejectsWhenRepositoryLockIsAlreadyHeld() {
        when(lockStateService.tryAcquireRepositoryLock(any(), any(), any(), any())).thenReturn(false);

        PurviewJobState result = service.startArchiveReconciliation("bedroom", "admin");

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("already running"));
        verify(schemaPlannerService, never()).getSchemaDiff();
    }

    @Test
    public void testStartArchiveReconciliationFailsFastWhenSchemaBootstrapIsRequired() {
        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "", "", "3", "desired-hash", true,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));

        PurviewJobState result = service.startArchiveReconciliation("bedroom", "admin");

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorSummary().contains("schema bootstrap"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStartArchiveReconciliationUpsertsArchivedDocumentAndArchiveEntity() throws Exception {
        PurviewTombstoneState tombstone = archivedTombstone("doc-001");
        Archive archive = archive("archive-001", "doc-001");

        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "3", "current-hash", "3", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listTombstoneStates("bedroom")).thenReturn(List.of(tombstone));
        when(contentDaoService.getArchiveByOriginalId("bedroom", "doc-001")).thenReturn(archive);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));

        PurviewJobState result = service.startArchiveReconciliation("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getProcessedCount());
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), argThat(payload -> {
            List<Map<String, Object>> entities = (List<Map<String, Object>>) payload.get("entities");
            return entities.size() == 2
                    && entities.stream().anyMatch(entity -> "nemaki_document".equals(entity.get("typeName")))
                    && entities.stream().anyMatch(entity -> "nemaki_archive".equals(entity.get("typeName")));
        }));
        verify(documentArchiveRelationshipService).upsertDocumentArchiveRelationships("bedroom", List.of(archive));
        verify(archivePublishService).buildRepositoryArchiveSnapshot("bedroom");
        verify(cursorStateService).saveCursorState(any());
        verify(tombstoneStateService).deleteTombstoneState("bedroom", "doc-001");
    }

    @Test
    public void testStartArchiveReconciliationDeletesTombstoneWhenLiveDocumentExistsAgain() throws Exception {
        PurviewTombstoneState tombstone = archivedTombstone("doc-002");
        Document liveDocument = new Document();
        liveDocument.setId("doc-002");

        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "3", "current-hash", "3", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listTombstoneStates("bedroom")).thenReturn(List.of(tombstone));
        when(contentDaoService.getContentFresh("bedroom", "doc-002")).thenReturn(liveDocument);

        PurviewJobState result = service.startArchiveReconciliation("bedroom", "admin");

        assertEquals("COMPLETED", result.getStatus());
        verify(tombstoneStateService).deleteTombstoneState("bedroom", "doc-002");
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testStartArchiveReconciliationMarksTombstoneErrorWhenArchivePublishFails() throws Exception {
        PurviewTombstoneState tombstone = archivedTombstone("doc-003");
        Archive archive = archive("archive-003", "doc-003");

        when(schemaPlannerService.getSchemaDiff()).thenReturn(new PurviewSchemaDiff(
                "NemakiWare", "3", "current-hash", "3", "current-hash", false,
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(tombstoneStateService.listTombstoneStates("bedroom")).thenReturn(List.of(tombstone));
        when(contentDaoService.getArchiveByOriginalId("bedroom", "doc-003")).thenReturn(archive);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("registry unavailable"));

        PurviewJobState result = service.startArchiveReconciliation("bedroom", "admin");

        assertEquals("COMPLETED_WITH_ERRORS", result.getStatus());
        assertEquals(1, result.getFailedCount());
        verify(tombstoneStateService).saveTombstoneState(argThat(state ->
                "doc-003".equals(state.getObjectId()) && "ERROR".equals(state.getStatus())));
        verify(tombstoneStateService, never()).deleteTombstoneState("bedroom", "doc-003");
    }

    private PurviewTombstoneState archivedTombstone(String objectId) {
        return new PurviewTombstoneState(
                "bedroom",
                objectId,
                "nemaki_document",
                "nemaki://bedroom/objects/" + objectId,
                "token-" + objectId,
                "2026-03-20T05:00:00Z",
                "2026-03-20T05:00:05Z",
                "ARCHIVED");
    }

    private Archive archive(String archiveId, String originalId) {
        Archive archive = new Archive();
        archive.setId(archiveId);
        archive.setOriginalId(originalId);
        archive.setName("Archived " + originalId);
        archive.setType("cmis:document");
        archive.setCreator("alice");
        archive.setArchivedBy("archiver");
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
        archive.setVersionSeriesId("vs-" + originalId);
        archive.setVersionLabel("1.0");
        archive.setIsLatestVersion(Boolean.TRUE);
        archive.setCreated(calendar("2026-03-20T01:00:00Z"));
        archive.setArchivedAt(calendar("2026-03-20T03:00:00Z"));
        return archive;
    }

    private GregorianCalendar calendar(String isoInstant) {
        GregorianCalendar calendar = GregorianCalendar.from(java.time.ZonedDateTime.parse(isoInstant));
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        return calendar;
    }
}
