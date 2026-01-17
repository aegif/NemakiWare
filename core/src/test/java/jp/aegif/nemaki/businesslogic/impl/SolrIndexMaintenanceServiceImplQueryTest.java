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
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Query Operations
 * 
 * Tests the executeSolrQuery method with proper Mockito mocking of dependencies.
 */
@RunWith(MockitoJUnitRunner.class)
public class SolrIndexMaintenanceServiceImplQueryTest {
    
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
    private QueryResponse queryResponse;
    
    @Mock
    private SolrDocumentList solrDocumentList;
    
    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testExecuteSolrQueryWithNullSolrClient() {
        when(solrUtil.getSolrClient()).thenReturn(null);
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertEquals("Solr client is not available", result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryBasic() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
        assertEquals(5, result.getNumFound());
        assertEquals(0, result.getStart());
    }
    
    @Test
    public void testExecuteSolrQueryWithSort() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, "created desc", null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithFields() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, "id,name,created");
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithAllParameters() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(100L);
        when(solrDocumentList.getStart()).thenReturn(20L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(
            TEST_REPO_ID, 
            "name:test", 
            20, 
            10, 
            "created desc", 
            "id,name,created"
        );
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
        assertEquals(100, result.getNumFound());
        assertEquals(20, result.getStart());
    }
    
    @Test
    public void testExecuteSolrQueryWithEmptyQuery() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(50L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithNullQuery() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(50L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, null, 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithNegativeStart() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(50L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", -5, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithLargeRows() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5000L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10000, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithZeroRows() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(100L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 0, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithSolrException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new RuntimeException("Solr query failed"));
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Solr query failed"));
    }
    
    @Test
    public void testExecuteSolrQueryWithDocuments() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(2L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        
        SolrDocument doc1 = mock(SolrDocument.class);
        when(doc1.getFieldNames()).thenReturn(Arrays.asList("id", "name"));
        when(doc1.getFieldValue("id")).thenReturn("doc-1");
        when(doc1.getFieldValue("name")).thenReturn("Document 1");
        
        SolrDocument doc2 = mock(SolrDocument.class);
        when(doc2.getFieldNames()).thenReturn(Arrays.asList("id", "name"));
        when(doc2.getFieldValue("id")).thenReturn("doc-2");
        when(doc2.getFieldValue("name")).thenReturn("Document 2");
        
        List<SolrDocument> docs = Arrays.asList(doc1, doc2);
        when(solrDocumentList.iterator()).thenReturn(docs.iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
        assertEquals(2, result.getNumFound());
        assertEquals(2, result.getDocs().size());
        assertEquals("doc-1", result.getDocs().get(0).get("id"));
        assertEquals("Document 2", result.getDocs().get(1).get("name"));
    }
    
    @Test
    public void testExecuteSolrQuerySetsQueryTime() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(0L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, null);
        
        assertNotNull(result);
        assertTrue(result.getQueryTime() >= 0);
    }
    
    @Test
    public void testExecuteSolrQueryWithRepositoryIdFilter() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:test", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithExistingRepositoryIdInQuery() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(
            TEST_REPO_ID, 
            "repository_id:other-repo AND name:test", 
            0, 10, null, null
        );
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithWhitespaceSort() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, "  ", null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithWhitespaceFields() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, "  ");
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithFieldsContainingSpaces() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(10L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 0, 10, null, " id , name , created ");
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryPagination() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(1000L);
        when(solrDocumentList.getStart()).thenReturn(100L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "*:*", 100, 50, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
        assertEquals(1000, result.getNumFound());
        assertEquals(100, result.getStart());
    }
}
