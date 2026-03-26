package jp.aegif.nemaki.rest.purview.lineage;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import jp.aegif.nemaki.model.Archive;

public class PurviewArchiveLineageServiceImplTest {

    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewArchiveLineageServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));

        service = new PurviewArchiveLineageServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertArchiveLineagePublishesExternalAssetAndArchiveProcess() throws Exception {
        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://archive-bucket/bedroom/doc-001.bin"));

        int processedCount = service.upsertArchiveLineage("bedroom", List.of(archive));

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_archive_process", "nemaki_external_asset"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());

        Map<String, Object> processEntity = entities.stream()
                .filter(entity -> "nemaki_archive_process".equals(entity.get("typeName")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> processAttrs = (Map<String, Object>) processEntity.get("attributes");
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", processAttrs.get("externalStableKey"));
        assertEquals("s3://archive-bucket/bedroom/doc-001.bin", processAttrs.get("targetDescription"));
        Map<String, Object> relationshipAttributes = (Map<String, Object>) processEntity.get("relationshipAttributes");
        assertEquals(1, ((List<?>) relationshipAttributes.get("inputs")).size());
        assertEquals(2, ((List<?>) relationshipAttributes.get("outputs")).size());

        Map<String, Object> externalEntity = entities.stream()
                .filter(entity -> "nemaki_external_asset".equals(entity.get("typeName")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> externalAttrs = (Map<String, Object>) externalEntity.get("attributes");
        assertTrue(externalAttrs.get("qualifiedName").toString().contains("bedroom"));
    }

    @Test
    public void testUpsertArchiveLineageSkipsArchivesWithoutStableTarget() throws Exception {
        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);

        int processedCount = service.upsertArchiveLineage("bedroom", List.of(archive));

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testUpsertArchiveLineageReturnsZeroForEmptyList() throws Exception {
        int processedCount = service.upsertArchiveLineage("bedroom", List.of());

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testUpsertArchiveLineageReturnsZeroForNullList() throws Exception {
        int processedCount = service.upsertArchiveLineage("bedroom", null);

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).bulkCreateOrUpdateEntities(any(), any());
    }

    @Test
    public void testUpsertArchiveLineageThrowsWhenPublishFails() throws Exception {
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("HTTP 500: Internal Server Error"));

        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://bucket/doc-001.bin"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertArchiveLineage("bedroom", List.of(archive)));

        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    public void testUpsertArchiveLineageThrowsWhenPurviewClientExceptionOccurs() throws Exception {
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenThrow(new PurviewClientException("Connection refused"));

        Archive archive = archive("archive-001", "doc-001");
        archive.setArchiveState(Archive.STATE_ARCHIVED_COLD);
        archive.setContentRef(Map.of("type", "s3", "ref", "s3://bucket/doc-001.bin"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertArchiveLineage("bedroom", List.of(archive)));

        assertTrue(error.getMessage().contains("Connection refused"));
    }

    private Archive archive(String archiveId, String originalId) {
        Archive archive = new Archive();
        archive.setId(archiveId);
        archive.setOriginalId(originalId);
        archive.setName(originalId);
        archive.setArchivedBy("archiver");
        archive.setArchivedAt(calendar("2026-03-20T03:00:00Z"));
        archive.setColdArchivedAt(calendar("2026-03-20T04:00:00Z"));
        return archive;
    }

    private GregorianCalendar calendar(String isoInstant) {
        GregorianCalendar calendar = GregorianCalendar.from(java.time.ZonedDateTime.parse(isoInstant));
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        return calendar;
    }
}
