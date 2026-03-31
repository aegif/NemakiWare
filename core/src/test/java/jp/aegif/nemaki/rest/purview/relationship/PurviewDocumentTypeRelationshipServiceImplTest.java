package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
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

import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewDocumentTypeRelationshipServiceImplTest {

    private MetadataCatalogConnectionResolver connectionResolver;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewDocumentTypeRelationshipServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created"));

        service = new PurviewDocumentTypeRelationshipServiceImpl(
                connectionResolver,
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
        assertEquals("nemaki_document", end1.get("typeName"));
        assertEquals("nemaki_type_definition", end2.get("typeName"));
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

    @Test
    public void testUpsertDocumentTypeRelationshipsReturnsZeroForEmptyList() throws Exception {
        int processedCount = service.upsertDocumentTypeRelationships("bedroom", List.of());

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentTypeRelationshipsReturnsZeroForNullList() throws Exception {
        int processedCount = service.upsertDocumentTypeRelationships("bedroom", null);

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentTypeRelationshipsSkipsBuiltInTypes() throws Exception {
        Document cmisDocument = new Document();
        cmisDocument.setId("doc-001");
        cmisDocument.setObjectType("cmis:document");

        int processedCount = service.upsertDocumentTypeRelationships("bedroom", List.of(cmisDocument));

        assertEquals(0, processedCount);
        verify(entityRegistryClient, never()).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentTypeRelationshipsProcessesMultipleCustomDocuments() throws Exception {
        Document custom1 = new Document();
        custom1.setId("doc-custom-001");
        custom1.setObjectType("D:custom:report");
        Document custom2 = new Document();
        custom2.setId("doc-custom-002");
        custom2.setObjectType("D:custom:invoice");

        int processedCount = service.upsertDocumentTypeRelationships("bedroom", List.of(custom1, custom2));

        assertEquals(2, processedCount);
        verify(entityRegistryClient, times(2)).createRelationship(any(), any());
    }

    @Test
    public void testUpsertDocumentTypeRelationshipsThrowsOnClientException() throws Exception {
        Document customDocument = new Document();
        customDocument.setId("doc-custom-001");
        customDocument.setObjectType("D:custom:report");
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenThrow(new PurviewClientException("Connection refused"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertDocumentTypeRelationships("bedroom", List.of(customDocument)));

        assertTrue(error.getMessage().contains("Connection refused"));
    }
}
