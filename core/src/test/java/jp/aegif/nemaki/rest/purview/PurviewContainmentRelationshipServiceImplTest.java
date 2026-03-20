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

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewContainmentRelationshipServiceImplTest {

    private RepositoryInfoMap repositoryInfoMap;
    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewContainmentRelationshipServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        config = mock(PurviewConfig.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);

        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);

        when(config.getEndpoint()).thenReturn("https://example-account.purview.azure.com");
        when(config.getAtlasBasePath()).thenReturn("datamap/api/atlas/v2");
        when(config.getTenantId()).thenReturn("tenant-123");
        when(config.getClientId()).thenReturn("client-123");
        when(config.getClientSecret()).thenReturn("secret-123");
        when(config.getConnectTimeoutMs()).thenReturn(5000);
        when(config.getReadTimeoutMs()).thenReturn(30000);
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created"));

        service = new PurviewContainmentRelationshipServiceImpl(
                repositoryInfoMap,
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertContainmentRelationshipsPublishesRepositoryFolderAndFolderChildrenRelationships() throws Exception {
        Folder root = folder("root-001", null);
        Folder childFolder = folder("folder-001", "root-001");
        Document childDocument = document("doc-001", "folder-001");

        int processedCount = service.upsertContainmentRelationships("bedroom", List.of(root, childFolder, childDocument));

        assertEquals(3, processedCount);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(entityRegistryClient, org.mockito.Mockito.times(3)).createRelationship(any(), payloadCaptor.capture());
        List<String> typeNames = payloadCaptor.getAllValues().stream()
                .map(payload -> payload.get("typeName").toString())
                .sorted()
                .toList();
        assertEquals(List.of(
                "nemaki_folder_contains_document",
                "nemaki_folder_contains_folder",
                "nemaki_repository_contains_folder"),
                typeNames);
    }

    @Test
    public void testUpsertContainmentRelationshipsFailsWhenPurviewRejectsRelationshipCreate() throws Exception {
        Folder root = folder("root-001", null);
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.failure("relationship rejected"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upsertContainmentRelationships("bedroom", List.of(root)));

        assertEquals("relationship rejected", error.getMessage());
    }

    private Folder folder(String id, String parentId) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setParentId(parentId);
        return folder;
    }

    private Document document(String id, String parentId) {
        Document document = new Document();
        document.setId(id);
        document.setParentId(parentId);
        return document;
    }
}
