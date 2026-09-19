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

    /**
     * Models the publish service's real contract: {@code upsertContents} returns a MIXED
     * count (entities + containment + document-type edges) and
     * {@code lastEntityPublishFailureCount} reports, per call, how many of that call's
     * documents did not get their entity in. Stubbing only the return value — which these
     * tests used to do — cannot express "the entity failed but the edges landed", which is
     * the case the round-34 review found slipping into the baseline.
     */
    @SuppressWarnings("unchecked")
    private void entityPublishFailsFor(String... failingObjectIds) {
        java.util.Set<String> failing = java.util.Set.of(failingObjectIds);
        java.util.concurrent.atomic.AtomicInteger lastFailures =
                new java.util.concurrent.atomic.AtomicInteger(0);
        // doAnswer, not when(): when() CALLS the mock, and the setUp answer already
        // registered would run with null arguments during stubbing.
        org.mockito.Mockito.doAnswer(invocation -> {
            List<Content> batch = invocation.getArgument(1, List.class);
            int failures = 0;
            for (Content content : batch) {
                if (content != null && failing.contains(content.getId())) {
                    failures++;
                }
            }
            lastFailures.set(failures);
            // Deliberately NOT "landed count": the edges of a failed entity still count,
            // so the mixed return stays positive even when every entity failed.
            return batch.size() + 1;
        }).when(documentPublishService).upsertContents(any(), any());
        org.mockito.Mockito.doAnswer(invocation -> lastFailures.get())
                .when(documentPublishService).lastEntityPublishFailureCount();
    }

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

        // The URL slot is empty by contract: the snapshot is persisted as the cursor and served
        // by the admin API, and a drive URL's query string is where sharing tokens live. The slot
        // itself stays so stored old-format cursors keep their field positions.
        assertEquals("doc-001|google|cloud-001||2026-03-20T03:00:00.000+0000", snapshot);
        assertFalse(snapshot.contains("https://"), "no URL may enter the cursor");
    }

    /**
     * A cursor stored before the URL left the format must compare as unchanged against a fresh
     * snapshot of the same documents — otherwise the first sync after upgrade republishes every
     * cloud-linked document for no observable catalog difference.
     */
    @Test
    public void testAnOldFormatCursorWithUrlsComparesAsUnchanged() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                        "https://drive.example/doc-001", "2026-03-20T03:00:00.000+0000")));

        String oldFormatCursor =
                "doc-001|google|cloud-001|https://drive.example/doc-001?authkey=SECRET|2026-03-20T03:00:00.000+0000";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", oldFormatCursor);

        assertFalse(result.isChanged());
        assertFalse(result.getSnapshot().contains("SECRET"),
                "the returned snapshot replaces the stored cursor and must be clean");
        verify(documentPublishService, never()).upsertContents(any(), any());
    }

    /** A URL-only change is no longer a change: the catalog cannot see it (A-1g). */
    @Test
    public void testAUrlOnlyChangeDoesNotTriggerRepublish() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(documentWithCloud("doc-001", "root-001", "google", "cloud-001",
                        "https://drive.example/doc-001?rotated=NEW", "2026-03-20T03:00:00.000+0000")));

        String oldCursorWithDifferentUrl =
                "doc-001|google|cloud-001|https://drive.example/doc-001?authkey=OLD|2026-03-20T03:00:00.000+0000";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", oldCursorWithDifferentUrl);

        assertFalse(result.isChanged());
        verify(documentPublishService, never()).upsertContents(any(), any());
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
                        // Normalised on parse: the URL slot from an old-format cursor is emptied
                        // before the entry travels anywhere further.
                        && "doc-legacy|microsoft|cloud-legacy||2026-03-19T01:00:00.000+0000"
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

    @Test
    public void anIncompleteWalkPublishesChangesButReconcilesNothing() {
        // A row the store cannot decode is absent from its page without an exception. Before
        // the guard, every document hidden that way landed in the "disappeared" set — the arm
        // that clears catalog copies, reconciles lineage away and deletes external assets —
        // and the short page also ended the folder early, hiding whole subtrees. Rule: an
        // incomplete walk publishes what it SAW, deletes nothing, and keeps the previous
        // snapshot so the next complete walk diffs against an honest baseline.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        jp.aegif.nemaki.model.Folder root = new jp.aegif.nemaki.model.Folder();
        root.setId("root-001");
        root.setType("cmis:folder");
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(root);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        when(contentDaoService.getChildrenPaged(org.mockito.ArgumentMatchers.eq("bedroom"),
                org.mockito.ArgumentMatchers.eq("root-001"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);

        // The previous snapshot claims a document the walk cannot see this round.
        String previousSnapshot = "doc-hidden=cloud://drive/f1|hash1";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", previousSnapshot);

        org.mockito.Mockito.verify(cloudSyncLineageService, org.mockito.Mockito.never())
                .reconcileRemovedCloudSyncLineage(any(), any(), any());
        org.mockito.Mockito.verify(cloudSyncLineageService, org.mockito.Mockito.never())
                .deleteObsoleteExternalAsset(any(), any());
        assertEquals(previousSnapshot, result.getSnapshot(),
                "an incomplete walk advanced the snapshot, so hidden documents become "
                        + "'removed' the moment a complete walk succeeds");
    }

    @Test
    public void aDocumentWhoseEntityFailedDoesNotEnterTheBaselineOnAMixedCount() {
        // The round-34 review's point: the publish call returns entities PLUS containment
        // PLUS document-type relationships, so a document whose ENTITY failed can still make
        // a single-document call return a positive number — through its companion or its
        // edges — and enter the baseline as if it had landed. The per-call entity-failure
        // count is what actually answers the question.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        Document mixedDocument = documentWithCloud("doc-mixed", "root-001", "google",
                "cloud-m", "https://drive.example/doc-mixed", "2026-03-20T06:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) mixedDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(0);
        // The entity failed, but the call still reports 2 (its edges landed) — exactly the
        // shape "> 0" could not tell from success.
        org.mockito.Mockito.doReturn(2).when(documentPublishService)
                .upsertContents("bedroom", List.of((Content) mixedDocument));
        org.mockito.Mockito.doReturn(1).when(documentPublishService)
                .lastEntityPublishFailureCount();

        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", "");

        org.junit.jupiter.api.Assertions.assertFalse(
                result.getSnapshot().contains("doc-mixed"),
                "a document whose entity failed entered the baseline because the mixed "
                        + "count was positive: " + result.getSnapshot());
    }

    @Test
    public void aCompleteWalkKeepsFailedDocumentsChangedInTheBaseline() {
        // The round-33 P1: the COMPLETE arm batch-upserted and advanced the baseline over
        // every changed document — one whose entity failed then read as "unchanged" next
        // round and was never republished (its document-entity dead letter has no retry
        // arm). Per-document publish + a baseline that keeps the failed document at its
        // PREVIOUS entry make the next round re-detect and retry it.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        Document landedDocument = documentWithCloud("doc-landed", "root-001", "google",
                "cloud-l", "https://drive.example/doc-landed", "2026-03-20T03:00:00.000+0000");
        Document failedDocument = documentWithCloud("doc-failed", "root-001", "google",
                "cloud-f2", "https://drive.example/doc-failed", "2026-03-20T04:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) landedDocument, (Content) failedDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(0);
        entityPublishFailsFor("doc-failed");

        // The failed document HAD a previous entry (its metadata changed this round).
        String previousSnapshot =
                "doc-failed|google|cloud-f2||2026-03-19T04:00:00.000+0000";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", previousSnapshot);

        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("doc-landed|google|cloud-l"),
                "the published document is missing from the baseline: " + result.getSnapshot());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("2026-03-19T04:00:00.000+0000"),
                "the failed document's baseline entry advanced to the NEW value, so the next"
                        + " round reads it as unchanged and never republishes: "
                        + result.getSnapshot());
    }

    @Test
    public void aCompleteWalkDropsAFailedNewDocumentFromTheBaseline() {
        // The new-document variant: no previous entry, so a failed publish must leave it
        // ABSENT from the baseline (still "new" next round), not enter it as published.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        Document failedNewDocument = documentWithCloud("doc-new-f", "root-001", "google",
                "cloud-nf", "https://drive.example/doc-new-f", "2026-03-20T05:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) failedNewDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(0);
        entityPublishFailsFor("doc-new-f");

        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", "");

        org.junit.jupiter.api.Assertions.assertFalse(
                result.getSnapshot().contains("doc-new-f"),
                "an unpublished NEW document entered the baseline as if it had landed: "
                        + result.getSnapshot());
    }

    @Test
    public void anIncompleteWalkWidensTheSnapshotWithWhatItPublished() {
        // The §44-1 hole, cloud twin: a document whose cloud link appeared during an
        // incomplete round is published above but was left out of the kept baseline. If the
        // link vanished before a complete walk, that walk found the document in neither side
        // of the diff — catalog copy never cleared, lineage never reconciled, external asset
        // stale for ever. The baseline now widens to previous ∪ published.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        Document createdDocument = documentWithCloud("doc-new", "root-001", "google", "cloud-new",
                "https://drive.example/doc-new", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(createdDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);

        String previousSnapshot = "doc-hidden|google|cloud-h||2026-03-19T01:00:00.000+0000";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", previousSnapshot);

        org.junit.jupiter.api.Assertions.assertTrue(result.isWalkIncomplete());
        verify(documentPublishService).upsertContents("bedroom", List.of((Content) createdDocument));
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("doc-hidden|google|cloud-h"),
                "the widened baseline lost the hidden document — a later complete walk reads "
                        + "that as a deletion: " + result.getSnapshot());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("doc-new|google|cloud-new"),
                "the published document is missing from the baseline — if its link vanishes "
                        + "before a complete walk, the external asset is never cleared: "
                        + result.getSnapshot());
    }

    @Test
    public void aPartiallyPublishedRoundWidensOnlyWhatLanded() {
        // The round-32 P1: upsertContents' batch return counts entities AND relationships,
        // so "batch count == input size" was not "every document published" — a partial
        // batch could coincidentally equal the size and put an UNPUBLISHED document into
        // the baseline, cancelling its retry for ever. The arm now publishes per document;
        // this pins the granularity: the landed document enters the baseline, the failed
        // one stays out and retries.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(3L);
        Document landedDocument = documentWithCloud("doc-landed", "root-001", "google",
                "cloud-l", "https://drive.example/doc-landed", "2026-03-20T03:00:00.000+0000");
        Document failedDocument = documentWithCloud("doc-failed", "root-001", "google",
                "cloud-f", "https://drive.example/doc-failed", "2026-03-20T04:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of((Content) landedDocument, (Content) failedDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);
        entityPublishFailsFor("doc-failed");

        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", "");

        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("doc-landed|google|cloud-l"),
                "the published document is missing from the baseline: " + result.getSnapshot());
        org.junit.jupiter.api.Assertions.assertFalse(
                result.getSnapshot().contains("doc-failed"),
                "a document that did NOT publish entered the baseline, so its publish is "
                        + "never retried: " + result.getSnapshot());
        assertEquals(1, result.getPublishedCount());
    }

    @Test
    public void aPartialPublishKeepsThePreviousSnapshot() {
        // upsertContents can skip documents without throwing (an unbuildable entity is
        // dropped, a failed batch is counted). Widening the baseline with a document that
        // was NOT actually published would cancel its retry: the next walk would read it as
        // already-baseline and never publish it. Merge only on full success.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(folder("root-001"));
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(2L);
        Document createdDocument = documentWithCloud("doc-new", "root-001", "google", "cloud-new",
                "https://drive.example/doc-new", "2026-03-20T03:00:00.000+0000");
        when(contentDaoService.getChildrenPaged("bedroom", "root-001", 0, 100))
                .thenReturn(List.of(createdDocument));
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);
        entityPublishFailsFor("doc-new");

        String previousSnapshot = "doc-hidden|google|cloud-h||2026-03-19T01:00:00.000+0000";
        PurviewCloudMetadataSyncResult result =
                service.syncRepositoryCloudMetadataIfChanged("bedroom", previousSnapshot);

        org.junit.jupiter.api.Assertions.assertFalse(
                result.getSnapshot().contains("doc-new"),
                "a document upsertContents did NOT publish entered the baseline, so its "
                        + "publish is never retried: " + result.getSnapshot());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getSnapshot().contains("doc-hidden|google|cloud-h"));
    }

    @Test
    public void theLineageRetryRefusesAnIncompleteWalk() {
        // Reachable through dead-letter retry: without this guard, every document hidden
        // behind an unreadable row landed in the "removed" set and its process entities (and
        // possibly shared external assets) were deleted from the catalog. Throwing keeps the
        // dead letter alive; nothing absence-based runs until a complete walk succeeds.
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setId("bedroom");
        repositoryInfo.setRootFolder("root-001");
        when(repositoryInfoMap.get("bedroom")).thenReturn(repositoryInfo);
        jp.aegif.nemaki.model.Folder root = new jp.aegif.nemaki.model.Folder();
        root.setId("root-001");
        root.setType("cmis:folder");
        when(contentDaoService.getContent("bedroom", "root-001")).thenReturn(root);
        when(contentDaoService.getChildrenCount("bedroom", "root-001")).thenReturn(1L);
        when(contentDaoService.getChildrenPaged(org.mockito.ArgumentMatchers.eq("bedroom"),
                org.mockito.ArgumentMatchers.eq("root-001"), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(contentDaoService.lastUnreadableChildCount()).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.retryRepositoryCloudSyncLineage("bedroom",
                        "doc-hidden=cloud://drive/f1|hash1"),
                "the retry reconciled absence from a walk that could not see everything");
        org.mockito.Mockito.verify(cloudSyncLineageService, org.mockito.Mockito.never())
                .reconcileRemovedCloudSyncLineage(any(), any(), any());
    }
}
