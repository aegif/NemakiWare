package jp.aegif.nemaki.businesslogic.impl;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Reindex Operations
 * 
 * Tests the startFullReindex, startFolderReindex, and cancelReindex methods
 * with proper Mockito mocking of dependencies.
 */
@RunWith(MockitoJUnitRunner.class)
public class SolrIndexMaintenanceServiceImplReindexTest {
    
    private static final String TEST_REPO_ID = "test-repo";
    private static final String ROOT_FOLDER_ID = "root-folder-id";
    
    @Mock
    private ContentService contentService;
    
    @Mock
    private SolrUtil solrUtil;
    
    @Mock
    private RepositoryInfoMap repositoryInfoMap;
    
    @Mock
    private RepositoryInfo repositoryInfo;
    
    @Mock
    private Folder rootFolder;
    
    @Mock
    private Folder subFolder;
    
    @Mock
    private Document document;
    
    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;
    
    @Before
    public void setUp() {
        when(repositoryInfoMap.getSingleRepositoryInfo(TEST_REPO_ID)).thenReturn(repositoryInfo);
        when(repositoryInfo.getRootFolderId()).thenReturn(ROOT_FOLDER_ID);
    }
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testStartFullReindexReturnsTrue() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        
        assertTrue("startFullReindex should return true", started);
    }
    
    @Test
    public void testStartFullReindexSetsRunningStatus() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        service.startFullReindex(TEST_REPO_ID);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("running", status.getStatus());
    }
    
    @Test
    public void testStartFullReindexPreventsDoubleStart() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean first = service.startFullReindex(TEST_REPO_ID);
        boolean second = service.startFullReindex(TEST_REPO_ID);
        
        assertTrue("First start should succeed", first);
        assertFalse("Second start should fail while running", second);
    }
    
    @Test
    public void testStartFullReindexWithEmptyRepository() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
        assertEquals(0, status.getTotalDocuments());
    }
    
    @Test
    public void testStartFullReindexWithDocuments() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");
        
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");
        
        List<Content> children = Arrays.asList(doc1, doc2);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt())).thenReturn(2);
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
        assertEquals(2, status.getTotalDocuments());
    }
    
    @Test
    public void testStartFolderReindexReturnsTrue() {
        String folderId = "folder-123";
        Folder folder = mock(Folder.class);
        when(folder.getId()).thenReturn(folderId);
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        
        assertTrue("startFolderReindex should return true", started);
    }
    
    @Test
    public void testStartFolderReindexRecursive() throws Exception {
        String folderId = "folder-123";
        Folder folder = mock(Folder.class);
        when(folder.getId()).thenReturn(folderId);
        when(folder.getName()).thenReturn("TestFolder");
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testStartFolderReindexNonRecursive() throws Exception {
        String folderId = "folder-456";
        Folder folder = mock(Folder.class);
        when(folder.getId()).thenReturn(folderId);
        when(folder.getName()).thenReturn("TestFolder");
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, false);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testStartFolderReindexWithNonExistentFolder() throws Exception {
        String folderId = "non-existent-folder";
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(null);
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("error", status.getStatus());
        assertTrue(status.getErrorMessage().contains("Folder not found"));
    }
    
    @Test
    public void testCancelReindexWhenIdle() {
        boolean cancelled = service.cancelReindex(TEST_REPO_ID);
        
        assertFalse("Cancel should return false when no reindex is running", cancelled);
    }
    
    @Test
    public void testCancelReindexWhenRunning() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        service.startFullReindex(TEST_REPO_ID);
        
        boolean cancelled = service.cancelReindex(TEST_REPO_ID);
        
        assertTrue("Cancel should return true when reindex is running", cancelled);
    }
    
    @Test
    public void testCancelReindexForNonExistentRepo() {
        boolean cancelled = service.cancelReindex("non-existent-repo");
        
        assertFalse("Cancel should return false for non-existent repo", cancelled);
    }
    
    @Test
    public void testReindexStatusAfterCancel() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> manyDocs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            manyDocs.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(manyDocs);
        
        service.startFullReindex(TEST_REPO_ID);
        service.cancelReindex(TEST_REPO_ID);
        
        awaitReindexCompletion(TEST_REPO_ID, 10);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertTrue("Status should be cancelled or completed", 
            "cancelled".equals(status.getStatus()) || "completed".equals(status.getStatus()));
    }
    
    @Test
    public void testStartFullReindexWithRootFolderNotFound() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(null);
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("error", status.getStatus());
        assertEquals("Root folder not found", status.getErrorMessage());
    }
    
    @Test
    public void testStartFullReindexWithSubfolders() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");
        
        Folder subFolder = mock(Folder.class);
        when(subFolder.getId()).thenReturn("sub-folder-1");
        when(subFolder.getName()).thenReturn("SubFolder");
        
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        
        List<Content> rootChildren = Arrays.asList(subFolder, doc1);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootChildren);
        
        when(contentService.getFolder(TEST_REPO_ID, "sub-folder-1")).thenReturn(subFolder);
        when(contentService.getChildren(TEST_REPO_ID, "sub-folder-1")).thenReturn(new ArrayList<>());
        
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt())).thenReturn(1);
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testMultipleRepositoriesCanReindexSimultaneously() {
        String repo1 = "repo-1";
        String repo2 = "repo-2";
        
        RepositoryInfo info1 = mock(RepositoryInfo.class);
        RepositoryInfo info2 = mock(RepositoryInfo.class);
        when(repositoryInfoMap.getSingleRepositoryInfo(repo1)).thenReturn(info1);
        when(repositoryInfoMap.getSingleRepositoryInfo(repo2)).thenReturn(info2);
        when(info1.getRootFolderId()).thenReturn("root-1");
        when(info2.getRootFolderId()).thenReturn("root-2");
        
        Folder root1 = mock(Folder.class);
        Folder root2 = mock(Folder.class);
        when(root1.getId()).thenReturn("root-1");
        when(root2.getId()).thenReturn("root-2");
        when(contentService.getFolder(repo1, "root-1")).thenReturn(root1);
        when(contentService.getFolder(repo2, "root-2")).thenReturn(root2);
        when(contentService.getChildren(eq(repo1), anyString())).thenReturn(new ArrayList<>());
        when(contentService.getChildren(eq(repo2), anyString())).thenReturn(new ArrayList<>());
        
        boolean started1 = service.startFullReindex(repo1);
        boolean started2 = service.startFullReindex(repo2);
        
        assertTrue("First repo should start", started1);
        assertTrue("Second repo should also start", started2);
    }
    
    private void awaitReindexCompletion(String repositoryId, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < deadline) {
            ReindexStatus status = service.getReindexStatus(repositoryId);
            if (!"running".equals(status.getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
