package jp.aegif.nemaki.rag.indexing;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for RAGIndexingException.
 * 
 * Tests the exception class including:
 * - Constructors
 * - Factory methods
 * - Error type classification
 * - Retryable flag logic
 */
public class RAGIndexingExceptionTest {

    // ========== Constructor Tests ==========

    @Test
    public void testConstructorWithMessage() {
        RAGIndexingException exception = new RAGIndexingException("Test error message");

        assertEquals("Message should match", "Test error message", exception.getMessage());
        assertEquals("Default error type should be UNKNOWN", 
                RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType());
        assertFalse("Default retryable should be false", exception.isRetryable());
        assertNull("Cause should be null", exception.getCause());
    }

    @Test
    public void testConstructorWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        RAGIndexingException exception = new RAGIndexingException("Test error", cause);

        assertEquals("Message should match", "Test error", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Default error type should be UNKNOWN", 
                RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType());
        assertFalse("Default retryable should be false", exception.isRetryable());
    }

    @Test
    public void testConstructorWithMessageErrorTypeAndRetryable() {
        RAGIndexingException exception = new RAGIndexingException(
                "Service error", 
                RAGIndexingException.ErrorType.SOLR_ERROR, 
                true);

        assertEquals("Message should match", "Service error", exception.getMessage());
        assertEquals("Error type should match", 
                RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType());
        assertTrue("Retryable should be true", exception.isRetryable());
        assertNull("Cause should be null", exception.getCause());
    }

    @Test
    public void testConstructorWithAllParameters() {
        Throwable cause = new RuntimeException("Root cause");
        RAGIndexingException exception = new RAGIndexingException(
                "Embedding failed", 
                cause,
                RAGIndexingException.ErrorType.EMBEDDING_FAILED, 
                true);

        assertEquals("Message should match", "Embedding failed", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should match", 
                RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType());
        assertTrue("Retryable should be true", exception.isRetryable());
    }

    // ========== Factory Method Tests ==========

    @Test
    public void testServiceDisabled() {
        RAGIndexingException exception = RAGIndexingException.serviceDisabled("RAG is disabled");

        assertEquals("Message should match", "RAG is disabled", exception.getMessage());
        assertNull("Cause should be null", exception.getCause());
        assertEquals("Error type should be SERVICE_DISABLED", 
                RAGIndexingException.ErrorType.SERVICE_DISABLED, exception.getErrorType());
        assertFalse("Service disabled errors should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testUnsupportedMimeType() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("image/png");

        assertTrue("Message should contain MIME type", 
                exception.getMessage().contains("image/png"));
        assertTrue("Message should indicate unsupported", 
                exception.getMessage().toLowerCase().contains("not supported"));
        assertNull("Cause should be null", exception.getCause());
        assertEquals("Error type should be UNSUPPORTED_MIME_TYPE", 
                RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, exception.getErrorType());
        assertFalse("Unsupported MIME type errors should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testTextExtractionFailed() {
        Throwable cause = new RuntimeException("Tika extraction error");
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc-123", cause);

        assertTrue("Message should contain document ID", 
                exception.getMessage().contains("doc-123"));
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be TEXT_EXTRACTION_FAILED", 
                RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED, exception.getErrorType());
        assertFalse("Text extraction errors should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNoContent() {
        RAGIndexingException exception = RAGIndexingException.noContent("Document is empty");

        assertTrue("Message should contain reason", 
                exception.getMessage().contains("Document is empty"));
        assertNull("Cause should be null", exception.getCause());
        assertEquals("Error type should be NO_CONTENT", 
                RAGIndexingException.ErrorType.NO_CONTENT, exception.getErrorType());
        assertFalse("No content errors should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testEmbeddingFailed() {
        Throwable cause = new RuntimeException("TEI connection error");
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc-456", cause);

        assertTrue("Message should contain document ID", 
                exception.getMessage().contains("doc-456"));
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be EMBEDDING_FAILED", 
                RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType());
        assertTrue("Embedding errors should be retryable", exception.isRetryable());
    }

    @Test
    public void testSolrError() {
        Throwable cause = new RuntimeException("Solr connection refused");
        RAGIndexingException exception = RAGIndexingException.solrError("doc-789", cause);

        assertTrue("Message should contain document ID", 
                exception.getMessage().contains("doc-789"));
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be SOLR_ERROR", 
                RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType());
        assertTrue("Solr errors should be retryable", exception.isRetryable());
    }

    @Test
    public void testAclError() {
        Throwable cause = new RuntimeException("Principal service error");
        RAGIndexingException exception = RAGIndexingException.aclError("doc-abc", cause);

        assertTrue("Message should contain document ID", 
                exception.getMessage().contains("doc-abc"));
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be ACL_ERROR", 
                RAGIndexingException.ErrorType.ACL_ERROR, exception.getErrorType());
        assertFalse("ACL errors should NOT be retryable", exception.isRetryable());
    }

    // ========== Error Type Tests ==========

    @Test
    public void testAllErrorTypes() {
        RAGIndexingException.ErrorType[] types = RAGIndexingException.ErrorType.values();
        
        assertEquals("Should have 8 error types", 8, types.length);
        
        assertNotNull(RAGIndexingException.ErrorType.SERVICE_DISABLED);
        assertNotNull(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE);
        assertNotNull(RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED);
        assertNotNull(RAGIndexingException.ErrorType.NO_CONTENT);
        assertNotNull(RAGIndexingException.ErrorType.EMBEDDING_FAILED);
        assertNotNull(RAGIndexingException.ErrorType.SOLR_ERROR);
        assertNotNull(RAGIndexingException.ErrorType.ACL_ERROR);
        assertNotNull(RAGIndexingException.ErrorType.UNKNOWN);
    }

    @Test
    public void testErrorTypeValueOf() {
        assertEquals(RAGIndexingException.ErrorType.SERVICE_DISABLED, 
                RAGIndexingException.ErrorType.valueOf("SERVICE_DISABLED"));
        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, 
                RAGIndexingException.ErrorType.valueOf("UNSUPPORTED_MIME_TYPE"));
        assertEquals(RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED, 
                RAGIndexingException.ErrorType.valueOf("TEXT_EXTRACTION_FAILED"));
        assertEquals(RAGIndexingException.ErrorType.NO_CONTENT, 
                RAGIndexingException.ErrorType.valueOf("NO_CONTENT"));
        assertEquals(RAGIndexingException.ErrorType.EMBEDDING_FAILED, 
                RAGIndexingException.ErrorType.valueOf("EMBEDDING_FAILED"));
        assertEquals(RAGIndexingException.ErrorType.SOLR_ERROR, 
                RAGIndexingException.ErrorType.valueOf("SOLR_ERROR"));
        assertEquals(RAGIndexingException.ErrorType.ACL_ERROR, 
                RAGIndexingException.ErrorType.valueOf("ACL_ERROR"));
        assertEquals(RAGIndexingException.ErrorType.UNKNOWN, 
                RAGIndexingException.ErrorType.valueOf("UNKNOWN"));
    }

    // ========== Retryable Flag Tests ==========

    @Test
    public void testRetryableForEmbeddingFailed() {
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc", null);
        assertTrue("EMBEDDING_FAILED should be retryable", exception.isRetryable());
    }

    @Test
    public void testRetryableForSolrError() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc", null);
        assertTrue("SOLR_ERROR should be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForServiceDisabled() {
        RAGIndexingException exception = RAGIndexingException.serviceDisabled("disabled");
        assertFalse("SERVICE_DISABLED should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForUnsupportedMimeType() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("image/png");
        assertFalse("UNSUPPORTED_MIME_TYPE should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForTextExtractionFailed() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc", null);
        assertFalse("TEXT_EXTRACTION_FAILED should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForNoContent() {
        RAGIndexingException exception = RAGIndexingException.noContent("empty");
        assertFalse("NO_CONTENT should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForAclError() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc", null);
        assertFalse("ACL_ERROR should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForDefaultConstructor() {
        RAGIndexingException exception = new RAGIndexingException("error");
        assertFalse("Default constructor should create non-retryable exception", 
                exception.isRetryable());
    }

    // ========== Exception Hierarchy Tests ==========

    @Test
    public void testExceptionIsCheckedException() {
        RAGIndexingException exception = new RAGIndexingException("test");
        
        assertTrue("RAGIndexingException should be an Exception", 
                exception instanceof Exception);
        assertFalse("RAGIndexingException should not be a RuntimeException", 
                exception instanceof RuntimeException);
    }

    @Test
    public void testExceptionCanBeCaught() {
        try {
            throw new RAGIndexingException("Test exception");
        } catch (RAGIndexingException e) {
            assertEquals("Caught exception message should match", 
                    "Test exception", e.getMessage());
        }
    }

    @Test
    public void testExceptionCanBeCaughtAsException() {
        try {
            throw new RAGIndexingException("Test exception");
        } catch (Exception e) {
            assertTrue("Should be caught as Exception", e instanceof RAGIndexingException);
        }
    }

    // ========== Edge Cases ==========

    @Test
    public void testNullMessage() {
        RAGIndexingException exception = new RAGIndexingException(null);
        
        assertNull("Null message should be preserved", exception.getMessage());
        assertEquals("Error type should still be UNKNOWN", 
                RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType());
    }

    @Test
    public void testEmptyMessage() {
        RAGIndexingException exception = new RAGIndexingException("");
        
        assertEquals("Empty message should be preserved", "", exception.getMessage());
    }

    @Test
    public void testNullCause() {
        RAGIndexingException exception = new RAGIndexingException("error", null);
        
        assertNull("Null cause should be preserved", exception.getCause());
    }

    @Test
    public void testTextExtractionFailedWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be TEXT_EXTRACTION_FAILED", 
                RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED, exception.getErrorType());
    }

    @Test
    public void testEmbeddingFailedWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be EMBEDDING_FAILED", 
                RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType());
    }

    @Test
    public void testSolrErrorWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be SOLR_ERROR", 
                RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType());
    }

    @Test
    public void testAclErrorWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be ACL_ERROR", 
                RAGIndexingException.ErrorType.ACL_ERROR, exception.getErrorType());
    }

    // ========== Chained Exception Tests ==========

    @Test
    public void testChainedExceptions() {
        Throwable rootCause = new IllegalArgumentException("Root cause");
        Throwable middleCause = new RuntimeException("Middle cause", rootCause);
        RAGIndexingException exception = new RAGIndexingException("Top level error", middleCause);

        assertEquals("Direct cause should be middle cause", middleCause, exception.getCause());
        assertEquals("Root cause should be accessible", rootCause, exception.getCause().getCause());
    }

    @Test
    public void testGetStackTrace() {
        RAGIndexingException exception = new RAGIndexingException("test");
        
        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull("Stack trace should not be null", stackTrace);
        assertTrue("Stack trace should have elements", stackTrace.length > 0);
    }

    // ========== Factory Method Message Format Tests ==========

    @Test
    public void testUnsupportedMimeTypeMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("application/octet-stream");
        
        assertEquals("Message format should match", 
                "MIME type not supported: application/octet-stream", exception.getMessage());
    }

    @Test
    public void testTextExtractionFailedMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc-123", null);
        
        assertEquals("Message format should match", 
                "Text extraction failed for document: doc-123", exception.getMessage());
    }

    @Test
    public void testNoContentMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.noContent("empty document");
        
        assertEquals("Message format should match", 
                "No text content: empty document", exception.getMessage());
    }

    @Test
    public void testEmbeddingFailedMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc-456", null);
        
        assertEquals("Message format should match", 
                "Failed to generate embeddings for document: doc-456", exception.getMessage());
    }

    @Test
    public void testSolrErrorMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc-789", null);
        
        assertEquals("Message format should match", 
                "Solr indexing failed for document: doc-789", exception.getMessage());
    }

    @Test
    public void testAclErrorMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc-abc", null);
        
        assertEquals("Message format should match", 
                "ACL expansion failed for document: doc-abc", exception.getMessage());
    }
}
