package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.relationship.PurviewContainmentRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentTypeRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(containmentRelationshipService.upsertContainmentRelationships(any(), any(), anyMap())).thenReturn(0);
        when(documentTypeRelationshipService.upsertDocumentTypeRelationships(any(), any(), anyMap())).thenReturn(0);

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
        when(documentTypeRelationshipService.upsertDocumentTypeRelationships(any(), any(), anyMap())).thenReturn(1);

        int processedCount = service.upsertContents("bedroom", List.of(customDocument));

        assertEquals(2, processedCount);
        verify(documentTypeRelationshipService).upsertDocumentTypeRelationships(
                eq("bedroom"),
                eq(List.of(customDocument)),
                anyMap());
    }

    @Test
    public void testUpsertContentsDelegatesContainmentRelationshipsForFoldersAndDocuments() {
        Folder folder = folder("folder-001");
        folder.setParentId("root-001");
        Content document = document("doc-001", "folder-001");
        when(containmentRelationshipService.upsertContainmentRelationships(any(), any(), anyMap())).thenReturn(2);

        int processedCount = service.upsertContents("bedroom", List.of(folder, document));

        assertEquals(4, processedCount);
        verify(containmentRelationshipService).upsertContainmentRelationships(
                eq("bedroom"),
                eq(List.of(folder, document)),
                anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertContentsDocumentEntityContainsExpectedAttributes() throws Exception {
        Document doc = new Document();
        doc.setId("doc-attr-001");
        doc.setName("Report Q1");
        doc.setParentId("folder-001");
        doc.setObjectType("D:custom:report");
        doc.setCreator("alice");
        doc.setModifier("bob");

        service.upsertContents("bedroom", List.of(doc));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        Map<String, Object> docEntity = entities.stream()
                .filter(e -> "nemaki_document".equals(e.get("typeName")))
                .findFirst().orElse(null);
        assertNotNull(docEntity, "Document entity must be present in payload");
        Map<String, Object> attrs = (Map<String, Object>) docEntity.get("attributes");
        assertEquals("nemaki://bedroom/objects/doc-attr-001", attrs.get("qualifiedName"));
        assertEquals("Report Q1", attrs.get("name"));
        assertEquals("D:custom:report", attrs.get("typeId"));
        assertEquals("alice", attrs.get("owner"));
        assertEquals("folder-001", attrs.get("parentId"));
        assertEquals("alice", docEntity.get("createdBy"));
        assertEquals("bob", docEntity.get("updatedBy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertContentsFolderEntityContainsExpectedAttributes() throws Exception {
        Folder f = new Folder();
        f.setId("folder-attr-001");
        f.setName("Projects");
        f.setParentId("root-001");

        service.upsertContents("bedroom", List.of(f));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        Map<String, Object> folderEntity = entities.stream()
                .filter(e -> "nemaki_folder".equals(e.get("typeName")))
                .findFirst().orElse(null);
        assertNotNull(folderEntity, "Folder entity must be present in payload");
        Map<String, Object> attrs = (Map<String, Object>) folderEntity.get("attributes");
        assertEquals("nemaki://bedroom/objects/folder-attr-001", attrs.get("qualifiedName"));
        assertEquals("Projects", attrs.get("name"));
        assertEquals("root-001", attrs.get("parentId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryHierarchySetsFolderPathAttributes() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setName("Bedroom Repository");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        Folder rootFolder = folder("root-001");
        rootFolder.setName("Root");
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(rootFolder);

        Folder subFolder = folder("folder-001");
        subFolder.setName("Reports");
        Document doc = new Document();
        doc.setId("doc-001");
        doc.setName("Q1.pdf");
        doc.setParentId("folder-001");
        doc.setObjectType("cmis:document");
        doc.setCreator("alice");
        doc.setModifier("bob");

        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(subFolder, doc));
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(0L);

        service.publishRepositoryHierarchy("bedroom");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");

        // Root folder: folderPath = "/"
        Map<String, Object> rootEntity = entities.stream()
                .filter(e -> "nemaki_folder".equals(e.get("typeName")))
                .filter(e -> "root-001".equals(((Map<String, Object>) e.get("attributes")).get("objectId")))
                .findFirst().orElse(null);
        assertNotNull(rootEntity);
        assertEquals("/", ((Map<String, Object>) rootEntity.get("attributes")).get("folderPath"));

        // Sub folder: folderPath = "/Reports"
        Map<String, Object> subFolderEntity = entities.stream()
                .filter(e -> "nemaki_folder".equals(e.get("typeName")))
                .filter(e -> "folder-001".equals(((Map<String, Object>) e.get("attributes")).get("objectId")))
                .findFirst().orElse(null);
        assertNotNull(subFolderEntity);
        assertEquals("/Reports", ((Map<String, Object>) subFolderEntity.get("attributes")).get("folderPath"));

        // Document: folderPath = "/" (parent = root-001)
        Map<String, Object> docEntity = entities.stream()
                .filter(e -> "nemaki_document".equals(e.get("typeName")))
                .findFirst().orElse(null);
        assertNotNull(docEntity);
        assertEquals("/", ((Map<String, Object>) docEntity.get("attributes")).get("folderPath"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertContentsResolvesFolderPathViaParentChain() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);

        Folder parentFolder = new Folder();
        parentFolder.setId("folder-001");
        parentFolder.setName("Reports");
        parentFolder.setParentId("root-001");
        when(contentDaoService.getContent("bedroom", "folder-001")).thenReturn(parentFolder);

        Folder rootFolder = new Folder();
        rootFolder.setId("root-001");
        rootFolder.setName("Root");
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(rootFolder);

        Document doc = new Document();
        doc.setId("doc-moved-001");
        doc.setName("Report.pdf");
        doc.setParentId("folder-001");
        doc.setObjectType("cmis:document");
        doc.setCreator("alice");
        doc.setModifier("bob");

        service.upsertContents("bedroom", List.of(doc));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        Map<String, Object> docEntity = entities.get(0);
        Map<String, Object> attrs = (Map<String, Object>) docEntity.get("attributes");
        // folderPath should be resolved to /Reports (the parent folder's path)
        assertEquals("/Reports", attrs.get("folderPath"));
    }

    @Test
    public void testUpsertContentsAtlasModeResolvesUnmappedGuidsViaFollowUpLookup() throws Exception {
        // Configure Atlas on-prem mode
        when(config.isAtlasOnPrem()).thenReturn(true);
        when(config.getAtlasBasePath()).thenReturn("api/atlas/v2");

        // Bulk response returns no qualifiedName for the entities (only guid + typeName)
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(2, "published", Map.of()));

        // Follow-up lookup resolves GUIDs
        when(entityRegistryClient.getEntityByUniqueAttribute(any(), eq("nemaki_document"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001")))
                .thenReturn(Map.of("entity", Map.of("guid", "resolved-guid-001")));
        when(entityRegistryClient.getEntityByUniqueAttribute(any(), eq("nemaki_folder"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/folder-001")))
                .thenReturn(Map.of("entity", Map.of("guid", "resolved-guid-002")));

        service.upsertContents("bedroom", List.of(folder("folder-001"), document("doc-001", "folder-001")));

        // Verify follow-up lookups were made for both entities
        verify(entityRegistryClient).getEntityByUniqueAttribute(any(), eq("nemaki_document"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/doc-001"));
        verify(entityRegistryClient).getEntityByUniqueAttribute(any(), eq("nemaki_folder"), eq("qualifiedName"),
                eq("nemaki://bedroom/objects/folder-001"));
    }

    @Test
    public void testUpsertContentsPurviewModeSkipsGuidFollowUpLookup() throws Exception {
        // Purview cloud mode (default setUp)
        when(config.isAtlasOnPrem()).thenReturn(false);

        // Bulk response returns no qualifiedName for entities
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "published", Map.of()));

        service.upsertContents("bedroom", List.of(document("doc-001", "root")));

        // No follow-up lookups in Purview mode
        verify(entityRegistryClient, never()).getEntityByUniqueAttribute(any(), any(), any(), any());
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
