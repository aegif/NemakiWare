package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.sync.PurviewContainmentSyncResult;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

public class PurviewContainmentRelationshipServiceImplTest {

    private RepositoryInfoMap repositoryInfoMap;
    private ContentDaoService contentDaoService;
    private PurviewConfig config;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewStateStore stateStore;
    private PurviewContainmentRelationshipServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        contentDaoService = mock(ContentDaoService.class);
        config = mock(PurviewConfig.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        stateStore = mock(PurviewStateStore.class);

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
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created", "rel-guid"));
        when(entityRegistryClient.deleteRelationshipByGuid(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship deleted", "rel-guid"));

        service = new PurviewContainmentRelationshipServiceImpl(
                repositoryInfoMap,
                contentDaoService,
                config,
                new PurviewEntityPayloadFactory(),
                entityRegistryClient,
                stateStore);
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
        List<String> end1QualifiedNames = payloadCaptor.getAllValues().stream()
                .map(payload -> ((Map<String, Object>) ((Map<String, Object>) payload.get("end1")).get("uniqueAttributes")).get("qualifiedName").toString())
                .sorted()
                .toList();
        assertEquals(List.of(
                "nemaki://bedroom",
                "nemaki://bedroom/objects/folder-001",
                "nemaki://bedroom/objects/root-001"),
                end1QualifiedNames);
        List<String> end2QualifiedNames = payloadCaptor.getAllValues().stream()
                .map(payload -> ((Map<String, Object>) ((Map<String, Object>) payload.get("end2")).get("uniqueAttributes")).get("qualifiedName").toString())
                .sorted()
                .toList();
        assertEquals(List.of(
                "nemaki://bedroom/objects/doc-001",
                "nemaki://bedroom/objects/folder-001",
                "nemaki://bedroom/objects/root-001"),
                end2QualifiedNames);
        verify(stateStore, times(3)).putAll(any());
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

    @Test
    public void testBuildRepositoryContainmentSnapshotTraversesRootAndChildren() {
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001", null));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(folder("folder-001", "root-001"), document("doc-001", "root-001")));
        when(contentDaoService.getChildrenPaged("bedroom", "folder-001", 0, 100))
                .thenReturn(List.of(document("doc-002", "folder-001")));

        String snapshot = service.buildRepositoryContainmentSnapshot("bedroom");

        assertEquals(String.join("\n",
                "nemaki_folder_contains_document|nemaki://bedroom/objects/folder-001|nemaki://bedroom/objects/doc-002",
                "nemaki_folder_contains_document|nemaki://bedroom/objects/root-001|nemaki://bedroom/objects/doc-001",
                "nemaki_folder_contains_folder|nemaki://bedroom/objects/root-001|nemaki://bedroom/objects/folder-001",
                "nemaki_repository_contains_folder|nemaki://bedroom|nemaki://bedroom/objects/root-001"), snapshot);
    }

    @Test
    public void testSyncRepositoryContainmentRelationshipsIfChangedCreatesNewEdgesAndDeletesRemovedEdges() throws Exception {
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001", null));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(document("doc-001", "root-001")));
        when(stateStore.getString("purview.containment.relationship.guid.bedroom."
                + "bmVtYWtpX2ZvbGRlcl9jb250YWluc19kb2N1bWVudHxuZW1ha2k6Ly9iZWRyb29tL29iamVjdHMvcm9vdC0wMDF8bmVtYWtpOi8vYmVkcm9vbS9vYmplY3RzL2RvYy1sZWdhY3k"))
                .thenReturn("rel-legacy");

        PurviewContainmentSyncResult result = service.syncRepositoryContainmentRelationshipsIfChanged(
                "bedroom",
                String.join("\n",
                        "nemaki_folder_contains_document|nemaki://bedroom/objects/root-001|nemaki://bedroom/objects/doc-legacy",
                        "nemaki_repository_contains_folder|nemaki://bedroom|nemaki://bedroom/objects/root-001"));

        assertEquals(true, result.isChanged());
        assertEquals(1, result.getPublishedCount());
        assertEquals(1, result.getReconciledCount());
        verify(entityRegistryClient, times(1)).createRelationship(any(), any());
        verify(entityRegistryClient).deleteRelationshipByGuid(any(), eq("rel-legacy"));
        verify(stateStore).removeAll(any());
    }

    @Test
    public void testSyncRepositoryContainmentRelationshipsIfChangedSkipsCallsWhenSnapshotIsUnchanged() throws Exception {
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001", null));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(document("doc-001", "root-001")));

        String snapshot = String.join("\n",
                "nemaki_folder_contains_document|nemaki://bedroom/objects/root-001|nemaki://bedroom/objects/doc-001",
                "nemaki_repository_contains_folder|nemaki://bedroom|nemaki://bedroom/objects/root-001");
        PurviewContainmentSyncResult result = service.syncRepositoryContainmentRelationshipsIfChanged("bedroom", snapshot);

        assertEquals(false, result.isChanged());
        assertEquals(0, result.getProcessedCount());
        verify(entityRegistryClient, never()).createRelationship(any(), any());
        verify(entityRegistryClient, never()).deleteRelationshipByGuid(any(), any());
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
