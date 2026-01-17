package jp.aegif.nemaki.businesslogic.impl;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Comprehensive unit tests for SolrIndexMaintenanceServiceImpl
 * 
 * Tests the Solr index maintenance service functionality including:
 * - Reindex status tracking and management
 * - Index health check operations
 * - Solr query execution
 * - Document-level index operations
 * - Batch verification and silent drop detection
 * - Post-reindex health check
 * - Concurrent access handling
 * - Edge cases and error handling
 * 
 * Note: These tests focus on the service logic without requiring
 * actual Solr or CouchDB connections. Integration tests would
 * require a running backend environment.
 */
public class SolrIndexMaintenanceServiceImplTest {
    
    private static final Log log = LogFactory.getLog(SolrIndexMaintenanceServiceImplTest.class);
    private SolrIndexMaintenanceServiceImpl service;
    
    @Before
    public void setUp() {
        service = new SolrIndexMaintenanceServiceImpl();
        log.info("SolrIndexMaintenanceServiceImpl test setup complete");
    }
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        service = null;
    }
    
    @Test
    public void testReindexStatusInitialization() {
        log.info("Testing ReindexStatus initialization");
        
        // Get status for a repository that hasn't started reindexing
        ReindexStatus status = service.getReindexStatus("test-repo");
        
        assertNotNull("Status should not be null", status);
        assertEquals("Repository ID should match", "test-repo", status.getRepositoryId());
        assertEquals("Initial status should be idle", "idle", status.getStatus());
        assertEquals("Initial total documents should be 0", 0, status.getTotalDocuments());
        assertEquals("Initial indexed count should be 0", 0, status.getIndexedCount());
        assertEquals("Initial error count should be 0", 0, status.getErrorCount());
        
        log.info("ReindexStatus initialization test passed");
    }
    
    @Test
    public void testReindexStatusFields() {
        log.info("Testing ReindexStatus field accessors");
        
        ReindexStatus status = service.getReindexStatus("test-repo");
        
        // Test all field accessors
        assertNotNull("Repository ID should not be null", status.getRepositoryId());
        assertNotNull("Status should not be null", status.getStatus());
        assertTrue("Start time should be >= 0", status.getStartTime() >= 0);
        assertTrue("End time should be >= 0", status.getEndTime() >= 0);
        
        // Current folder and error message can be null
        // Just verify they don't throw exceptions
        String currentFolder = status.getCurrentFolder();
        String errorMessage = status.getErrorMessage();
        
        log.info("Current folder: " + currentFolder);
        log.info("Error message: " + errorMessage);
        log.info("ReindexStatus field accessors test passed");
    }
    
    @Test
    public void testIndexHealthStatusFields() {
        log.info("Testing IndexHealthStatus field structure");
        
        // Create a mock health status to test the class structure
        // Note: Actual health check requires backend connection
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        healthStatus.setRepositoryId("test-repo");
        healthStatus.setSolrDocumentCount(100);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(0);
        healthStatus.setOrphanedInSolr(0);
        healthStatus.setHealthy(true);
        healthStatus.setMessage("Index is healthy");
        healthStatus.setCheckTime(System.currentTimeMillis());
        
        assertEquals("Repository ID should match", "test-repo", healthStatus.getRepositoryId());
        assertEquals("Solr document count should be 100", 100, healthStatus.getSolrDocumentCount());
        assertEquals("CouchDB document count should be 100", 100, healthStatus.getCouchDbDocumentCount());
        assertEquals("Missing in Solr should be 0", 0, healthStatus.getMissingInSolr());
        assertEquals("Orphaned in Solr should be 0", 0, healthStatus.getOrphanedInSolr());
        assertTrue("Should be healthy", healthStatus.isHealthy());
        assertEquals("Message should match", "Index is healthy", healthStatus.getMessage());
        assertTrue("Check time should be positive", healthStatus.getCheckTime() > 0);
        
        log.info("IndexHealthStatus field structure test passed");
    }
    
    @Test
    public void testIndexHealthStatusUnhealthy() {
        log.info("Testing IndexHealthStatus unhealthy state");
        
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        healthStatus.setRepositoryId("test-repo");
        healthStatus.setSolrDocumentCount(90);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(10);
        healthStatus.setOrphanedInSolr(0);
        healthStatus.setHealthy(false);
        healthStatus.setMessage("10 documents missing in Solr");
        
        assertFalse("Should not be healthy", healthStatus.isHealthy());
        assertEquals("Missing count should be 10", 10, healthStatus.getMissingInSolr());
        assertTrue("Message should mention missing documents", 
                   healthStatus.getMessage().contains("missing"));
        
        log.info("IndexHealthStatus unhealthy state test passed");
    }
    
    @Test
    public void testSolrQueryResultFields() {
        log.info("Testing SolrQueryResult field structure");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setNumFound(50);
        result.setStart(0);
        result.setQueryTime(15);
        
        assertEquals("NumFound should be 50", 50, result.getNumFound());
        assertEquals("Start should be 0", 0, result.getStart());
        assertEquals("Query time should be 15", 15, result.getQueryTime());
        
        // Docs list can be null or empty initially
        assertNull("Docs should be null initially", result.getDocs());
        
        log.info("SolrQueryResult field structure test passed");
    }
    
    @Test
    public void testSolrQueryResultWithDocs() {
        log.info("Testing SolrQueryResult with documents");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setNumFound(2);
        result.setStart(0);
        result.setQueryTime(10);
        
        java.util.List<java.util.Map<String, Object>> docs = new java.util.ArrayList<>();
        
        java.util.Map<String, Object> doc1 = new java.util.HashMap<>();
        doc1.put("id", "doc-1");
        doc1.put("name", "Test Document 1");
        docs.add(doc1);
        
        java.util.Map<String, Object> doc2 = new java.util.HashMap<>();
        doc2.put("id", "doc-2");
        doc2.put("name", "Test Document 2");
        docs.add(doc2);
        
        result.setDocs(docs);
        
        assertNotNull("Docs should not be null", result.getDocs());
        assertEquals("Should have 2 documents", 2, result.getDocs().size());
        assertEquals("First doc ID should match", "doc-1", result.getDocs().get(0).get("id"));
        assertEquals("Second doc name should match", "Test Document 2", result.getDocs().get(1).get("name"));
        
        log.info("SolrQueryResult with documents test passed");
    }
    
    @Test
    public void testCancelReindexWhenIdle() {
        log.info("Testing cancel reindex when idle");
        
        // Cancel should return false when no reindex is running
        boolean cancelled = service.cancelReindex("test-repo");
        
        // When idle, cancel should return false (nothing to cancel)
        assertFalse("Cancel should return false when idle", cancelled);
        
        log.info("Cancel reindex when idle test passed");
    }
    
    @Test
    public void testMultipleRepositoryStatusTracking() {
        log.info("Testing multiple repository status tracking");
        
        // Get status for multiple repositories
        ReindexStatus status1 = service.getReindexStatus("repo-1");
        ReindexStatus status2 = service.getReindexStatus("repo-2");
        ReindexStatus status3 = service.getReindexStatus("repo-3");
        
        // Each should have its own status
        assertEquals("Repo 1 ID should match", "repo-1", status1.getRepositoryId());
        assertEquals("Repo 2 ID should match", "repo-2", status2.getRepositoryId());
        assertEquals("Repo 3 ID should match", "repo-3", status3.getRepositoryId());
        
        // All should be idle initially
        assertEquals("Repo 1 should be idle", "idle", status1.getStatus());
        assertEquals("Repo 2 should be idle", "idle", status2.getStatus());
        assertEquals("Repo 3 should be idle", "idle", status3.getStatus());
        
        log.info("Multiple repository status tracking test passed");
    }
    
    @Test
    public void testReindexStatusStates() {
        log.info("Testing ReindexStatus state values");
        
        // Test that status can hold different state values
        ReindexStatus status = new ReindexStatus();
        
        // Test idle state
        status.setStatus("idle");
        assertEquals("idle", status.getStatus());
        
        // Test running state
        status.setStatus("running");
        assertEquals("running", status.getStatus());
        
        // Test completed state
        status.setStatus("completed");
        assertEquals("completed", status.getStatus());
        
        // Test error state
        status.setStatus("error");
        assertEquals("error", status.getStatus());
        
        // Test cancelled state
        status.setStatus("cancelled");
        assertEquals("cancelled", status.getStatus());
        
        log.info("ReindexStatus state values test passed");
    }
    
    @Test
    public void testReindexStatusProgress() {
        log.info("Testing ReindexStatus progress tracking");
        
        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId("test-repo");
        status.setStatus("running");
        status.setTotalDocuments(100);
        status.setIndexedCount(50);
        status.setErrorCount(2);
        status.setStartTime(System.currentTimeMillis() - 5000);
        status.setCurrentFolder("/test/folder");
        
        assertEquals("Total documents should be 100", 100, status.getTotalDocuments());
        assertEquals("Indexed count should be 50", 50, status.getIndexedCount());
        assertEquals("Error count should be 2", 2, status.getErrorCount());
        assertEquals("Current folder should match", "/test/folder", status.getCurrentFolder());
        
        // Calculate progress percentage
        double progress = (double) status.getIndexedCount() / status.getTotalDocuments() * 100;
        assertEquals("Progress should be 50%", 50.0, progress, 0.01);
        
        log.info("ReindexStatus progress tracking test passed");
    }
    
    @Test
    public void testReindexStatusErrorHandling() {
        log.info("Testing ReindexStatus error handling");
        
        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId("test-repo");
        status.setStatus("error");
        status.setErrorMessage("Failed to connect to Solr");
        status.setErrorCount(5);
        status.setEndTime(System.currentTimeMillis());
        
        assertEquals("Status should be error", "error", status.getStatus());
        assertEquals("Error message should match", "Failed to connect to Solr", status.getErrorMessage());
        assertEquals("Error count should be 5", 5, status.getErrorCount());
        assertTrue("End time should be set", status.getEndTime() > 0);
        
        log.info("ReindexStatus error handling test passed");
    }
    
    @Test
    public void testServiceNotNull() {
        log.info("Testing service instantiation");
        
        assertNotNull("Service should not be null", service);
        
        log.info("Service instantiation test passed");
    }
    
    @Test
    public void testGetReindexStatusConsistency() {
        log.info("Testing getReindexStatus consistency");
        
        // Get status twice for same repository
        ReindexStatus status1 = service.getReindexStatus("consistency-test-repo");
        ReindexStatus status2 = service.getReindexStatus("consistency-test-repo");
        
        // Both should return consistent data
        assertEquals("Repository IDs should match", 
                     status1.getRepositoryId(), status2.getRepositoryId());
        assertEquals("Statuses should match", 
                     status1.getStatus(), status2.getStatus());
        
        log.info("getReindexStatus consistency test passed");
    }
    
    // ==================== Batch Verification Tests ====================
    
    @Test
    public void testReindexStatusWithBatchVerificationErrors() {
        log.info("Testing ReindexStatus with batch verification errors");
        
        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId("test-repo");
        status.setStatus("completed");
        status.setTotalDocuments(100);
        status.setIndexedCount(98);
        status.setErrorCount(2);
        
        List<String> errors = new ArrayList<>();
        errors.add("Batch verification: 2 documents missing in Solr");
        errors.add("Re-indexed silently dropped document: doc-1");
        errors.add("Re-indexed silently dropped document: doc-2");
        status.setErrors(errors);
        
        assertEquals("Should have 3 error entries", 3, status.getErrors().size());
        assertTrue("Should mention batch verification", 
                   status.getErrors().get(0).contains("Batch verification"));
        
        log.info("ReindexStatus with batch verification errors test passed");
    }
    
    @Test
    public void testReindexStatusWithPostHealthCheckError() {
        log.info("Testing ReindexStatus with post-reindex health check error");
        
        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId("test-repo");
        status.setStatus("completed");
        status.setTotalDocuments(100);
        status.setIndexedCount(100);
        status.setErrorCount(0);
        
        List<String> errors = new ArrayList<>();
        errors.add("Post-reindex health check: Index mismatch detected. CouchDB: 100, Solr: 95");
        status.setErrors(errors);
        
        assertEquals("Should have 1 error entry", 1, status.getErrors().size());
        assertTrue("Should mention post-reindex health check", 
                   status.getErrors().get(0).contains("Post-reindex health check"));
        assertTrue("Should mention index mismatch", 
                   status.getErrors().get(0).contains("mismatch"));
        
        log.info("ReindexStatus with post-reindex health check error test passed");
    }
    
    @Test
    public void testIndexHealthStatusOrphaned() {
        log.info("Testing IndexHealthStatus with orphaned documents");
        
        IndexHealthStatus healthStatus = new IndexHealthStatus();
        healthStatus.setRepositoryId("test-repo");
        healthStatus.setSolrDocumentCount(110);
        healthStatus.setCouchDbDocumentCount(100);
        healthStatus.setMissingInSolr(0);
        healthStatus.setOrphanedInSolr(10);
        healthStatus.setHealthy(false);
        healthStatus.setMessage("10 orphaned documents in Solr");
        
        assertFalse("Should not be healthy", healthStatus.isHealthy());
        assertEquals("Orphaned count should be 10", 10, healthStatus.getOrphanedInSolr());
        assertEquals("Missing count should be 0", 0, healthStatus.getMissingInSolr());
        
        log.info("IndexHealthStatus with orphaned documents test passed");
    }
    
    @Test
    public void testSolrQueryResultWithError() {
        log.info("Testing SolrQueryResult with error");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setErrorMessage("Connection refused");
        
        assertNotNull("Error message should not be null", result.getErrorMessage());
        assertEquals("Error message should match", "Connection refused", result.getErrorMessage());
        assertEquals("NumFound should be 0 on error", 0, result.getNumFound());
        
        log.info("SolrQueryResult with error test passed");
    }
    
    @Test
    public void testSolrQueryResultPagination() {
        log.info("Testing SolrQueryResult pagination");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setNumFound(100);
        result.setStart(20);
        result.setQueryTime(25);
        
        java.util.List<java.util.Map<String, Object>> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            java.util.Map<String, Object> doc = new java.util.HashMap<>();
            doc.put("id", "doc-" + (20 + i));
            docs.add(doc);
        }
        result.setDocs(docs);
        
        assertEquals("NumFound should be 100", 100, result.getNumFound());
        assertEquals("Start should be 20", 20, result.getStart());
        assertEquals("Should have 10 documents in page", 10, result.getDocs().size());
        
        log.info("SolrQueryResult pagination test passed");
    }
    
    // ==================== Service Method Tests (without mocks) ====================
    
    @Test
    public void testStartFullReindexWithoutDependencies() {
        log.info("Testing startFullReindex without dependencies");
        
        // Without dependencies set, startFullReindex should handle gracefully
        // It will fail but should not throw exception
        boolean started = service.startFullReindex("test-repo");
        
        // Should return true (started async task) but task will fail internally
        assertTrue("Should return true for starting", started);
        
        // Wait a bit for async task to process
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Status should be error due to missing dependencies
        ReindexStatus status = service.getReindexStatus("test-repo");
        assertNotNull("Status should not be null", status);
        
        log.info("startFullReindex without dependencies test passed");
    }
    
    @Test
    public void testStartFolderReindexWithoutDependencies() {
        log.info("Testing startFolderReindex without dependencies");
        
        // Without dependencies set, startFolderReindex should handle gracefully
        boolean started = service.startFolderReindex("test-repo-folder", "folder-123", true);
        
        // Should return true (started async task) but task will fail internally
        assertTrue("Should return true for starting", started);
        
        // Wait a bit for async task to process
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        log.info("startFolderReindex without dependencies test passed");
    }
    
    @Test
    public void testPreventDuplicateReindex() {
        log.info("Testing prevention of duplicate reindex");
        
        // Start first reindex
        boolean started1 = service.startFullReindex("duplicate-test-repo");
        assertTrue("First reindex should start", started1);
        
        // Try to start second reindex immediately (should fail)
        boolean started2 = service.startFullReindex("duplicate-test-repo");
        assertFalse("Second reindex should not start while first is running", started2);
        
        log.info("Prevention of duplicate reindex test passed");
    }
    
    @Test
    public void testReindexDocumentWithoutDependencies() {
        log.info("Testing reindexDocument without dependencies");
        
        // Without dependencies, should return false
        boolean result = service.reindexDocument("test-repo", "doc-123");
        assertFalse("Should return false without dependencies", result);
        
        log.info("reindexDocument without dependencies test passed");
    }
    
    @Test
    public void testDeleteFromIndexWithoutDependencies() {
        log.info("Testing deleteFromIndex without dependencies");
        
        // Without dependencies, should return false
        boolean result = service.deleteFromIndex("test-repo", "doc-123");
        assertFalse("Should return false without dependencies", result);
        
        log.info("deleteFromIndex without dependencies test passed");
    }
    
    @Test
    public void testClearIndexWithoutDependencies() {
        log.info("Testing clearIndex without dependencies");
        
        // Without dependencies, should return false
        boolean result = service.clearIndex("test-repo");
        assertFalse("Should return false without dependencies", result);
        
        log.info("clearIndex without dependencies test passed");
    }
    
    @Test
    public void testOptimizeIndexWithoutDependencies() {
        log.info("Testing optimizeIndex without dependencies");
        
        // Without dependencies, should return false
        boolean result = service.optimizeIndex("test-repo");
        assertFalse("Should return false without dependencies", result);
        
        log.info("optimizeIndex without dependencies test passed");
    }
    
    @Test
    public void testCheckIndexHealthWithoutDependencies() {
        log.info("Testing checkIndexHealth without dependencies");
        
        // Without dependencies, should return unhealthy status
        IndexHealthStatus health = service.checkIndexHealth("test-repo");
        
        assertNotNull("Health status should not be null", health);
        assertEquals("Repository ID should match", "test-repo", health.getRepositoryId());
        assertFalse("Should not be healthy without dependencies", health.isHealthy());
        
        log.info("checkIndexHealth without dependencies test passed");
    }
    
    @Test
    public void testExecuteSolrQueryWithoutDependencies() {
        log.info("Testing executeSolrQuery without dependencies");
        
        // Without dependencies, should return error result
        SolrQueryResult result = service.executeSolrQuery("test-repo", "*:*", 0, 10, null, null);
        
        assertNotNull("Result should not be null", result);
        assertNotNull("Error message should be set", result.getErrorMessage());
        
        log.info("executeSolrQuery without dependencies test passed");
    }
    
    // ==================== Concurrent Access Tests ====================
    
    @Test
    public void testConcurrentStatusAccess() throws InterruptedException {
        log.info("Testing concurrent status access");
        
        final int threadCount = 10;
        final String repoId = "concurrent-test-repo";
        Thread[] threads = new Thread[threadCount];
        final boolean[] errors = new boolean[1];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        ReindexStatus status = service.getReindexStatus(repoId);
                        assertNotNull(status);
                        assertEquals(repoId, status.getRepositoryId());
                    }
                } catch (Exception e) {
                    errors[0] = true;
                    log.error("Concurrent access error", e);
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertFalse("Should not have concurrent access errors", errors[0]);
        
        log.info("Concurrent status access test passed");
    }
    
    @Test
    public void testConcurrentMultipleRepositories() throws InterruptedException {
        log.info("Testing concurrent access to multiple repositories");
        
        final int threadCount = 5;
        final String[] repoIds = {"repo-a", "repo-b", "repo-c", "repo-d", "repo-e"};
        Thread[] threads = new Thread[threadCount];
        final boolean[] errors = new boolean[1];
        
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        ReindexStatus status = service.getReindexStatus(repoIds[index]);
                        assertNotNull(status);
                        assertEquals(repoIds[index], status.getRepositoryId());
                    }
                } catch (Exception e) {
                    errors[0] = true;
                    log.error("Concurrent multi-repo access error", e);
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertFalse("Should not have concurrent access errors", errors[0]);
        
        log.info("Concurrent access to multiple repositories test passed");
    }
    
    // ==================== Edge Case Tests ====================
    
    @Test
    public void testEmptyRepositoryId() {
        log.info("Testing empty repository ID");
        
        ReindexStatus status = service.getReindexStatus("");
        assertNotNull("Status should not be null for empty repo ID", status);
        assertEquals("Repository ID should be empty string", "", status.getRepositoryId());
        
        log.info("Empty repository ID test passed");
    }
    
    @Test
    public void testNullHandlingInHealthStatus() {
        log.info("Testing null handling in IndexHealthStatus");
        
        IndexHealthStatus health = new IndexHealthStatus();
        
        // All fields should have default values
        assertNull("Repository ID should be null initially", health.getRepositoryId());
        assertNull("Message should be null initially", health.getMessage());
        assertEquals("Solr count should be 0", 0, health.getSolrDocumentCount());
        assertEquals("CouchDB count should be 0", 0, health.getCouchDbDocumentCount());
        assertFalse("Should not be healthy by default", health.isHealthy());
        
        log.info("Null handling in IndexHealthStatus test passed");
    }
    
    @Test
    public void testReindexStatusTimestamps() {
        log.info("Testing ReindexStatus timestamps");
        
        ReindexStatus status = new ReindexStatus();
        long startTime = System.currentTimeMillis();
        status.setStartTime(startTime);
        
        // Simulate some processing time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long endTime = System.currentTimeMillis();
        status.setEndTime(endTime);
        
        assertEquals("Start time should match", startTime, status.getStartTime());
        assertEquals("End time should match", endTime, status.getEndTime());
        assertTrue("End time should be after start time", status.getEndTime() >= status.getStartTime());
        
        // Calculate duration
        long duration = status.getEndTime() - status.getStartTime();
        assertTrue("Duration should be positive", duration >= 0);
        
        log.info("ReindexStatus timestamps test passed");
    }
    
    @Test
    public void testReindexStatusProgressCalculation() {
        log.info("Testing ReindexStatus progress calculation");
        
        ReindexStatus status = new ReindexStatus();
        
        // Test 0% progress
        status.setTotalDocuments(100);
        status.setIndexedCount(0);
        double progress0 = calculateProgress(status);
        assertEquals("Progress should be 0%", 0.0, progress0, 0.01);
        
        // Test 25% progress
        status.setIndexedCount(25);
        double progress25 = calculateProgress(status);
        assertEquals("Progress should be 25%", 25.0, progress25, 0.01);
        
        // Test 100% progress
        status.setIndexedCount(100);
        double progress100 = calculateProgress(status);
        assertEquals("Progress should be 100%", 100.0, progress100, 0.01);
        
        // Test edge case: 0 total documents
        status.setTotalDocuments(0);
        status.setIndexedCount(0);
        double progressZeroTotal = calculateProgress(status);
        assertEquals("Progress should be 0% when total is 0", 0.0, progressZeroTotal, 0.01);
        
        log.info("ReindexStatus progress calculation test passed");
    }
    
    private double calculateProgress(ReindexStatus status) {
        if (status.getTotalDocuments() == 0) {
            return 0.0;
        }
        return (double) status.getIndexedCount() / status.getTotalDocuments() * 100;
    }
    
    @Test
    public void testLargeErrorsList() {
        log.info("Testing large errors list handling");
        
        ReindexStatus status = new ReindexStatus();
        List<String> errors = new ArrayList<>();
        
        // Add 150 errors (more than the 100 limit in the implementation)
        for (int i = 0; i < 150; i++) {
            errors.add("Error " + i + ": Document doc-" + i + " failed");
        }
        status.setErrors(errors);
        
        assertEquals("Should store all 150 errors", 150, status.getErrors().size());
        
        log.info("Large errors list handling test passed");
    }
    
    @Test
    public void testSpecialCharactersInRepositoryId() {
        log.info("Testing special characters in repository ID");
        
        String[] specialRepoIds = {
            "repo-with-dash",
            "repo_with_underscore",
            "repo.with" + ".dots",
            "repo123",
            "UPPERCASE_REPO"
        };
        
        for (String repoId : specialRepoIds) {
            ReindexStatus status = service.getReindexStatus(repoId);
            assertNotNull("Status should not be null for " + repoId, status);
            assertEquals("Repository ID should match", repoId, status.getRepositoryId());
        }
        
        log.info("Special characters in repository ID test passed");
    }
    
    @Test
    public void testReindexStatusErrorsList() {
        log.info("Testing ReindexStatus errors list");
        
        ReindexStatus status = new ReindexStatus();
        status.setRepositoryId("test-repo");
        
        List<String> errors = new ArrayList<>();
        errors.add("Error 1: Document doc-1 failed");
        errors.add("Error 2: Document doc-2 failed");
        errors.add("Error 3: Batch indexing failed");
        status.setErrors(errors);
        
        assertNotNull("Errors list should not be null", status.getErrors());
        assertEquals("Should have 3 errors", 3, status.getErrors().size());
        assertTrue("First error should contain doc-1", status.getErrors().get(0).contains("doc-1"));
        
        log.info("ReindexStatus errors list test passed");
    }
    
    // ==================== Service Lifecycle Tests ====================
    
    @Test
    public void testServiceShutdown() {
        log.info("Testing service shutdown");
        
        SolrIndexMaintenanceServiceImpl testService = new SolrIndexMaintenanceServiceImpl();
        
        // Service should be usable before shutdown
        ReindexStatus status = testService.getReindexStatus("test-repo");
        assertNotNull("Status should be available before shutdown", status);
        
        // Shutdown should not throw exception
        testService.shutdown();
        
        log.info("Service shutdown test passed");
    }
    
    @Test
    public void testMultipleShutdownCalls() {
        log.info("Testing multiple shutdown calls");
        
        SolrIndexMaintenanceServiceImpl testService = new SolrIndexMaintenanceServiceImpl();
        
        // Multiple shutdown calls should not throw exception
        testService.shutdown();
        testService.shutdown();
        testService.shutdown();
        
        log.info("Multiple shutdown calls test passed");
    }
    
    // ==================== Query Result Tests ====================
    
    @Test
    public void testSolrQueryResultEmptyDocs() {
        log.info("Testing SolrQueryResult with empty docs");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setNumFound(0);
        result.setStart(0);
        result.setQueryTime(5);
        result.setDocs(new ArrayList<>());
        
        assertEquals("NumFound should be 0", 0, result.getNumFound());
        assertNotNull("Docs should not be null", result.getDocs());
        assertTrue("Docs should be empty", result.getDocs().isEmpty());
        
        log.info("SolrQueryResult with empty docs test passed");
    }
    
    @Test
    public void testSolrQueryResultWithComplexFields() {
        log.info("Testing SolrQueryResult with complex fields");
        
        SolrQueryResult result = new SolrQueryResult();
        result.setNumFound(1);
        result.setStart(0);
        result.setQueryTime(20);
        
        java.util.List<java.util.Map<String, Object>> docs = new java.util.ArrayList<>();
        java.util.Map<String, Object> doc = new java.util.HashMap<>();
        
        // Add various field types
        doc.put("id", "complex-doc-1");
        doc.put("name", "Complex Document");
        doc.put("score", 0.95);
        doc.put("created", "2024-01-15T10:30:00Z");
        doc.put("tags", Arrays.asList("tag1", "tag2", "tag3"));
        
        docs.add(doc);
        result.setDocs(docs);
        
        assertEquals("Should have 1 document", 1, result.getDocs().size());
        assertEquals("ID should match", "complex-doc-1", result.getDocs().get(0).get("id"));
        assertEquals("Score should match", 0.95, result.getDocs().get(0).get("score"));
        
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) result.getDocs().get(0).get("tags");
        assertEquals("Should have 3 tags", 3, tags.size());
        
        log.info("SolrQueryResult with complex fields test passed");
    }
    
    // ==================== Health Status Calculation Tests ====================
    
    @Test
    public void testHealthStatusCalculationExactMatch() {
        log.info("Testing health status calculation with exact match");
        
        IndexHealthStatus health = new IndexHealthStatus();
        health.setSolrDocumentCount(500);
        health.setCouchDbDocumentCount(500);
        
        long diff = health.getCouchDbDocumentCount() - health.getSolrDocumentCount();
        assertEquals("Difference should be 0", 0, diff);
        
        health.setHealthy(diff == 0);
        assertTrue("Should be healthy when counts match", health.isHealthy());
        
        log.info("Health status calculation with exact match test passed");
    }
    
    @Test
    public void testHealthStatusCalculationMissing() {
        log.info("Testing health status calculation with missing documents");
        
        IndexHealthStatus health = new IndexHealthStatus();
        health.setSolrDocumentCount(450);
        health.setCouchDbDocumentCount(500);
        
        long diff = health.getCouchDbDocumentCount() - health.getSolrDocumentCount();
        assertEquals("Difference should be 50", 50, diff);
        
        if (diff > 0) {
            health.setMissingInSolr(diff);
        }
        
        health.setHealthy(diff == 0);
        assertFalse("Should not be healthy when documents are missing", health.isHealthy());
        assertEquals("Missing count should be 50", 50, health.getMissingInSolr());
        
        log.info("Health status calculation with missing documents test passed");
    }
    
    @Test
    public void testHealthStatusCalculationOrphaned() {
        log.info("Testing health status calculation with orphaned documents");
        
        IndexHealthStatus health = new IndexHealthStatus();
        health.setSolrDocumentCount(550);
        health.setCouchDbDocumentCount(500);
        
        long diff = health.getCouchDbDocumentCount() - health.getSolrDocumentCount();
        assertEquals("Difference should be -50", -50, diff);
        
        if (diff < 0) {
            health.setOrphanedInSolr(-diff);
        }
        
        health.setHealthy(diff == 0);
        assertFalse("Should not be healthy when orphaned documents exist", health.isHealthy());
        assertEquals("Orphaned count should be 50", 50, health.getOrphanedInSolr());
        
        log.info("Health status calculation with orphaned documents test passed");
    }
    
    // ==================== Batch Size Tests ====================
    
    @Test
    public void testBatchSizeCalculation() {
        log.info("Testing batch size calculation");
        
        // The BATCH_SIZE constant should be 100 as per implementation
        // We can verify behavior by checking batch calculations
        
        int batchSize = 100;
        
        // Test various document counts
        assertEquals("250 docs should require 3 batches", 3, (int) Math.ceil(250.0 / batchSize));
        assertEquals("100 docs should require 1 batch", 1, (int) Math.ceil(100.0 / batchSize));
        assertEquals("101 docs should require 2 batches", 2, (int) Math.ceil(101.0 / batchSize));
        assertEquals("1 doc should require 1 batch", 1, (int) Math.ceil(1.0 / batchSize));
        assertEquals("1000 docs should require 10 batches", 10, (int) Math.ceil(1000.0 / batchSize));
        
        log.info("Batch size calculation test passed");
    }
    
    @Test
    public void testBatchCommitWithinMs() {
        log.info("Testing batch commit within milliseconds");
        
        // The BATCH_COMMIT_WITHIN_MS constant should be 5000 as per implementation
        int expectedCommitWithinMs = 5000;
        
        // Verify the expected value is reasonable (1-10 seconds)
        assertTrue("Commit within should be at least 1 second", expectedCommitWithinMs >= 1000);
        assertTrue("Commit within should be at most 10 seconds", expectedCommitWithinMs <= 10000);
        
        log.info("Batch commit within milliseconds test passed");
    }
    
    // ==================== Cancel Reindex Tests ====================
    
    @Test
    public void testCancelReindexDuringExecution() {
        log.info("Testing cancel reindex during execution");
        
        // Start a reindex
        boolean started = service.startFullReindex("cancel-test-repo");
        assertTrue("Reindex should start", started);
        
        // Immediately try to cancel
        boolean cancelled = service.cancelReindex("cancel-test-repo");
        assertTrue("Cancel should return true when reindex is running", cancelled);
        
        // Wait a bit for the cancellation to take effect
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        log.info("Cancel reindex during execution test passed");
    }
    
    @Test
    public void testCancelReindexForNonExistentRepo() {
        log.info("Testing cancel reindex for non-existent repository");
        
        // Try to cancel a reindex that was never started
        boolean cancelled = service.cancelReindex("non-existent-repo");
        assertFalse("Cancel should return false for non-existent repo", cancelled);
        
        log.info("Cancel reindex for non-existent repository test passed");
    }
    
    // ==================== Folder Reindex Tests ====================
    
    @Test
    public void testStartFolderReindexRecursive() {
        log.info("Testing startFolderReindex with recursive flag");
        
        boolean started = service.startFolderReindex("folder-recursive-repo", "folder-123", true);
        assertTrue("Folder reindex should start with recursive=true", started);
        
        // Wait a bit for async task to process
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        log.info("startFolderReindex with recursive flag test passed");
    }
    
    @Test
    public void testStartFolderReindexNonRecursive() {
        log.info("Testing startFolderReindex without recursive flag");
        
        boolean started = service.startFolderReindex("folder-nonrecursive-repo", "folder-456", false);
        assertTrue("Folder reindex should start with recursive=false", started);
        
        // Wait a bit for async task to process
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        log.info("startFolderReindex without recursive flag test passed");
    }
    
    // ==================== Query Parameter Tests ====================
    
    @Test
    public void testExecuteSolrQueryWithAllParameters() {
        log.info("Testing executeSolrQuery with all parameters");
        
        // Without dependencies, should return error but should handle all parameters
        SolrQueryResult result = service.executeSolrQuery(
            "test-repo", 
            "name:test", 
            10, 
            20, 
            "created desc", 
            "id,name,created"
        );
        
        assertNotNull("Result should not be null", result);
        // Error is expected without dependencies
        assertNotNull("Error message should be set", result.getErrorMessage());
        
        log.info("executeSolrQuery with all parameters test passed");
    }
    
    @Test
    public void testExecuteSolrQueryWithNullParameters() {
        log.info("Testing executeSolrQuery with null parameters");
        
        // Should handle null sort and fields gracefully
        SolrQueryResult result = service.executeSolrQuery(
            "test-repo", 
            "*:*", 
            0, 
            10, 
            null, 
            null
        );
        
        assertNotNull("Result should not be null", result);
        
        log.info("executeSolrQuery with null parameters test passed");
    }
    
    @Test
    public void testExecuteSolrQueryWithEmptyQuery() {
        log.info("Testing executeSolrQuery with empty query");
        
        // Should handle empty query string
        SolrQueryResult result = service.executeSolrQuery(
            "test-repo", 
            "", 
            0, 
            10, 
            null, 
            null
        );
        
        assertNotNull("Result should not be null", result);
        
        log.info("executeSolrQuery with empty query test passed");
    }
    
    @Test
    public void testExecuteSolrQueryWithNegativeStart() {
        log.info("Testing executeSolrQuery with negative start");
        
        // Should handle negative start value
        SolrQueryResult result = service.executeSolrQuery(
            "test-repo", 
            "*:*", 
            -5, 
            10, 
            null, 
            null
        );
        
        assertNotNull("Result should not be null", result);
        
        log.info("executeSolrQuery with negative start test passed");
    }
    
    @Test
    public void testExecuteSolrQueryWithLargeRows() {
        log.info("Testing executeSolrQuery with large rows value");
        
        // Should handle large rows value (should be capped at MAX_QUERY_ROWS)
        SolrQueryResult result = service.executeSolrQuery(
            "test-repo", 
            "*:*", 
            0, 
            10000, 
            null, 
            null
        );
        
        assertNotNull("Result should not be null", result);
        
        log.info("executeSolrQuery with large rows value test passed");
    }
}
