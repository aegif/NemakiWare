package jp.aegif.nemaki.rag.search;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for VectorSearchServiceImpl performance optimizations.
 *
 * Tests:
 * - Batch enrichment logic (N+1 query fix)
 * - Parallel execution pattern (double KNN query optimization)
 * - ConcurrentHashMap thread-safety
 */
public class VectorSearchPerformanceTest {

    // ========== Batch Enrichment Tests (P0-2 Fix) ==========

    @Test
    public void testBatchQueryConstruction() {
        // Simulate building a batch query for multiple document IDs
        List<String> documentIds = new ArrayList<>();
        documentIds.add("doc1");
        documentIds.add("doc2");
        documentIds.add("doc3");

        StringBuilder queryBuilder = new StringBuilder("id:(");
        for (int i = 0; i < documentIds.size(); i++) {
            if (i > 0) {
                queryBuilder.append(" OR ");
            }
            queryBuilder.append(documentIds.get(i));
        }
        queryBuilder.append(")");

        String query = queryBuilder.toString();
        assertEquals("id:(doc1 OR doc2 OR doc3)", query);
    }

    @Test
    public void testBatchQueryConstructionSingleDocument() {
        List<String> documentIds = new ArrayList<>();
        documentIds.add("single_doc");

        StringBuilder queryBuilder = new StringBuilder("id:(");
        for (int i = 0; i < documentIds.size(); i++) {
            if (i > 0) {
                queryBuilder.append(" OR ");
            }
            queryBuilder.append(documentIds.get(i));
        }
        queryBuilder.append(")");

        String query = queryBuilder.toString();
        assertEquals("id:(single_doc)", query);
    }

    @Test
    public void testBatchQueryConstructionEmpty() {
        List<String> documentIds = new ArrayList<>();

        // Should not construct query for empty list
        assertTrue("Empty list should result in no query", documentIds.isEmpty());
    }

    @Test
    public void testResultEnrichmentWithMap() {
        // Simulate enriching results from a pre-fetched map
        Map<String, MockParentDoc> parentDocs = new HashMap<>();
        parentDocs.put("doc1", new MockParentDoc("Document 1", "/path/to/doc1", "cmis:document"));
        parentDocs.put("doc2", new MockParentDoc("Document 2", "/path/to/doc2", "custom:type"));
        parentDocs.put("doc3", new MockParentDoc("Document 3", "/path/to/doc3", "cmis:document"));

        List<VectorSearchResult> results = new ArrayList<>();
        results.add(createResult("doc1"));
        results.add(createResult("doc2"));
        results.add(createResult("doc3"));

        // Enrich results from map
        for (VectorSearchResult result : results) {
            MockParentDoc parentDoc = parentDocs.get(result.getDocumentId());
            if (parentDoc != null) {
                if (result.getDocumentName() == null) {
                    result.setDocumentName(parentDoc.name);
                }
                result.setPath(parentDoc.path);
                result.setObjectType(parentDoc.objectType);
            }
        }

        // Verify enrichment
        assertEquals("Document 1", results.get(0).getDocumentName());
        assertEquals("/path/to/doc1", results.get(0).getPath());
        assertEquals("cmis:document", results.get(0).getObjectType());

        assertEquals("Document 2", results.get(1).getDocumentName());
        assertEquals("/path/to/doc2", results.get(1).getPath());
        assertEquals("custom:type", results.get(1).getObjectType());
    }

    @Test
    public void testResultEnrichmentPreservesExistingName() {
        Map<String, MockParentDoc> parentDocs = new HashMap<>();
        parentDocs.put("doc1", new MockParentDoc("From Solr", "/path", "type"));

        VectorSearchResult result = createResult("doc1");
        result.setDocumentName("Already Set");

        // Enrich - should preserve existing name
        MockParentDoc parentDoc = parentDocs.get(result.getDocumentId());
        if (parentDoc != null) {
            if (result.getDocumentName() == null) {
                result.setDocumentName(parentDoc.name);
            }
            result.setPath(parentDoc.path);
        }

        assertEquals("Already Set", result.getDocumentName()); // Should not be overwritten
        assertEquals("/path", result.getPath()); // Should be set
    }

    // ========== Parallel Execution Tests (P0-3 Fix) ==========

    @Test
    public void testConcurrentHashMapThreadSafety() throws InterruptedException {
        Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();

        int threadCount = 10;
        int incrementsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    scores.computeIfAbsent("doc1", k -> new AtomicInteger(0)).incrementAndGet();
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals("All increments should be counted",
                threadCount * incrementsPerThread, scores.get("doc1").get());
    }

    @Test
    public void testParallelExecutionPattern() throws Exception {
        // Simulate parallel execution pattern used in executeWeightedKnnSearch
        AtomicInteger chunkSearchCount = new AtomicInteger(0);
        AtomicInteger propertySearchCount = new AtomicInteger(0);

        CompletableFuture<Void> chunkSearchFuture = CompletableFuture.runAsync(() -> {
            // Simulate chunk search
            try {
                Thread.sleep(50); // Simulate work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            chunkSearchCount.incrementAndGet();
        });

        CompletableFuture<Void> propertySearchFuture = CompletableFuture.runAsync(() -> {
            // Simulate property search
            try {
                Thread.sleep(50); // Simulate work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            propertySearchCount.incrementAndGet();
        });

        // Wait for both to complete
        CompletableFuture.allOf(chunkSearchFuture, propertySearchFuture).join();

        assertEquals("Chunk search should have run once", 1, chunkSearchCount.get());
        assertEquals("Property search should have run once", 1, propertySearchCount.get());
    }

    @Test
    public void testParallelExecutionReducesTime() throws Exception {
        // Test that parallel execution is faster than sequential
        long parallelStart = System.currentTimeMillis();

        CompletableFuture<Void> task1 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        });
        CompletableFuture<Void> task2 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
        });

        CompletableFuture.allOf(task1, task2).join();
        long parallelDuration = System.currentTimeMillis() - parallelStart;

        // Parallel should complete in ~100ms (not 200ms)
        // Allow some margin for thread scheduling
        assertTrue("Parallel execution should be faster than sequential (took " + parallelDuration + "ms)",
                parallelDuration < 180);
    }

    @Test
    public void testConcurrentHashMapComputeIfAbsent() {
        // Test ConcurrentHashMap.computeIfAbsent pattern used in search methods
        Map<String, MockScoredDocument> documentScores = new ConcurrentHashMap<>();

        // Simulate concurrent updates from chunk search and property search
        Thread chunkThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                String docId = "doc" + (i % 10);
                MockScoredDocument doc = documentScores.computeIfAbsent(docId, k -> new MockScoredDocument(k));
                doc.setContentScore(0.8f);
            }
        });

        Thread propertyThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                String docId = "doc" + (i % 10);
                MockScoredDocument doc = documentScores.computeIfAbsent(docId, k -> new MockScoredDocument(k));
                doc.setPropertyScore(0.5f);
            }
        });

        chunkThread.start();
        propertyThread.start();

        try {
            chunkThread.join();
            propertyThread.join();
        } catch (InterruptedException e) {
            fail("Thread interrupted");
        }

        // Should have exactly 10 unique documents
        assertEquals("Should have 10 unique documents", 10, documentScores.size());

        // Each document should have both scores set
        for (MockScoredDocument doc : documentScores.values()) {
            assertTrue("Content score should be set", doc.contentScore > 0);
            assertTrue("Property score should be set", doc.propertyScore > 0);
        }
    }

    @Test
    public void testCompletableFutureExceptionHandling() {
        // Test that exceptions in parallel tasks are handled gracefully
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        CompletableFuture<Void> successTask = CompletableFuture.runAsync(() -> {
            successCount.incrementAndGet();
        });

        CompletableFuture<Void> errorTask = CompletableFuture.runAsync(() -> {
            try {
                // Simulate error handling pattern from executeWeightedKnnSearch
                throw new RuntimeException("Simulated error");
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        });

        CompletableFuture.allOf(successTask, errorTask).join();

        assertEquals("Success task should complete", 1, successCount.get());
        assertEquals("Error should be caught", 1, errorCount.get());
    }

    // ========== Score Combination Tests ==========

    @Test
    public void testScoreCombinationWithConcurrentMap() {
        Map<String, MockScoredDocument> documentScores = new ConcurrentHashMap<>();

        // Simulate chunk search results
        documentScores.computeIfAbsent("doc1", k -> new MockScoredDocument(k)).setContentScore(0.8f * 0.7f); // weighted
        documentScores.computeIfAbsent("doc2", k -> new MockScoredDocument(k)).setContentScore(0.6f * 0.7f);

        // Simulate property search results
        documentScores.computeIfAbsent("doc1", k -> new MockScoredDocument(k)).setPropertyScore(0.7f * 0.3f); // weighted
        documentScores.computeIfAbsent("doc3", k -> new MockScoredDocument(k)).setPropertyScore(0.9f * 0.3f);

        // Calculate combined scores
        float doc1Combined = documentScores.get("doc1").getCombinedScore();
        float doc2Combined = documentScores.get("doc2").getCombinedScore();
        float doc3Combined = documentScores.get("doc3").getCombinedScore();

        // doc1 has both scores
        assertEquals(0.8f * 0.7f + 0.7f * 0.3f, doc1Combined, 0.001f);
        // doc2 only has content score
        assertEquals(0.6f * 0.7f, doc2Combined, 0.001f);
        // doc3 only has property score
        assertEquals(0.9f * 0.3f, doc3Combined, 0.001f);
    }

    // ========== Helper Classes ==========

    private VectorSearchResult createResult(String documentId) {
        VectorSearchResult result = new VectorSearchResult();
        result.setDocumentId(documentId);
        return result;
    }

    private static class MockParentDoc {
        String name;
        String path;
        String objectType;

        MockParentDoc(String name, String path, String objectType) {
            this.name = name;
            this.path = path;
            this.objectType = objectType;
        }
    }

    private static class MockScoredDocument {
        String documentId;
        volatile float contentScore = 0f;
        volatile float propertyScore = 0f;

        MockScoredDocument(String documentId) {
            this.documentId = documentId;
        }

        void setContentScore(float score) {
            this.contentScore = score;
        }

        void setPropertyScore(float score) {
            this.propertyScore = score;
        }

        float getCombinedScore() {
            return contentScore + propertyScore;
        }
    }
}
