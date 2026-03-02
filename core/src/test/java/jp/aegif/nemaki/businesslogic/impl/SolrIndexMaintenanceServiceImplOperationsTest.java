package jp.aegif.nemaki.businesslogic.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.client.solrj.util.ClientUtils;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Document Operations
 * 
 * Tests the reindexDocument, deleteFromIndex, clearIndex, and optimizeIndex methods
 * with proper Mockito mocking of dependencies.
 */
@ExtendWith(MockitoExtension.class)
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
    
    @AfterEach
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testReindexDocumentSuccess() throws Exception {
        String objectId = "doc-123";
        Document document = mock(Document.class);

        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);

        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);

        assertTrue(result, "reindexDocument should return true on success");
        verify(solrUtil).indexDocument(TEST_REPO_ID, document, true);
    }

    @Test
    public void testReindexDocumentNotFound() {
        String objectId = "non-existent-doc";
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(null);
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertFalse(result, "reindexDocument should return false when document not found");
        verify(solrUtil, never()).indexDocument(anyString(), any(Content.class), anyBoolean());
    }
    
    @Test
    public void testReindexDocumentWithException() throws Exception {
        String objectId = "doc-123";
        Document document = mock(Document.class);
        
        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);
        doThrow(new RuntimeException("Index failed")).when(solrUtil).indexDocument(eq(TEST_REPO_ID), eq(document), eq(true));
        
        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);
        
        assertFalse(result, "reindexDocument should return false on exception");
    }
    
    @Test
    public void testDeleteFromIndexSuccess() throws Exception {
        String objectId = "doc-123";

        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);

        assertTrue(result, "deleteFromIndex should return true on success");
        verify(solrUtil).deleteDocument(TEST_REPO_ID, objectId, true);
    }
    
    @Test
    public void testDeleteFromIndexWithException() throws Exception {
        String objectId = "doc-123";
        doThrow(new RuntimeException("Delete failed")).when(solrUtil).deleteDocument(eq(TEST_REPO_ID), eq(objectId), eq(true));
        
        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);
        
        assertFalse(result, "deleteFromIndex should return false on exception");
    }
    
    @Test
    public void testClearIndexSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertTrue(result, "clearIndex should return true on success");
        verify(solrClient).deleteByQuery("repository_id:" + ClientUtils.escapeQueryChars(TEST_REPO_ID));
        verify(solrClient).commit();
        // BTL-004: SolrClient is now shared and lifecycle-managed — no per-call close()
    }
    
    @Test
    public void testClearIndexWithNullSolrClient() {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse(result, "clearIndex should return false when Solr client is null");
    }
    
    @Test
    public void testClearIndexWithException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenThrow(new RuntimeException("Clear failed"));
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse(result, "clearIndex should return false on exception");
    }
    
    @Test
    public void testClearIndexWithNonZeroStatus() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(500);
        
        boolean result = service.clearIndex(TEST_REPO_ID);
        
        assertFalse(result, "clearIndex should return false when status is non-zero");
    }
    
    @Test
    public void testOptimizeIndexSuccess() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertTrue(result, "optimizeIndex should return true on success");
        verify(solrClient).optimize();
        // BTL-004: SolrClient is now shared and lifecycle-managed — no per-call close()
    }
    
    @Test
    public void testOptimizeIndexWithNullSolrClient() {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse(result, "optimizeIndex should return false when Solr client is null");
    }
    
    @Test
    public void testOptimizeIndexWithException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenThrow(new RuntimeException("Optimize failed"));
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse(result, "optimizeIndex should return false on exception");
    }
    
    @Test
    public void testOptimizeIndexWithNonZeroStatus() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.optimize()).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(500);
        
        boolean result = service.optimizeIndex(TEST_REPO_ID);
        
        assertFalse(result, "optimizeIndex should return false when status is non-zero");
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

        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(content);

        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);

        assertTrue(result);
        verify(solrUtil).indexDocument(TEST_REPO_ID, content, true);
    }
    
    @Test
    public void testDeleteFromIndexWithEmptyObjectId() throws Exception {
        String objectId = "";

        boolean result = service.deleteFromIndex(TEST_REPO_ID, objectId);

        assertTrue(result);
    }
    
    @Test
    public void testClearIndexVerifiesRepositoryFilter() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.deleteByQuery(anyString())).thenReturn(updateResponse);
        when(updateResponse.getStatus()).thenReturn(0);
        
        service.clearIndex(TEST_REPO_ID);
        
        verify(solrClient).deleteByQuery("repository_id:" + ClientUtils.escapeQueryChars(TEST_REPO_ID));
    }
    
    @Test
    public void testOperationsWithSpecialCharactersInIds() throws Exception {
        String objectId = "doc-with-special:chars/and\\slashes";
        Document document = mock(Document.class);

        when(contentService.getContent(TEST_REPO_ID, objectId)).thenReturn(document);

        boolean result = service.reindexDocument(TEST_REPO_ID, objectId);

        assertTrue(result);
    }

    // BTL-004: testClearIndexClosesClientOnSuccess and testOptimizeIndexClosesClientOnSuccess
    // removed — SolrClient is now shared and lifecycle-managed, no per-call close().
}
