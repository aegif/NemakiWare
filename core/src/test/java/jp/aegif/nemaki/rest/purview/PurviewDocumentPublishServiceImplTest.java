package jp.aegif.nemaki.rest.purview;

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
    private PurviewDocumentPublishServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(PurviewConfig.class);
        repositoryInfoMap = mock(RepositoryInfoMap.class);
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
                .thenReturn(PurviewEntityPublishResult.success(2, "published"));

        service = new PurviewDocumentPublishServiceImpl(
                config,
                repositoryInfoMap,
                contentDaoService,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishRepositoryDocumentsTraversesFoldersAndPublishesDocumentBatch() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(folder("folder-001"), document("doc-001", "root-001")));
        when(contentDaoService.getChildrenPaged("bedroom", "folder-001", 0, 100))
                .thenReturn(List.of(document("doc-002", "folder-001")));

        int processedCount = service.publishRepositoryDocuments("bedroom");

        assertEquals(2, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient).bulkCreateOrUpdateEntities(any(), payloadCaptor.capture());
        List<Map<String, Object>> entities = (List<Map<String, Object>>) payloadCaptor.getValue().get("entities");
        assertEquals(2, entities.size());
        assertEquals(List.of("nemaki://bedroom/objects/doc-001", "nemaki://bedroom/objects/doc-002"),
                entities.stream()
                        .map(entity -> ((Map<String, Object>) entity.get("attributes")).get("qualifiedName").toString())
                        .sorted()
                        .toList());
        verify(contentDaoService).getChildrenPaged("bedroom", "root-001", 0, 100);
        verify(contentDaoService).getChildrenPaged("bedroom", "folder-001", 0, 100);
    }

    @Test
    public void testPublishRepositoryDocumentsFailsWhenRootFolderIsUnknown() {
        when(repositoryInfoMap.get("bedroom")).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryDocuments("bedroom"));

        assertEquals("Root folder ID is not configured for repository bedroom", error.getMessage());
    }

    @Test
    public void testPublishRepositoryDocumentsFailsWhenPurviewRejectsBulkUpsert() throws Exception {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged(eq("bedroom"), eq("root-001"), eq(0), eq(100)))
                .thenReturn(List.of(document("doc-001", "root-001")));
        when(entityRegistryClient.bulkCreateOrUpdateEntities(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("rate limited"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.publishRepositoryDocuments("bedroom"));

        assertEquals("rate limited", error.getMessage());
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
}
