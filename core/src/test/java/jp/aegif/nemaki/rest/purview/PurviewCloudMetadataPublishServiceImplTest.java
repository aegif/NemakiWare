package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private PurviewCloudMetadataPublishServiceImpl service;

    @BeforeEach
    public void setUp() {
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        contentDaoService = mock(ContentDaoService.class);
        documentPublishService = mock(PurviewDocumentPublishService.class);
        when(documentPublishService.upsertContents(any(), any())).thenAnswer(invocation -> invocation.getArgument(1, List.class).size());

        service = new PurviewCloudMetadataPublishServiceImpl(
                repositoryInfoMap,
                contentDaoService,
                documentPublishService);
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
        assertEquals(1, result.getPublishedCount());
        assertEquals(1, result.getReconciledCount());
        verify(documentPublishService).upsertContents("bedroom", List.of(currentDocument));
        verify(documentPublishService).upsertContents("bedroom", List.of(clearedDocument));
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
        verify(contentDaoService, never()).getContentFresh(eq("bedroom"), eq("doc-001"));
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
