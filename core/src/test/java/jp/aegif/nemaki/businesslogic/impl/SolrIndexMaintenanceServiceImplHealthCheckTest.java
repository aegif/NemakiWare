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
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Document;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Health Check Operations
 * 
 * Tests the checkIndexHealth method with proper Mockito mocking of dependencies.
 */
@RunWith(MockitoJUnitRunner.class)
public class SolrIndexMaintenanceServiceImplHealthCheckTest {
    
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
    private SolrClient solrClient;
    
    @Mock
    private QueryResponse queryResponse;
    
    @Mock
    private SolrDocumentList solrDocumentList;
    
    @Mock
    private Folder rootFolder;
    
    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;
    
    @Before
    public void setUp() {
        when(repositoryInfoMap.get(TEST_REPO_ID)).thenReturn(repositoryInfo);
        when(repositoryInfo.getRootFolderId()).thenReturn(ROOT_FOLDER_ID);
    }
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testCheckIndexHealthWhenHealthy() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(100L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertEquals(TEST_REPO_ID, health.getRepositoryId());
        assertEquals(100, health.getSolrDocumentCount());
        assertEquals(100, health.getCouchDbDocumentCount());
        assertTrue(health.isHealthy());
        assertEquals(0, health.getMissingInSolr());
        assertEquals(0, health.getOrphanedInSolr());
    }
    
    @Test
    public void testCheckIndexHealthWithMissingDocuments() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(80L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertEquals(80, health.getSolrDocumentCount());
        assertEquals(100, health.getCouchDbDocumentCount());
        assertEquals(20, health.getMissingInSolr());
        assertEquals(0, health.getOrphanedInSolr());
    }
    
    @Test
    public void testCheckIndexHealthWithOrphanedDocuments() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(120L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertEquals(120, health.getSolrDocumentCount());
        assertEquals(100, health.getCouchDbDocumentCount());
        assertEquals(0, health.getMissingInSolr());
        assertEquals(20, health.getOrphanedInSolr());
    }
    
    @Test
    public void testCheckIndexHealthWithNullSolrClient() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertEquals(0, health.getSolrDocumentCount());
    }
    
    @Test
    public void testCheckIndexHealthWithSolrException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenThrow(new RuntimeException("Solr connection failed"));
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertTrue(health.getMessage().contains("Error checking health"));
    }
    
    @Test
    public void testCheckIndexHealthWithEmptyRepository() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(0L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertTrue(health.isHealthy());
        assertEquals(0, health.getSolrDocumentCount());
        assertEquals(0, health.getCouchDbDocumentCount());
    }
    
    @Test
    public void testCheckIndexHealthWithNullRootFolder() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(50L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(null);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertEquals(50, health.getSolrDocumentCount());
        assertEquals(0, health.getCouchDbDocumentCount());
    }
    
    @Test
    public void testCheckIndexHealthSetsCheckTime() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(0L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());
        
        long beforeCheck = System.currentTimeMillis();
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        long afterCheck = System.currentTimeMillis();
        
        assertTrue(health.getCheckTime() >= beforeCheck);
        assertTrue(health.getCheckTime() <= afterCheck);
    }
    
    @Test
    public void testCheckIndexHealthMessageForHealthyIndex() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(50L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertTrue(health.getMessage().contains("healthy"));
    }
    
    @Test
    public void testCheckIndexHealthMessageForUnhealthyIndex() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(30L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertTrue(health.getMessage().contains("mismatch"));
    }
    
    @Test
    public void testCheckIndexHealthWithNestedFolders() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        Folder subFolder = mock(Folder.class);
        when(subFolder.getId()).thenReturn("sub-folder-1");
        
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");
        
        List<Content> rootChildren = Arrays.asList(subFolder, doc1, doc2);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootChildren);
        
        Document doc3 = mock(Document.class);
        when(doc3.getId()).thenReturn("doc-3");
        Document doc4 = mock(Document.class);
        when(doc4.getId()).thenReturn("doc-4");
        
        List<Content> subChildren = Arrays.asList(doc3, doc4);
        when(contentService.getChildren(TEST_REPO_ID, "sub-folder-1")).thenReturn(subChildren);
        
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        
        assertNotNull(health);
        assertEquals(5, health.getSolrDocumentCount());
        assertEquals(5, health.getCouchDbDocumentCount());
        assertTrue(health.isHealthy());
    }
}
