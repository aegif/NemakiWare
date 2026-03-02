package jp.aegif.nemaki.rag.indexing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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

        assertEquals("Test error message", exception.getMessage(), "Message should match");
        assertEquals(RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType(), "Default error type should be UNKNOWN");
        assertFalse(exception.isRetryable(), "Default retryable should be false");
        assertNull(exception.getCause(), "Cause should be null");
    }

    @Test
    public void testConstructorWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        RAGIndexingException exception = new RAGIndexingException("Test error", cause);

        assertEquals("Test error", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType(), "Default error type should be UNKNOWN");
        assertFalse(exception.isRetryable(), "Default retryable should be false");
    }

    @Test
    public void testConstructorWithMessageErrorTypeAndRetryable() {
        RAGIndexingException exception = new RAGIndexingException(
                "Service error", 
                RAGIndexingException.ErrorType.SOLR_ERROR, 
                true);

        assertEquals("Service error", exception.getMessage(), "Message should match");
        assertEquals(RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType(), "Error type should match");
        assertTrue(exception.isRetryable(), "Retryable should be true");
        assertNull(exception.getCause(), "Cause should be null");
    }

    @Test
    public void testConstructorWithAllParameters() {
        Throwable cause = new RuntimeException("Root cause");
        RAGIndexingException exception = new RAGIndexingException(
                "Embedding failed", 
                cause,
                RAGIndexingException.ErrorType.EMBEDDING_FAILED, 
                true);

        assertEquals("Embedding failed", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType(), "Error type should match");
        assertTrue(exception.isRetryable(), "Retryable should be true");
    }

    // ========== Factory Method Tests ==========

    @Test
    public void testServiceDisabled() {
        RAGIndexingException exception = RAGIndexingException.serviceDisabled("RAG is disabled");

        assertEquals("RAG is disabled", exception.getMessage(), "Message should match");
        assertNull(exception.getCause(), "Cause should be null");
        assertEquals(RAGIndexingException.ErrorType.SERVICE_DISABLED, exception.getErrorType(), "Error type should be SERVICE_DISABLED");
        assertFalse(exception.isRetryable(), "Service disabled errors should NOT be retryable");
    }

    @Test
    public void testUnsupportedMimeType() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("image/png");

        assertTrue(exception.getMessage().contains("image/png"), "Message should contain MIME type");
        assertTrue(exception.getMessage().toLowerCase().contains("not supported"), "Message should indicate unsupported");
        assertNull(exception.getCause(), "Cause should be null");
        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, exception.getErrorType(), "Error type should be UNSUPPORTED_MIME_TYPE");
        assertFalse(exception.isRetryable(), "Unsupported MIME type errors should NOT be retryable");
    }

    @Test
    public void testTextExtractionFailed() {
        Throwable cause = new RuntimeException("Tika extraction error");
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc-123", cause);

        assertTrue(exception.getMessage().contains("doc-123"), "Message should contain document ID");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED, exception.getErrorType(), "Error type should be TEXT_EXTRACTION_FAILED");
        assertFalse(exception.isRetryable(), "Text extraction errors should NOT be retryable");
    }

    @Test
    public void testNoContent() {
        RAGIndexingException exception = RAGIndexingException.noContent("Document is empty");

        assertTrue(exception.getMessage().contains("Document is empty"), "Message should contain reason");
        assertNull(exception.getCause(), "Cause should be null");
        assertEquals(RAGIndexingException.ErrorType.NO_CONTENT, exception.getErrorType(), "Error type should be NO_CONTENT");
        assertFalse(exception.isRetryable(), "No content errors should NOT be retryable");
    }

    @Test
    public void testEmbeddingFailed() {
        Throwable cause = new RuntimeException("TEI connection error");
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc-456", cause);

        assertTrue(exception.getMessage().contains("doc-456"), "Message should contain document ID");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType(), "Error type should be EMBEDDING_FAILED");
        assertTrue(exception.isRetryable(), "Embedding errors should be retryable");
    }

    @Test
    public void testSolrError() {
        Throwable cause = new RuntimeException("Solr connection refused");
        RAGIndexingException exception = RAGIndexingException.solrError("doc-789", cause);

        assertTrue(exception.getMessage().contains("doc-789"), "Message should contain document ID");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType(), "Error type should be SOLR_ERROR");
        assertTrue(exception.isRetryable(), "Solr errors should be retryable");
    }

    @Test
    public void testAclError() {
        Throwable cause = new RuntimeException("Principal service error");
        RAGIndexingException exception = RAGIndexingException.aclError("doc-abc", cause);

        assertTrue(exception.getMessage().contains("doc-abc"), "Message should contain document ID");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(RAGIndexingException.ErrorType.ACL_ERROR, exception.getErrorType(), "Error type should be ACL_ERROR");
        assertFalse(exception.isRetryable(), "ACL errors should NOT be retryable");
    }

    // ========== Error Type Tests ==========

    @Test
    public void testAllErrorTypes() {
        RAGIndexingException.ErrorType[] types = RAGIndexingException.ErrorType.values();
        
        assertEquals(8, types.length, "Should have 8 error types");
        
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
        assertTrue(exception.isRetryable(), "EMBEDDING_FAILED should be retryable");
    }

    @Test
    public void testRetryableForSolrError() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc", null);
        assertTrue(exception.isRetryable(), "SOLR_ERROR should be retryable");
    }

    @Test
    public void testNotRetryableForServiceDisabled() {
        RAGIndexingException exception = RAGIndexingException.serviceDisabled("disabled");
        assertFalse(exception.isRetryable(), "SERVICE_DISABLED should NOT be retryable");
    }

    @Test
    public void testNotRetryableForUnsupportedMimeType() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("image/png");
        assertFalse(exception.isRetryable(), "UNSUPPORTED_MIME_TYPE should NOT be retryable");
    }

    @Test
    public void testNotRetryableForTextExtractionFailed() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc", null);
        assertFalse(exception.isRetryable(), "TEXT_EXTRACTION_FAILED should NOT be retryable");
    }

    @Test
    public void testNotRetryableForNoContent() {
        RAGIndexingException exception = RAGIndexingException.noContent("empty");
        assertFalse(exception.isRetryable(), "NO_CONTENT should NOT be retryable");
    }

    @Test
    public void testNotRetryableForAclError() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc", null);
        assertFalse(exception.isRetryable(), "ACL_ERROR should NOT be retryable");
    }

    @Test
    public void testNotRetryableForDefaultConstructor() {
        RAGIndexingException exception = new RAGIndexingException("error");
        assertFalse(exception.isRetryable(), "Default constructor should create non-retryable exception");
    }

    // ========== Exception Hierarchy Tests ==========

    @Test
    public void testExceptionIsCheckedException() {
        RAGIndexingException exception = new RAGIndexingException("test");

        assertTrue(exception instanceof Exception, "RAGIndexingException should be an Exception");
        // RAGIndexingException extends Exception (not RuntimeException)
        // This is enforced by the type system - no runtime check needed
        assertFalse(RuntimeException.class.isAssignableFrom(RAGIndexingException.class), "RAGIndexingException should not be a RuntimeException");
    }

    @Test
    public void testExceptionCanBeCaught() {
        try {
            throw new RAGIndexingException("Test exception");
        } catch (RAGIndexingException e) {
            assertEquals("Test exception", e.getMessage(), "Caught exception message should match");
        }
    }

    @Test
    public void testExceptionCanBeCaughtAsException() {
        try {
            throw new RAGIndexingException("Test exception");
        } catch (Exception e) {
            assertTrue(e instanceof RAGIndexingException, "Should be caught as Exception");
        }
    }

    // ========== Edge Cases ==========

    @Test
    public void testNullMessage() {
        RAGIndexingException exception = new RAGIndexingException(null);
        
        assertNull(exception.getMessage(), "Null message should be preserved");
        assertEquals(RAGIndexingException.ErrorType.UNKNOWN, exception.getErrorType(), "Error type should still be UNKNOWN");
    }

    @Test
    public void testEmptyMessage() {
        RAGIndexingException exception = new RAGIndexingException("");
        
        assertEquals("", exception.getMessage(), "Empty message should be preserved");
    }

    @Test
    public void testNullCause() {
        RAGIndexingException exception = new RAGIndexingException("error", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved");
    }

    @Test
    public void testTextExtractionFailedWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(RAGIndexingException.ErrorType.TEXT_EXTRACTION_FAILED, exception.getErrorType(), "Error type should still be TEXT_EXTRACTION_FAILED");
    }

    @Test
    public void testEmbeddingFailedWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(RAGIndexingException.ErrorType.EMBEDDING_FAILED, exception.getErrorType(), "Error type should still be EMBEDDING_FAILED");
    }

    @Test
    public void testSolrErrorWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(RAGIndexingException.ErrorType.SOLR_ERROR, exception.getErrorType(), "Error type should still be SOLR_ERROR");
    }

    @Test
    public void testAclErrorWithNullCause() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(RAGIndexingException.ErrorType.ACL_ERROR, exception.getErrorType(), "Error type should still be ACL_ERROR");
    }

    // ========== Chained Exception Tests ==========

    @Test
    public void testChainedExceptions() {
        Throwable rootCause = new IllegalArgumentException("Root cause");
        Throwable middleCause = new RuntimeException("Middle cause", rootCause);
        RAGIndexingException exception = new RAGIndexingException("Top level error", middleCause);

        assertEquals(middleCause, exception.getCause(), "Direct cause should be middle cause");
        assertEquals(rootCause, exception.getCause().getCause(), "Root cause should be accessible");
    }

    @Test
    public void testGetStackTrace() {
        RAGIndexingException exception = new RAGIndexingException("test");
        
        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull(stackTrace, "Stack trace should not be null");
        assertTrue(stackTrace.length > 0, "Stack trace should have elements");
    }

    // ========== Factory Method Message Format Tests ==========

    @Test
    public void testUnsupportedMimeTypeMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.unsupportedMimeType("application/octet-stream");
        
        assertEquals("MIME type not supported: application/octet-stream", exception.getMessage(), "Message format should match");
    }

    @Test
    public void testTextExtractionFailedMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.textExtractionFailed("doc-123", null);
        
        assertEquals("Text extraction failed for document: doc-123", exception.getMessage(), "Message format should match");
    }

    @Test
    public void testNoContentMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.noContent("empty document");
        
        assertEquals("No text content: empty document", exception.getMessage(), "Message format should match");
    }

    @Test
    public void testEmbeddingFailedMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.embeddingFailed("doc-456", null);
        
        assertEquals("Failed to generate embeddings for document: doc-456", exception.getMessage(), "Message format should match");
    }

    @Test
    public void testSolrErrorMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.solrError("doc-789", null);
        
        assertEquals("Solr indexing failed for document: doc-789", exception.getMessage(), "Message format should match");
    }

    @Test
    public void testAclErrorMessageFormat() {
        RAGIndexingException exception = RAGIndexingException.aclError("doc-abc", null);
        
        assertEquals("ACL expansion failed for document: doc-abc", exception.getMessage(), "Message format should match");
    }
}
