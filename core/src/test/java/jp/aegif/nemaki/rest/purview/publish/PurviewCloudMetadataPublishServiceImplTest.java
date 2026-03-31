package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.sync.PurviewCloudMetadataSyncResult;
import jp.aegif.nemaki.rest.purview.lineage.PurviewCloudSyncLineageService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;

public class PurviewCloudMetadataPublishServiceImplTest {

    private RepositoryInfoMap repositoryInfoMap;
    private ContentDaoService contentDaoService;
    private PurviewDocumentPublishService documentPublishService;
    private PurviewCloudSyncLineageService cloudSyncLineageService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewEntityPayloadFactory entityPayloadFactory;
    private PurviewCloudMetadataPublishServiceImpl service;

    @BeforeEach
    public void setUp() {
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        contentDaoService = mock(ContentDaoService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        cloudSyncLineageService = mock(PurviewCloudSyncLineageService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);
        entityPayloadFactory = new PurviewEntityPayloadFactory();
        when(documentPublishService.upsertContents(any(), any())).thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
        when(cloudSyncLineageService.upsertCloudSyncLineage(any(), any())).thenAnswer(invocation -> invocation.getArgument(1, List.class).size());
        when(deadLetterStateService.saveDeadLetterState(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new PurviewCloudMetadataPublishServiceImpl(
                repositoryInfoMap,
                contentDaoService,
                documentPublishService,
                cloudSyncLineageService,
                deadLetterStateService,
                entityPayloadFactory);
    }

    @Test
    public void testBuildRepositoryCloudMetadataSnapshotIncludesOnlyDocumentsWithCloudMetadata() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(
                        documentWithCloud("doc-001", "root-001", "google", "cloud-001", "https://drive.example/doc-001",
                                "2026-03-20T03:00:00.000+0000"),
                        documentWithoutCloud("doc-002", "root-001")));

        String snapshot = service.buildRepositoryCloudMetadataSnapshot("bedroom");

        assertEquals("doc-001|google|cloud-001|https://drive.example/doc-001|2026-03-20T03:00:00.000+0000", snapshot);
    }

    @Test
    public void testPublishRepositoryCloudSyncLineagePublishesOnlyCloudLinkedDocuments() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        Document currentDocument = documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(currentDocument, documentWithoutCloud("doc-002", "root-001")));

        int publishedCount = service.publishRepositoryCloudSyncLineage("bedroom");

        assertEquals(1, publishedCount);
        verify(cloudSyncLineageService).upsertCloudSyncLineage("bedroom", List.of(currentDocument));
        verify(documentPublishService, never()).upsertContents(any(), any());
    }

    @Test
    public void testPublishRepositoryCloudSyncLineageMovesLineageFailureToDeadLetterAndContinues() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        Document currentDocument = documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(currentDocument));
        when(cloudSyncLineageService.upsertCloudSyncLineage("bedroom", List.of(currentDocument)))
                .thenThrow(new IllegalStateException("cloud lineage unavailable"));

        int publishedCount = service.publishRepositoryCloudSyncLineage("bedroom");

        assertEquals(0, publishedCount);
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "cloud-sync-lineage".equals(state.getStreamKind())
                        && "bedroom".equals(state.getEntryKey())
                        && "".equals(state.getCheckpoint())
                        && state.getErrorSummary().contains("cloud lineage unavailable")));
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedRepublishesChangedDocumentsAndClearsRemovedMetadata() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        Document currentDocument = documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100)).thenReturn(List.of(currentDocument));
        Content clearedDocument = documentWithoutCloud("doc-legacy", "root-001");
        when(contentDaoService.getContentFresh("bedroom", "doc-legacy")).thenReturn(clearedDocument);

        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged(
                "bedroom",
                "doc-001|google|cloud-old|https://drive.example/doc-001|2026-03-19T03:00:00.000+0000\n"
                        + "doc-legacy|microsoft|cloud-legacy|https://onedrive.example/doc-legacy|2026-03-19T01:00:00.000+0000");

        assertTrue(result.isChanged());
        assertEquals(2, result.getPublishedCount());
        assertEquals(1, result.getReconciledCount());
        verify(documentPublishService).upsertContents("bedroom", List.of(currentDocument));
        verify(documentPublishService).upsertContents("bedroom", List.of(clearedDocument));
        verify(cloudSyncLineageService).upsertCloudSyncLineage("bedroom", List.of(currentDocument));
        verify(cloudSyncLineageService).reconcileRemovedCloudSyncLineage(eq("bedroom"), argThat(entries ->
                entries.size() == 1
                        && "doc-legacy|microsoft|cloud-legacy|https://onedrive.example/doc-legacy|2026-03-19T01:00:00.000+0000"
                                .equals(entries.get("doc-legacy"))),
                argThat(keys -> keys.size() == 1 && keys.contains("google:cloud-001")));
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedMovesLineageFailureToDeadLetterAndContinues() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        Document currentDocument = documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100)).thenReturn(List.of(currentDocument));
        when(cloudSyncLineageService.upsertCloudSyncLineage("bedroom", List.of(currentDocument)))
                .thenThrow(new IllegalStateException("cloud lineage unavailable"));

        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged(
                "bedroom",
                "doc-001|google|cloud-old|https://drive.example/doc-001|2026-03-19T03:00:00.000+0000");

        assertTrue(result.isChanged());
        assertEquals(1, result.getPublishedCount());
        assertEquals(0, result.getReconciledCount());
        verify(documentPublishService).upsertContents("bedroom", List.of(currentDocument));
        verify(deadLetterStateService).saveDeadLetterState(argThat(state ->
                "bedroom".equals(state.getRepositoryId())
                        && "cloud-sync-lineage".equals(state.getStreamKind())
                        && "bedroom".equals(state.getEntryKey())
                        && state.getCheckpoint().contains("cloud-old")
                        && state.getErrorSummary().contains("cloud lineage unavailable")));
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedSkipsPublishWhenSnapshotIsUnchanged() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                        "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000")));

        String snapshot = service.buildRepositoryCloudMetadataSnapshot("bedroom");
        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged("bedroom", snapshot);

        assertFalse(result.isChanged());
        assertEquals(0, result.getProcessedCount());
        verify(documentPublishService, never()).upsertContents(any(), any());
        verify(cloudSyncLineageService, never()).upsertCloudSyncLineage(any(), any());
        verify(contentDaoService, never()).getContentFresh(eq("bedroom"), eq("doc-001"));
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedDeletesOrphanedExternalAssetWhenStableKeyChanges() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        // Document changed its cloud link from google:cloud-old to microsoft:cloud-new
        Document currentDocument = documentWithCloud("doc-001", "root-001", "microsoft", "cloud-new",
                "https://onedrive.example/cloud-new", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100)).thenReturn(List.of(currentDocument));

        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged(
                "bedroom",
                "doc-001|google|cloud-old|https://drive.example/doc-001|2026-03-19T03:00:00.000+0000");

        assertTrue(result.isChanged());
        // Old external asset (google:cloud-old) should be deleted since it's no longer active
        verify(cloudSyncLineageService).deleteObsoleteExternalAsset("bedroom", "google:cloud-old");
        verify(cloudSyncLineageService).upsertCloudSyncLineage("bedroom", List.of(currentDocument));
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedSkipsOrphanDeletionWhenOldStableKeyStillActive() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        // doc-001 changed stableKey from google:shared-file to microsoft:cloud-new
        Document changedDocument = documentWithCloud("doc-001", "root-001", "microsoft", "cloud-new",
                "https://onedrive.example/cloud-new", "2026-03-20T03:00:00.000+0000");
        // doc-002 still uses google:shared-file — so it must NOT be deleted
        Document sharedDocument = documentWithCloud("doc-002", "root-001", "google", "shared-file",
                "https://drive.example/shared-file", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(changedDocument, sharedDocument));

        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged(
                "bedroom",
                "doc-001|google|shared-file|https://drive.example/shared-file|2026-03-19T03:00:00.000+0000\n"
                        + "doc-002|google|shared-file|https://drive.example/shared-file|2026-03-20T03:00:00.000+0000");

        assertTrue(result.isChanged());
        // google:shared-file is still active via doc-002, so deleteObsoleteExternalAsset should NOT be called
        verify(cloudSyncLineageService, never()).deleteObsoleteExternalAsset(any(), any());
    }

    @Test
    public void testSyncRepositoryCloudMetadataIfChangedNoOrphanDeletionWhenStableKeyUnchanged() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        // Only the URL changed, not the stableKey (google:cloud-001 remains the same)
        Document currentDocument = documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                "https://drive.example/doc-001-updated", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100)).thenReturn(List.of(currentDocument));

        PurviewCloudMetadataSyncResult result = service.syncRepositoryCloudMetadataIfChanged(
                "bedroom",
                "doc-001|google|cloud-001|https://drive.example/doc-001|2026-03-19T03:00:00.000+0000");

        assertTrue(result.isChanged());
        // StableKey did not change, so no orphan deletion
        verify(cloudSyncLineageService, never()).deleteObsoleteExternalAsset(any(), any());
        verify(cloudSyncLineageService).upsertCloudSyncLineage("bedroom", List.of(currentDocument));
    }

    private Folder folder(String objectId) {
        Folder folder = new Folder();
        folder.setId(objectId);
        folder.setName(objectId);
        return folder;
    }

    private Document documentWithoutCloud(String objectId, String parentId) {
        Document document = new Document();
        document.setId(objectId);
        document.setName(objectId);
        document.setParentId(parentId);
        document.setObjectType("cmis:document");
        return document;
    }

    private Document documentWithCloud(
            String objectId,
            String parentId,
            String provider,
            String externalFileId,
            String cloudFileUrl,
            String cloudLastSyncedAt) {
        Document document = documentWithoutCloud(objectId, parentId);
        document.setAspects(List.of(new Aspect("nemaki:cloudDriveMetadata", List.of(
                new Property("nemaki:cloudProvider", provider),
                new Property("nemaki:cloudFileId", externalFileId),
                new Property("nemaki:cloudFileUrl", cloudFileUrl),
                new Property("nemaki:cloudLastSyncedAt", cloudLastSyncedAt)))));
        return document;
    }
}
