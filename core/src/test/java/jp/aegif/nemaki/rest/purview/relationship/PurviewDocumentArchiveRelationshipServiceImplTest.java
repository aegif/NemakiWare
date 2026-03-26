package jp.aegif.nemaki.rest.purview.relationship;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Archive;

public class PurviewDocumentArchiveRelationshipServiceImplTest {

    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentArchiveRelationshipServiceImpl service;

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
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created"));

        service = new PurviewDocumentArchiveRelationshipServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertDocumentArchiveRelationshipsPublishesDocumentArchiveRelationship() throws Exception {
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");

        int processedCount = service.upsertDocumentArchiveRelationships("bedroom", List.of(archive));

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).createRelationship(any(), payloadCaptor.capture());
        assertEquals("nemaki_document_has_archive", payloadCaptor.getValue().get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) payloadCaptor.getValue().get("end1");
        Map<String, Object> end2 = (Map<String, Object>) payloadCaptor.getValue().get("end2");
        assertEquals("nemaki://bedroom/objects/doc-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/archives/archive-001",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("DataSet", end1.get("typeName"));
        assertEquals("nemaki_archive", end2.get("typeName"));
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsFailsWhenPurviewRejectsRelationshipCreate() throws Exception {
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("relationship rejected"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertDocumentArchiveRelationships("bedroom", List.of(archive)));

        assertEquals("relationship rejected", error.getMessage());
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsReturnsZeroForEmptyList() throws Exception {
        int processedCount = service.upsertDocumentArchiveRelationships("bedroom", List.of());

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsReturnsZeroForNullList() throws Exception {
        int processedCount = service.upsertDocumentArchiveRelationships("bedroom", null);

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsSkipsArchiveWithBlankOriginalId() throws Exception {
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("");

        int processedCount = service.upsertDocumentArchiveRelationships("bedroom", List.of(archive));

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsProcessesMultipleArchives() throws Exception {
        Archive archive1 = new Archive();
        archive1.setId("archive-001");
        archive1.setOriginalId("doc-001");
        Archive archive2 = new Archive();
        archive2.setId("archive-002");
        archive2.setOriginalId("doc-002");

        int processedCount = service.upsertDocumentArchiveRelationships("bedroom", List.of(archive1, archive2));

        assertEquals(2, processedCount);
        verify(entityRegistryClient, times(2)).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentArchiveRelationshipsThrowsOnClientException() throws Exception {
        Archive archive = new Archive();
        archive.setId("archive-001");
        archive.setOriginalId("doc-001");
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenThrow(new PurviewClientException("Connection timed out"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertDocumentArchiveRelationships("bedroom", List.of(archive)));

        assertTrue(error.getMessage().contains("Connection timed out"));
    }
}
