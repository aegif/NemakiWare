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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Status and Data Classes
 * 
 * Tests the ReindexStatus, IndexHealthStatus, and SolrQueryResult data classes
 * and their field accessors.
 */
@RunWith(MockitoJUnitRunner.class)
public class SolrIndexMaintenanceServiceImplStatusTest {
    
    @Mock
    private ContentService contentService;
    
    @Mock
    private SolrUtil solrUtil;
    
    @Mock
    private RepositoryInfoMap repositoryInfoMap;
    
    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    @Test
    public void testReindexStatusInitialization() {
        ReindexStatus status = service.getReindexStatus("test-repo");
        
        assertNotNull("Status should not be null", status);
        assertEquals("Repository ID should match", "test-repo", status.getRepositoryId());
        assertEquals("Initial status should be idle", "idle", status.getStatus());
        assertEquals("Initial total documents should be 0", 0, status.getTotalDocuments());
        assertEquals("Initial indexed count should be 0", 0, status.getIndexedCount());
        assertEquals("Initial error count should be 0", 0, status.getErrorCount());
    }
    
    @Test
    public void testReindexStatusFields() {
        ReindexStatus status = service.getReindexStatus("test-repo");
        
        assertNotNull("Repository ID should not be null", status.getRepositoryId());
        assertNotNull("Status should not be null", status.getStatus());
        assertTrue("Start time should be >= 0", status.getStartTime() >= 0);
        assertTrue("End time should be >= 0", status.getEndTime() >= 0);
        
        String currentFolder = status.getCurrentFolder();
        String errorMessage = status.getErrorMessage();
    }
    
    @Test
    public void testReindexStatusSettersAndGetters() {
        ReindexStatus status = new ReindexStatus();
        
        status.setRepositoryId("repo-1");
        status.setStatus("running");
        status.setTotalDocuments(100);
        status.setIndexedCount(50);
        status.setErrorCount(5);
        status.setStartTime(1000L);
        status.setEndTime(2000L);
        status.setCurrentFolder("folder-1");
        status.setErrorMessage("test error");
        status.setErrors(Arrays.asList("error1", "error2"));
        
        assertEquals("repo-1", status.getRepositoryId());
        assertEquals("running", status.getStatus());
        assertEquals(100, status.getTotalDocuments());
        assertEquals(50, status.getIndexedCount());
        assertEquals(5, status.getErrorCount());
        assertEquals(1000L, status.getStartTime());
        assertEquals(2000L, status.getEndTime());
        assertEquals("folder-1", status.getCurrentFolder());
        assertEquals("test error", status.getErrorMessage());
        assertEquals(2, status.getErrors().size());
    }
    
    @Test
    public void testIndexHealthStatusFields() {
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        
        healthStatus.setRepositoryId("test-repo");
        healthStatus.setSolrDocumentCount(100);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(0);
        healthStatus.setOrphanedInSolr(0);
        healthStatus.setHealthy(true);
        healthStatus.setMessage("Index is healthy");
        healthStatus.setCheckTime(System.currentTimeMillis());
        
        assertEquals("test-repo", healthStatus.getRepositoryId());
        assertEquals(100, healthStatus.getSolrDocumentCount());
        assertEquals(100, healthStatus.getCouchDbDocumentCount());
        assertEquals(0, healthStatus.getMissingInSolr());
        assertEquals(0, healthStatus.getOrphanedInSolr());
        assertTrue(healthStatus.isHealthy());
        assertEquals("Index is healthy", healthStatus.getMessage());
        assertTrue(healthStatus.getCheckTime() > 0);
    }
    
    @Test
    public void testIndexHealthStatusUnhealthy() {
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        
        healthStatus.setRepositoryId("test-repo");
        healthStatus.setSolrDocumentCount(80);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(20);
        healthStatus.setOrphanedInSolr(0);
        healthStatus.setHealthy(false);
        healthStatus.setMessage("20 documents missing in Solr");
        
        assertFalse("Health status should be unhealthy", healthStatus.isHealthy());
        assertEquals(20, healthStatus.getMissingInSolr());
        assertEquals(0, healthStatus.getOrphanedInSolr());
    }
    
    @Test
    public void testIndexHealthStatusOrphaned() {
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        
        healthStatus.setSolrDocumentCount(120);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(0);
        healthStatus.setOrphanedInSolr(20);
        healthStatus.setHealthy(false);
        
        assertFalse("Health status should be unhealthy", healthStatus.isHealthy());
        assertEquals(0, healthStatus.getMissingInSolr());
        assertEquals(20, healthStatus.getOrphanedInSolr());
    }
    
    @Test
    public void testSolrQueryResultFields() {
        SolrQueryResult result = new SolrQueryResult();
        
        result.setNumFound(100);
        result.setStart(0);
        result.setQueryTime(50);
        result.setRawResponse("{\"response\":{}}");
        result.setErrorMessage(null);
        
        assertEquals(100, result.getNumFound());
        assertEquals(0, result.getStart());
        assertEquals(50, result.getQueryTime());
        assertEquals("{\"response\":{}}", result.getRawResponse());
        assertNull(result.getErrorMessage());
    }
    
    @Test
    public void testSolrQueryResultWithDocs() {
        SolrQueryResult result = new SolrQueryResult();
        
        List<Map<String, Object>> docs = new ArrayList<>();
        Map<String, Object> doc1 = new HashMap<>();
        doc1.put("id", "doc-1");
        doc1.put("name", "Document 1");
        docs.add(doc1);
        
        Map<String, Object> doc2 = new HashMap<>();
        doc2.put("id", "doc-2");
        doc2.put("name", "Document 2");
        docs.add(doc2);
        
        result.setDocs(docs);
        result.setNumFound(2);
        
        assertEquals(2, result.getDocs().size());
        assertEquals("doc-1", result.getDocs().get(0).get("id"));
        assertEquals("Document 2", result.getDocs().get(1).get("name"));
    }
    
    @Test
    public void testSolrQueryResultWithError() {
        SolrQueryResult result = new SolrQueryResult();
        
        result.setErrorMessage("Connection refused");
        result.setNumFound(0);
        result.setDocs(new ArrayList<>());
        
        assertNotNull("Error message should be set", result.getErrorMessage());
        assertEquals("Connection refused", result.getErrorMessage());
        assertEquals(0, result.getNumFound());
    }
    
    @Test
    public void testSolrQueryResultPagination() {
        SolrQueryResult result = new SolrQueryResult();
        
        result.setNumFound(1000);
        result.setStart(100);
        
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", "doc-" + (100 + i));
            docs.add(doc);
        }
        result.setDocs(docs);
        
        assertEquals(1000, result.getNumFound());
        assertEquals(100, result.getStart());
        assertEquals(10, result.getDocs().size());
    }
    
    @Test
    public void testSolrQueryResultEmptyDocs() {
        SolrQueryResult result = new SolrQueryResult();
        
        result.setNumFound(0);
        result.setStart(0);
        result.setDocs(new ArrayList<>());
        
        assertEquals(0, result.getNumFound());
        assertNotNull(result.getDocs());
        assertTrue(result.getDocs().isEmpty());
    }
    
    @Test
    public void testMultipleRepositoryStatusTracking() {
        ReindexStatus status1 = service.getReindexStatus("repo-1");
        ReindexStatus status2 = service.getReindexStatus("repo-2");
        ReindexStatus status3 = service.getReindexStatus("repo-3");
        
        assertEquals("repo-1", status1.getRepositoryId());
        assertEquals("repo-2", status2.getRepositoryId());
        assertEquals("repo-3", status3.getRepositoryId());
        
        assertEquals("idle", status1.getStatus());
        assertEquals("idle", status2.getStatus());
        assertEquals("idle", status3.getStatus());
    }
    
    @Test
    public void testGetReindexStatusConsistency() {
        ReindexStatus status1 = service.getReindexStatus("consistent-repo");
        ReindexStatus status2 = service.getReindexStatus("consistent-repo");
        
        assertEquals(status1.getRepositoryId(), status2.getRepositoryId());
        assertEquals(status1.getStatus(), status2.getStatus());
    }
    
    @Test
    public void testEmptyRepositoryId() {
        ReindexStatus status = service.getReindexStatus("");
        
        assertNotNull("Status should not be null for empty repo ID", status);
        assertEquals("", status.getRepositoryId());
        assertEquals("idle", status.getStatus());
    }
    
    @Test
    public void testSpecialCharactersInRepositoryId() {
        String[] specialRepoIds = {
            "repo-with-dash",
            "repo_with_underscore",
            "repo.with.dots",
            "repo:with:colons",
            "repo/with/slashes"
        };
        
        for (String repoId : specialRepoIds) {
            ReindexStatus status = service.getReindexStatus(repoId);
            assertNotNull("Status should not be null for repo: " + repoId, status);
            assertEquals(repoId, status.getRepositoryId());
        }
    }
    
    @Test
    public void testReindexStatusErrorsList() {
        ReindexStatus status = new ReindexStatus();
        
        List<String> errors = new ArrayList<>();
        errors.add("Error 1: Document not found");
        errors.add("Error 2: Index failed");
        errors.add("Error 3: Connection timeout");
        
        status.setErrors(errors);
        
        assertNotNull(status.getErrors());
        assertEquals(3, status.getErrors().size());
        assertTrue(status.getErrors().contains("Error 1: Document not found"));
    }
    
    @Test
    public void testLargeErrorsList() {
        ReindexStatus status = new ReindexStatus();
        
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            errors.add("Error " + i + ": Test error message");
        }
        
        status.setErrors(errors);
        
        assertEquals(150, status.getErrors().size());
    }
}
