package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private PurviewArchivePublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        contentDaoService = mock(ContentDaoService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(4, "published"));

        service = new PurviewArchivePublishServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                contentDaoService);
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
