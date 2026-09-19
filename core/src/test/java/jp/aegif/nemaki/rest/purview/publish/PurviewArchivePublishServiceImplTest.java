package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.lineage.PurviewArchiveLineageService;
import jp.aegif.nemaki.rest.purview.sync.PurviewArchiveSyncResult;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentArchiveRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;

public class PurviewArchivePublishServiceImplTest {

    private MetadataCatalogConnectionResolver connectionResolver;
    private ContentDaoService contentDaoService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentArchiveRelationshipService documentArchiveRelationshipService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchiveLineageService archiveLineageService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewArchivePublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        contentDaoService = mock(ContentDaoService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        documentArchiveRelationshipService = mock(PurviewDocumentArchiveRelationshipService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archiveLineageService = mock(PurviewArchiveLineageService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);

        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenAnswer(this::successWithEntityCount);
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "deleted"));
        when(documentArchiveRelationshipService.upsertDocumentArchiveRelationships(any(), any(), any())).thenReturn(0);
        when(documentPublishService.upsertContents(any(), any())).thenReturn(1);
        when(deadLetterStateService.saveDeadLetterState(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new PurviewArchivePublishServiceImpl(
                connectionResolver,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                contentDaoService,
                documentArchiveRelationshipService,
                documentPublishService,
                archiveLineageService,
                deadLetterStateService);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryArchivesPublishesArchivedDocumentsAndArchives() throws Exception {
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive("archive-001", "doc-001"), archive("archive-002", "doc-002")));

        int processedCount = service.publishRepositoryArchives("bedroom");

        assertEquals(4, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_archive", "nemaki_archive", "nemaki_document", "nemaki_document"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    public void testPublishRepositoryArchivesFailsWhenPurviewRejectsBulkUpsert() throws Exception {
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive("archive-001", "doc-001")));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("rate limited"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryArchives("bedroom"));

        assertEquals("rate limited", error.getMessage());
    }

    @Test
    public void testPublishRepositoryArchivesDelegatesDocumentArchiveRelationships() {
        Archive archive = archive("archive-001", "doc-001");
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive));
        when(documentArchiveRelationshipService.upsertDocumentArchiveRelationships(any(), any(), any())).thenReturn(1);

        int processedCount = service.publishRepositoryArchives("bedroom");

        assertEquals(3, processedCount);
        verify(documentArchiveRelationshipService).upsertDocumentArchiveRelationships(
                eq("bedroom"),
                eq(List.of(archive)),
                any());
    }

    @Test
    public void testPublishRepositoryArchivesDelegatesArchiveLineage() throws Exception {
        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://archive-bucket/bedroom/doc-001.bin"));
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive));

        service.publishRepositoryArchives("bedroom");

        verify(archiveLineageService).upsertArchiveLineage("bedroom", List.of(archive));
    }

    @Test
    public void testPublishRepositoryArchivesMovesArchiveLineageFailureToDeadLetterAndContinues() throws Exception {
        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://archive-bucket/bedroom/doc-001.bin"));
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive));
        when(archiveLineageService.upsertArchiveLineage("bedroom", List.of(archive)))
                .thenThrow(new IllegalStateException("archive lineage unavailable"));

        int processedCount = service.publishRepositoryArchives("bedroom");

        assertEquals(2, processedCount);
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "archive-lineage".equals(state.getStreamKind())
                        && "bedroom".equals(state.getEntryKey())
                        && "".equals(state.getCheckpoint())
                        && state.getErrorSummary().contains("archive lineage unavailable")));
    }

    @Test
    public void testPublishRepositoryArchivesClearsArchiveLineageDeadLetterOnSuccess() throws Exception {
        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://archive-bucket/bedroom/doc-001.bin"));
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive));

        service.publishRepositoryArchives("bedroom");

        verify(deadLetterStateService).deleteDeadLetterState("bedroom", "archive-lineage", "bedroom");
    }

    @Test
    public void testSyncRepositoryArchivesIfChangedRepublishesArchivesAndDeletesMissingArchiveEntities() throws Exception {
        Archive currentArchive = archive("archive-001", "doc-001");
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(currentArchive));

        PurviewArchiveSyncResult result = service.syncRepositoryArchivesIfChanged(
                "bedroom",
                "archive-001|doc-001|ARCHIVED_LOCAL|0|0\narchive-legacy|doc-legacy|ARCHIVED_LOCAL|0|0");

        assertTrue(result.isChanged());
        assertEquals(2, result.getPublishedCount());
        assertEquals(2, result.getReconciledCount());
        verify(entityRegistryClient).deleteByUniqueAttribute(any(), eq("nemaki_archive"), any(), eq("nemaki://bedroom/archives/archive-legacy"));
        verify(entityRegistryClient).deleteByUniqueAttribute(any(), eq("nemaki_document"), any(), eq("nemaki://bedroom/objects/doc-legacy"));
    }

    @Test
    public void testSyncRepositoryArchivesIfChangedRepublishesRestoredDocumentWhenArchiveDisappears() throws Exception {
        Archive currentArchive = archive("archive-001", "doc-001");
        jp.aegif.nemaki.model.Document liveDocument = new jp.aegif.nemaki.model.Document();
        liveDocument.setId("doc-legacy");
        liveDocument.setType("cmis:document");
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(currentArchive));
        when(contentDaoService.getContentFresh("bedroom", "doc-legacy")).thenReturn(liveDocument);

        PurviewArchiveSyncResult result = service.syncRepositoryArchivesIfChanged(
                "bedroom",
                "archive-001|doc-001|ARCHIVED_LOCAL|0|0\narchive-legacy|doc-legacy|ARCHIVED_LOCAL|0|0");

        assertEquals(2, result.getPublishedCount());
        assertEquals(2, result.getReconciledCount());
        verify(documentPublishService).upsertContents("bedroom", List.of(liveDocument));
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), eq("nemaki_document"), any(), eq("nemaki://bedroom/objects/doc-legacy"));
    }

    @Test
    public void testSyncRepositoryArchivesIfChangedSkipsPurviewCallsWhenSnapshotIsUnchanged() throws Exception {
        Archive currentArchive = archive("archive-001", "doc-001");
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(currentArchive));

        PurviewArchiveSyncResult result = service.syncRepositoryArchivesIfChanged(
                "bedroom",
                service.buildRepositoryArchiveSnapshot("bedroom"));

        assertFalse(result.isChanged());
        assertEquals(0, result.getProcessedCount());
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
        verify(entityRegistryClient, never()).deleteByUniqueAttribute(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryArchivesArchiveEntityContainsExpectedAttributes() throws Exception {
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(List.of(archive("archive-attr-001", "doc-attr-001")));

        service.publishRepositoryArchives("bedroom");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        Map<String, Object> archiveEntity = entities.stream()
                .filter(e -> "nemaki_archive".equals(e.get("typeName")))
                .findFirst().orElse(null);
        assertNotNull(archiveEntity, "Archive entity must be present in payload");
        Map<String, Object> attrs = (Map<String, Object>) archiveEntity.get("attributes");
        assertEquals("nemaki://bedroom/archives/archive-attr-001", attrs.get("qualifiedName"));
        assertEquals("doc-attr-001", attrs.get("name"));
        assertEquals("doc-attr-001", attrs.get("originalObjectId"));
    }

    private Archive archive(String archiveId, String originalId) {
        Archive archive = new Archive();
        archive.setId(archiveId);
        archive.setOriginalId(originalId);
        archive.setName(originalId);
        archive.setType("cmis:document");
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

    @SuppressWarnings("unchecked")
    private PurviewEntityPublishResult successWithEntityCount(InvocationOnMock invocation) {
        Map<String, Object> payload = invocation.getArgument(1);
        if (payload == null) {
            return PurviewEntityPublishResult.success(0, "published");
        }
        Object entities = payload.get("entities");
        int count = entities instanceof List<?> list ? list.size() : 0;
        return PurviewEntityPublishResult.success(count, "published");
    }

    @org.junit.jupiter.api.Test
    public void anUnreadableArchiveRowRefusesTheSyncInsteadOfReconciling() {
        // A row the archive view returned and the DAO could not decode is an archive that
        // EXISTS. Before this guard the row was warn-and-dropped with no counter, the short
        // page read as the last page, and the snapshot diff then reconciled every hidden
        // archive out of the external catalog as "missing" — absence-equals-delete over a
        // listing that was known (to nobody) to be short. There is no total to bound a
        // skip-forward here, so the honest response is refusal: sync fails, cursor stays,
        // dead letter lives, nothing is deleted.
        when(contentDaoService.getArchives("bedroom", 0, 100, Boolean.FALSE))
                .thenReturn(java.util.List.of());
        when(contentDaoService.lastUnreadableArchiveCount()).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.syncRepositoryArchivesIfChanged("bedroom",
                        "archive-hidden|orig-1|hash"),
                "a listing that lost rows was diffed against the snapshot anyway, so the "
                        + "hidden archives get reconciled out of the catalog");
    }
}
