package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewDocumentTypeRelationshipServiceImplTest {

    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentTypeRelationshipServiceImpl service;

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

        service = new PurviewDocumentTypeRelationshipServiceImpl(
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertDocumentTypeRelationshipsPublishesCustomDocumentRelationships() throws Exception {
        Document customDocument = new Document();
        customDocument.setId("doc-custom-001");
        customDocument.setObjectType("D:custom:report");
        Document builtInDocument = new Document();
        builtInDocument.setId("doc-built-in-001");
        builtInDocument.setObjectType("cmis:document");
        Folder folder = new Folder();
        folder.setId("folder-001");

        int processedCount = service.upsertDocumentTypeRelationships(
                "bedroom",
                List.of(customDocument, builtInDocument, folder));

        assertEquals(1, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).createRelationship(any(), payloadCaptor.capture());
        assertEquals("nemaki_document_has_type_definition", payloadCaptor.getValue().get("typeName"));
        Map<String, Object> end1 = (Map<String, Object>) payloadCaptor.getValue().get("end1");
        Map<String, Object> end2 = (Map<String, Object>) payloadCaptor.getValue().get("end2");
        assertEquals("nemaki://bedroom/objects/doc-custom-001",
                ((Map<String, Object>) end1.get("uniqueAttributes")).get("qualifiedName"));
        assertEquals("nemaki://bedroom/types/D:custom:report",
                ((Map<String, Object>) end2.get("uniqueAttributes")).get("qualifiedName"));
    }

    @Test
    public void testUpsertDocumentTypeRelationshipsFailsWhenPurviewRejectsRelationshipCreate() throws Exception {
        Document customDocument = new Document();
        customDocument.setId("doc-custom-001");
        customDocument.setObjectType("D:custom:report");
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("relationship rejected"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertDocumentTypeRelationships("bedroom", List.of(customDocument)));

        assertEquals("relationship rejected", error.getMessage());
    }
}
