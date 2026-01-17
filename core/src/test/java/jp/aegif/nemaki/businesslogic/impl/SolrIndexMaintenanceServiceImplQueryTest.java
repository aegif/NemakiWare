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
    
    // ========== Query Syntax Validation Tests ==========
    
    @Test
    public void testExecuteSolrQueryWithUnbalancedParentheses() {
        // Query with unclosed parenthesis should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:(test", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unbalanced parentheses", 
            result.getErrorMessage().contains("parentheses") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithExtraClosingParenthesis() {
        // Query with extra closing parenthesis should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:test)", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unbalanced parentheses", 
            result.getErrorMessage().contains("parentheses") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithUnbalancedBrackets() {
        // Query with unclosed bracket should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "date:[2020-01-01 TO *", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unbalanced brackets", 
            result.getErrorMessage().contains("bracket") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithExtraClosingBracket() {
        // Query with extra closing bracket should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "date:2020-01-01]", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unbalanced brackets", 
            result.getErrorMessage().contains("bracket") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithUnclosedQuote() {
        // Query with unclosed quote should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unclosed quote", 
            result.getErrorMessage().contains("quote") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithBalancedParentheses() throws Exception {
        // Query with balanced parentheses should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:(test OR demo)", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithBalancedBrackets() throws Exception {
        // Query with balanced brackets should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "date:[2020-01-01 TO 2021-01-01]", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithQuotedString() throws Exception {
        // Query with properly quoted string should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test document\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithNestedParentheses() throws Exception {
        // Query with nested parentheses should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "(name:(test OR demo) AND type:document)", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithParenthesesInsideQuotes() throws Exception {
        // Parentheses inside quotes should not affect balance check
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test (with parens)\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithComplexValidQuery() throws Exception {
        // Complex but valid query should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(
            TEST_REPO_ID, 
            "(name:\"test doc\" OR title:demo) AND date:[2020-01-01 TO *] AND type:(document OR folder)", 
            0, 10, null, null
        );
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithMultipleUnbalancedParentheses() {
        // Query with multiple unclosed parentheses should return validation error
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:((test", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull(result.getErrorMessage());
        assertTrue("Error should mention unbalanced parentheses", 
            result.getErrorMessage().contains("parentheses") || result.getErrorMessage().contains("syntax"));
    }
    
    @Test
    public void testExecuteSolrQueryWithEscapedQuote() throws Exception {
        // Query with escaped quote should succeed
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test \\\"quoted\\\" doc\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull(result.getErrorMessage());
    }
    
    // Tests for improved escape detection with consecutive backslashes
    
    @Test
    public void testExecuteSolrQueryWithDoubleBackslashBeforeQuote() {
        // Query with \\" - the backslash is escaped, so the quote is NOT escaped (unclosed quote)
        // Two backslashes (even number) means the quote is not escaped
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test\\\\\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull("Double backslash before quote should leave quote unescaped (unclosed)", 
            result.getErrorMessage());
        assertTrue("Error should mention unclosed quote", 
            result.getErrorMessage().contains("quote") || result.getErrorMessage().contains("Unclosed"));
    }
    
    @Test
    public void testExecuteSolrQueryWithTripleBackslashBeforeQuote() throws Exception {
        // Query with \\\" - odd number of backslashes means quote IS escaped (valid)
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        // name:"test\\\" doc" - the \\\" is an escaped backslash followed by escaped quote
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test\\\\\\\" doc\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull("Triple backslash before quote should escape the quote (valid query)", result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithQuadrupleBackslashBeforeQuote() {
        // Query with \\\\" - four backslashes (even number) means quote is NOT escaped
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test\\\\\\\\\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNotNull("Quadruple backslash before quote should leave quote unescaped (unclosed)", 
            result.getErrorMessage());
        assertTrue("Error should mention unclosed quote", 
            result.getErrorMessage().contains("quote") || result.getErrorMessage().contains("Unclosed"));
    }
    
    @Test
    public void testExecuteSolrQueryWithSingleBackslashAtStart() throws Exception {
        // Query with escaped quote at the start of quoted string
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"\\\"test\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull("Single backslash should escape the quote (valid query)", result.getErrorMessage());
    }
    
    @Test
    public void testExecuteSolrQueryWithBackslashNotBeforeQuote() throws Exception {
        // Query with backslash not immediately before quote should not affect quote parsing
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(solrDocumentList);
        when(solrDocumentList.getNumFound()).thenReturn(5L);
        when(solrDocumentList.getStart()).thenReturn(0L);
        when(solrDocumentList.iterator()).thenReturn(new ArrayList<SolrDocument>().iterator());
        
        SolrQueryResult result = service.executeSolrQuery(TEST_REPO_ID, "name:\"test\\nvalue\"", 0, 10, null, null);
        
        assertNotNull(result);
        assertNull("Backslash not before quote should not affect parsing", result.getErrorMessage());
    }
}
