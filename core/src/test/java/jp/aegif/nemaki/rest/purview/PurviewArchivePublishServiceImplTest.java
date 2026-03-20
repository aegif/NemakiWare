package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;

public class PurviewArchivePublishServiceImplTest {

    private PurviewConfig config;
    private ContentDaoService contentDaoService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentArchiveRelationshipService documentArchiveRelationshipService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewArchiveLineageService archiveLineageService;
    private PurviewArchivePublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        contentDaoService = mock(ContentDaoService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        documentArchiveRelationshipService = mock(PurviewDocumentArchiveRelationshipService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        archiveLineageService = mock(PurviewArchiveLineageService.class);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(4, "published"));
        when(entityRegistryClient.deleteByUniqueAttribute(any(), any(), any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "deleted"));
        when(documentArchiveRelationshipService.upsertDocumentArchiveRelationships(any(), any())).thenReturn(0);
        when(documentPublishService.upsertContents(any(), any())).thenReturn(1);

        service = new PurviewArchivePublishServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                contentDaoService,
                documentArchiveRelationshipService,
                documentPublishService,
                archiveLineageService);
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
        when(documentArchiveRelationshipService.upsertDocumentArchiveRelationships(any(), any())).thenReturn(1);

        int processedCount = service.publishRepositoryArchives("bedroom");

        assertEquals(3, processedCount);
        verify(documentArchiveRelationshipService).upsertDocumentArchiveRelationships(
                eq("bedroom"),
                eq(List.of(archive)));
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
}
