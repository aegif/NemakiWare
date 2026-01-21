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
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Document Operations
 * 
 * Tests the reindexDocument, deleteFromIndex, clearIndex, and optimizeIndex methods
 * with proper Mockito mocking of dependencies.
 */
@RunWith(MockitoJUnitRunner.class)
public class SolrIndexMaintenanceServiceImplOperationsTest {
    
    private static final String TEST_REPO_ID = "test-repo";
    
    @Mock
    private ContentService contentService;
    
    @Mock
    private SolrUtil solrUtil;
    
    @Mock
    private RepositoryInfoMap repositoryInfoMap;
    
    @Mock
    private SolrClient solrClient;
    
    @Mock
    private UpdateResponse updateResponse;
    
    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testReindexDocumentSuccess() throws Exception {
        String objectId = "doc-123";
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(objectId);
        
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);
        doNothing().when(solrUtil).indexDocument(eq(TEST_REPO_ID), eq(document));
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertTrue("reindexDocument should return true on success", result);
        verify(solrUtil).indexDocument(TEST_REPO_ID, document);
    }
    
    @Test
    public void testReindexDocumentNotFound() {
        String objectId = "non-existent-doc";
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(null);
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertFalse("reindexDocument should return false when document not found", result);
        verify(solrUtil, never()).indexDocument(anyString(), any(Content.class));
    }
    
    @Test
    public void testReindexDocumentWithException() throws Exception {
        String objectId = "doc-123";
        Document document = mock(Document.class);
        
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);
        doThrow(new RuntimeException("Index failed")).when(solrUtil).indexDocument(eq(TEST_REPO_ID), eq(document));
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertFalse("reindexDocument should return false on exception", result);
    }
    
    @Test
    public void testDeleteFromIndexSuccess() throws Exception {
        String objectId = "doc-123";
        doNothing().when(solrUtil).deleteDocument(TEST_REPO_ID, objectId);
        
        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);
        
        assertTrue("deleteFromIndex should return true on success", result);
        verify(solrUtil).deleteDocument(TEST_REPO_ID, objectId);
    }
    
    @Test
    public void testDeleteFromIndexWithException() throws Exception {
        String objectId = "doc-123";
        doThrow(new RuntimeException("Delete failed")).when(solrUtil).deleteDocument(TEST_REPO_ID, objectId);
        
        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);
        
        assertFalse("deleteFromIndex should return false on exception", result);
    }
    
    @Test
    public void testClearIndexSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertTrue("clearIndex should return true on success", result);
        verify(solrClient).deleteByQuery("repository_id:" + TEST_REPO_ID);
        verify(solrClient).commit();
        verify(solrClient).close();
    }
    
    @Test
    public void testClearIndexWithNullSolrClient() {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse("clearIndex should return false when Solr client is null", result);
    }
    
    @Test
    public void testClearIndexWithException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenThrow(new RuntimeException("Clear failed"));
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse("clearIndex should return false on exception", result);
    }
    
    @Test
    public void testClearIndexWithNonZeroStatus() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(500);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse("clearIndex should return false when status is non-zero", result);
    }
    
    @Test
    public void testOptimizeIndexSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertTrue("optimizeIndex should return true on success", result);
        verify(solrClient).optimize();
        verify(solrClient).close();
    }
    
    @Test
    public void testOptimizeIndexWithNullSolrClient() {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse("optimizeIndex should return false when Solr client is null", result);
    }
    
    @Test
    public void testOptimizeIndexWithException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenThrow(new RuntimeException("Optimize failed"));
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse("optimizeIndex should return false on exception", result);
    }
    
    @Test
    public void testOptimizeIndexWithNonZeroStatus() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(500);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse("optimizeIndex should return false when status is non-zero", result);
    }
    
    @Test
    public void testServiceShutdown() {
        service.shutdown();
    }
    
    @Test
    public void testMultipleShutdownCalls() {
        service.shutdown();
        service.shutdown();
    }
    
    @Test
    public void testReindexDocumentWithDifferentContentTypes() throws Exception {
        String objectId = "content-123";
        Content content = mock(Content.class);
        when(content.getId()).thenReturn(objectId);
        
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(content);
        doNothing().when(solrUtil).indexDocument(eq(TEST_REPO_ID), eq(content));
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertTrue(result);
        verify(solrUtil).indexDocument(TEST_REPO_ID, content);
    }
    
    @Test
    public void testDeleteFromIndexWithEmptyObjectId() throws Exception {
        String objectId = "";
        doNothing().when(solrUtil).deleteDocument(TEST_REPO_ID, objectId);
        
        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);
        
        assertTrue(result);
    }
    
    @Test
    public void testClearIndexVerifiesRepositoryFilter() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        service.clearIndex(TEST_REPO_ID);
        
        verify(solrClient).deleteByQuery("repository_id:" + TEST_REPO_ID);
    }
    
    @Test
    public void testOperationsWithSpecialCharactersInIds() throws Exception {
        String objectId = "doc-with-special:chars/and\\slashes";
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(objectId);
        
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);
        doNothing().when(solrUtil).indexDocument(eq(TEST_REPO_ID), eq(document));
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertTrue(result);
    }
    
    @Test
    public void testClearIndexClosesClientOnSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        service.clearIndex(TEST_REPO_ID);
        
        verify(solrClient).close();
    }
    
    @Test
    public void testOptimizeIndexClosesClientOnSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        service.optimizeIndex(TEST_REPO_ID);
        
        verify(solrClient).close();
    }
}
