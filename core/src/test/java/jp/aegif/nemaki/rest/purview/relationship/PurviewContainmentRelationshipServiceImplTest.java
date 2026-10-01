package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.sync.PurviewContainmentSyncResult;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private MetadataCatalogConnectionResolver connectionResolver;
    private PurviewEntityRegistryClient entityRegistryClient;
    private PurviewStateStore stateStore;
    private PurviewContainmentRelationshipServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        contentDaoService = mock(ContentDaoService.class);
        connectionResolver = mock(MetadataCatalogConnectionResolver.class);
        entityRegistryClient = mock(PurviewEntityRegistryClient.class);
        stateStore = mock(PurviewStateStore.class);

        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);

        when(connectionResolver.buildConnectionRequest()).thenReturn(
                new PurviewConnectionRequest("https://example-account.purview.azure.com",
                        "datamap/api/atlas/v2", "tenant-123", "client-123", "secret-123", 5000, 30000));
        when(entityRegistryClient.createRelationship(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship created", "rel-guid"));
        when(entityRegistryClient.deleteRelationshipByGuid(any(), any()))
                .thenReturn(PurviewEntityPublishResult.success(1, "relationship deleted", "rel-guid"));

        service = new PurviewContainmentRelationshipServiceImpl(
                repositoryInfoMap,
                contentDaoService,
                connectionResolver,
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

    @Test
    public void anIncompleteWalkPublishesButNeverDeletes() throws Exception {
        // A row the store cannot decode is absent from the page without an exception. Before
        // this guard, the missing edge was diffed as a DELETED relationship and removed from
        // the external catalog, and the shortened page ended the folder walk early — one bad
        // row hid a subtree and then erased its containment. The rule: an incomplete walk may
        // ADD what it saw (those edges exist), deletes NOTHING, and WIDENS the previous
        // snapshot with what it published — never narrows it — so the next complete walk
        // diffs against a baseline that still holds the invisible edges AND now holds the
        // published ones (see the round-trip test below for why the second half matters).
        Folder root = folder("root-001", null);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(root);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) folder("folder-001", "root-001")));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(0L);

        // The previous snapshot holds an edge the walk cannot see this round: the one that
        // used to be deleted for being invisible. Its GUID is tracked, so with the guard
        // removed the delete is REACHED and the verify below goes red on its own assertion —
        // without the stub, the sabotaged run died on "GUID is not tracked" instead, which
        // the control runner rightly refuses to count as the lock firing.
        String previousSnapshot = "nemaki_folder_contains_document|nemaki://bedroom/objects/"
                + "folder-001|nemaki://bedroom/objects/doc-hidden";
        // The state key is Base64 of the edge key, so match any key: every edge in this
        // fixture then "has" a GUID, which (a) lets the sabotaged run REACH the delete and
        // fail on the verify's own assertion rather than dying on "GUID is not tracked",
        // and (b) exercises the already-created skip on the publish side.
        when(stateStore.getString(anyString())).thenReturn("hidden-rel-guid");

        PurviewContainmentSyncResult result =
                service.syncRepositoryContainmentRelationshipsIfChanged("bedroom", previousSnapshot);

        verify(entityRegistryClient, org.mockito.Mockito.never()).deleteRelationshipByGuid(any(), any());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains(previousSnapshot),
                "an incomplete walk NARROWED the snapshot — the invisible edge left the "
                        + "baseline, so a later complete walk cannot tell it from a deletion: "
                        + result.getSnapshot());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("nemaki_folder_contains_folder|"
                        + "nemaki://bedroom/objects/root-001|nemaki://bedroom/objects/folder-001"),
                "the edge published this round is missing from the baseline — if it vanishes "
                        + "before a complete walk, the external catalog keeps it for ever: "
                        + result.getSnapshot());
    }

    @Test
    public void theRecordedGuidsCanBeForgottenSoTheCatalogIsRepairable() throws Exception {
        // The publish path skips an edge whose GUID this store already holds. That is right
        // whenever we are the only writer — and a relationship deleted in the catalog
        // out-of-band breaks it: our snapshot and the catalog agree from our side, so no diff
        // will ever notice. Detecting it would mean reading every relationship back on every
        // cycle. Making it REPAIRABLE costs one call, and without that call an operator has
        // no way back at all.
        when(stateStore.getAllByPrefix("purview.containment.relationship.guid.bedroom."))
                .thenReturn(java.util.Map.of(
                        "purview.containment.relationship.guid.bedroom.abc", "guid-1",
                        "purview.containment.relationship.guid.bedroom.def", "guid-2"));

        int forgotten = service.forgetRecordedRelationshipGuids("bedroom");

        assertEquals(2, forgotten);
        ArgumentCaptor<java.util.Collection<String>> removed =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(stateStore).removeAll(removed.capture());
        org.junit.jupiter.api.Assertions.assertEquals(2, removed.getValue().size(),
                "the recorded GUIDs were not dropped, so the next sync still skips every "
                        + "edge it believes it already created");
    }

    @Test
    public void aCreatedEdgeFromAnIncompleteRoundIsDeletedOnceItVanishes() throws Exception {
        // The §44-1 hole this pins: an edge CREATED during an incomplete round was published
        // to the external catalog but left out of the persisted baseline. If it vanished
        // before a complete walk, that walk found it in NEITHER side of the diff — the stale
        // relationship (and its GUID in the state store) stayed in the external catalog for
        // ever. With the widened baseline, the complete walk sees it in the previous side
        // only and reconciles it away like any other deletion.
        Folder root = folder("root-001", null);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(root);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L, 0L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) folder("folder-001", "root-001")))
                .thenReturn(List.of());
        // Round 1: one row of the page would not decode. Round 2: the walk is complete.
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1, 0);
        when(contentDaoService.getChildrenCount("bedroom", "folder-001")).thenReturn(0L);
        when(stateStore.getString(anyString())).thenReturn("created-rel-guid");

        PurviewContainmentSyncResult incompleteRound =
                service.syncRepositoryContainmentRelationshipsIfChanged("bedroom", "");
        // folder-001 has meanwhile been deleted; the second walk reads cleanly.
        PurviewContainmentSyncResult completeRound = service
                .syncRepositoryContainmentRelationshipsIfChanged("bedroom",
                        incompleteRound.getSnapshot());

        verify(entityRegistryClient, org.mockito.Mockito.atLeastOnce())
                .deleteRelationshipByGuid(any(), eq("created-rel-guid"));
        org.junit.jupiter.api.Assertions.assertFalse(
                completeRound.getSnapshot().contains("folder-001"),
                "the vanished edge survived into the complete walk's snapshot: "
                        + completeRound.getSnapshot());
    }
}
