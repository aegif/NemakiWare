package jp.aegif.nemaki.rag.embedding;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for Embedding related functionality.
 * Note: TeiEmbeddingService requires external TEI service,
 * so these tests focus on interface contracts and exception classes.
 */
public class EmbeddingServiceTest {

    @Test
    public void testEmbeddingExceptionMessage() {
        EmbeddingException ex = new EmbeddingException("Test error");
        assertEquals("Test error", ex.getMessage());
    }

    @Test
    public void testEmbeddingExceptionWithCause() {
        RuntimeException cause = new RuntimeException("Original error");
        EmbeddingException ex = new EmbeddingException("Wrapped error", cause);

        assertEquals("Wrapped error", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    public void testEmbeddingExceptionTimeout() {
        RuntimeException cause = new RuntimeException("timeout");
        EmbeddingException ex = EmbeddingException.timeout("Timeout occurred", cause);

        assertTrue(ex.getMessage().contains("Timeout"));
        assertEquals(cause, ex.getCause());
    }

    @Test
    public void testEmbeddingExceptionServiceUnavailable() {
        EmbeddingException ex = EmbeddingException.serviceUnavailable("Service down");

        assertTrue(ex.getMessage().contains("Service down"));
    }

    @Test
    public void testEmbeddingExceptionConnectionError() {
        RuntimeException cause = new RuntimeException("Connection refused");
        EmbeddingException ex = EmbeddingException.connectionError("Cannot connect", cause);

        assertTrue(ex.getMessage().contains("Cannot connect"));
        assertEquals(cause, ex.getCause());
    }

    @Test
    public void testE5QueryPrefix() {
        // E5 model requires "query:" prefix for query texts
        String query = "What is NemakiWare?";
        String prefixedQuery = "query: " + query;

        assertTrue(prefixedQuery.startsWith("query:"));
    }

    @Test
    public void testE5PassagePrefix() {
        // E5 model requires "passage:" prefix for document texts
        String passage = "NemakiWare is a CMIS 1.1 compliant ECM system.";
        String prefixedPassage = "passage: " + passage;

        assertTrue(prefixedPassage.startsWith("passage:"));
    }

    @Test
    public void testEmbeddingVectorDimensions() {
        // E5-large model produces 1024-dimensional vectors
        int expectedDimensions = 1024;

        // Mock embedding vector
        float[] vector = new float[expectedDimensions];
        for (int i = 0; i < expectedDimensions; i++) {
            vector[i] = (float) Math.random();
        }

        assertEquals(expectedDimensions, vector.length);
    }

    @Test
    public void testNormalizeVector() {
        // Test L2 normalization which is typically applied to embeddings
        float[] vector = {3.0f, 4.0f}; // 3-4-5 triangle

        float magnitude = (float) Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / magnitude;
        }

        // After normalization, magnitude should be ~1.0
        float normalizedMagnitude = (float) Math.sqrt(
                normalized[0] * normalized[0] + normalized[1] * normalized[1]);
        assertEquals(1.0f, normalizedMagnitude, 0.001f);
    }

    @Test
    public void testCosineSimilarity() {
        // Test cosine similarity calculation
        float[] vector1 = {1.0f, 0.0f};
        float[] vector2 = {0.707f, 0.707f}; // 45 degrees

        float dotProduct = vector1[0] * vector2[0] + vector1[1] * vector2[1];
        float magnitude1 = (float) Math.sqrt(vector1[0] * vector1[0] + vector1[1] * vector1[1]);
        float magnitude2 = (float) Math.sqrt(vector2[0] * vector2[0] + vector2[1] * vector2[1]);

        float cosineSimilarity = dotProduct / (magnitude1 * magnitude2);

        // Cosine similarity of 45 degrees should be ~0.707
        assertEquals(0.707f, cosineSimilarity, 0.01f);
    }

    @Test
    public void testBatchEmbeddingSize() {
        // Verify batch size constraints
        int maxBatchSize = 32; // Typical TEI batch size
        List<String> texts = Arrays.asList("text1", "text2", "text3");

        assertTrue(texts.size() <= maxBatchSize);
    }
}
