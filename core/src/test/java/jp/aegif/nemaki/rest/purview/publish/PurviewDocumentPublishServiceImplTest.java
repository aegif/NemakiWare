package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.relationship.PurviewContainmentRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentTypeRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewDocumentPublishServiceImplTest {

    private PurviewConfig config;
    private RepositoryInfoMap repositoryInfoMap;
    private ContentDaoService contentDaoService;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewContainmentRelationshipService containmentRelationshipService;
    private PurviewDocumentTypeRelationshipService documentTypeRelationshipService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewDocumentPublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        contentDaoService = mock(ContentDaoService.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        containmentRelationshipService = mock(PurviewContainmentRelationshipService.class);
        documentTypeRelationshipService = mock(PurviewDocumentTypeRelationshipService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenAnswer(this::successWithEntityCount);
        when(containmentRelationshipService.upsertContainmentRelationships(any(), any())).thenReturn(0);
        when(documentTypeRelationshipService.upsertDocumentTypeRelationships(any(), any())).thenReturn(0);

        service = new PurviewDocumentPublishServiceImpl(
                config,
                repositoryInfoMap,
                contentDaoService,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                containmentRelationshipService,
                documentTypeRelationshipService,
                deadLetterStateService);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryDocumentsTraversesFoldersAndPublishesDocumentBatch() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setName("Bedroom Repository");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(folder("folder-001"), document("doc-001", "root-001")));
        when(contentDaoService.getChildrenPaged("bedroom", "folder-001", 0, 100))
                .thenReturn(List.of(document("doc-002", "folder-001")));

        int processedCount = service.publishRepositoryHierarchy("bedroom");

        assertEquals(5, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(5, entities.size());
        assertEquals(List.of(
                "nemaki://bedroom",
                "nemaki://bedroom/objects/doc-001",
                "nemaki://bedroom/objects/doc-002",
                "nemaki://bedroom/objects/folder-001",
                "nemaki://bedroom/objects/root-001"),
                entities.stream()
                        .map(entity -> ((Map<String, Object>) entity.get("attributes")).get("qualifiedName").toString())
                        .sorted()
                        .toList());
        assertEquals(List.of(
                "nemaki_document",
                "nemaki_document",
                "nemaki_folder",
                "nemaki_folder",
                "nemaki_repository"),
                entities.stream()
                        .map(entity -> entity.get("typeName").toString())
                        .sorted()
                        .toList());
        verify(contentDaoService).getChildrenPaged("bedroom", "root-001", 0, 100);
        verify(contentDaoService).getChildrenPaged("bedroom", "folder-001", 0, 100);
    }

    @Test
    public void testPublishRepositoryDocumentsFailsWhenRootFolderIsUnknown() {
        when(repositoryInfoMap.get("bedroom")).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryHierarchy("bedroom"));

        assertEquals("Root folder ID is not configured for repository bedroom", error.getMessage());
    }

    @Test
    public void testUpsertContentsDeadLettersPartialFailures() throws Exception {
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.partialSuccess(
                        1, 1,
                        List.of(new PurviewEntityPublishResult.FailedItem(
                                "nemaki://bedroom/objects/doc-001", "nemaki_document", "Schema validation failed")),
                        "partial failure: 1 entities failed"));

        int processedCount = service.upsertContents("bedroom", List.of(document("doc-001", "root"), document("doc-002", "root")));

        assertEquals(1, processedCount);
        verify(deadLetterStateService).saveDeadLetterState(any());
    }

    @Test
    public void testPublishRepositoryDocumentsFailsWhenPurviewRejectsBulkUpsert() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged(eq("bedroom"), eq("root-001"), eq(0), eq(100)))
                .thenReturn(List.of(document("doc-001", "root-001")));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("rate limited"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryHierarchy("bedroom"));

        assertEquals("rate limited", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertContentsPublishesFoldersAndDocumentsTogether() throws Exception {
        int processedCount = service.upsertContents("bedroom", List.of(folder("folder-001"), document("doc-001", "folder-001")));

        assertEquals(2, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(List.of("nemaki_document", "nemaki_folder"),
                entities.stream().map(entity -> entity.get("typeName").toString()).sorted().toList());
    }

    @Test
    public void testUpsertContentsDelegatesDocumentTypeRelationshipsForCustomDocuments() {
        Document customDocument = new Document();
        customDocument.setId("doc-custom-001");
        customDocument.setName("doc-custom-001");
        customDocument.setParentId("folder-001");
        customDocument.setObjectType("D:custom:report");
        customDocument.setCreator("alice");
        customDocument.setModifier("bob");
        when(documentTypeRelationshipService.upsertDocumentTypeRelationships(any(), any())).thenReturn(1);

        int processedCount = service.upsertContents("bedroom", List.of(customDocument));

        assertEquals(2, processedCount);
        verify(documentTypeRelationshipService).upsertDocumentTypeRelationships(
                eq("bedroom"),
                eq(List.of(customDocument)));
    }

    @Test
    public void testUpsertContentsDelegatesContainmentRelationshipsForFoldersAndDocuments() {
        Folder folder = folder("folder-001");
        folder.setParentId("root-001");
        Content document = document("doc-001", "folder-001");
        when(containmentRelationshipService.upsertContainmentRelationships(any(), any())).thenReturn(2);

        int processedCount = service.upsertContents("bedroom", List.of(folder, document));

        assertEquals(4, processedCount);
        verify(containmentRelationshipService).upsertContainmentRelationships(
                eq("bedroom"),
                eq(List.of(folder, document)));
    }

    private Folder folder(String id) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(id);
        return folder;
    }

    private Content document(String id, String parentId) {
        Document document = new Document();
        document.setId(id);
        document.setName(id);
        document.setParentId(parentId);
        document.setObjectType("cmis:document");
        document.setCreator("alice");
        document.setModifier("bob");
        return document;
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
}
