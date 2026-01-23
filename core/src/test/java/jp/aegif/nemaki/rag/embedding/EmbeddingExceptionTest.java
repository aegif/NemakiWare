package jp.aegif.nemaki.rag.embedding;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for EmbeddingException.
 * 
 * Tests the exception class including:
 * - Constructors
 * - Factory methods
 * - Error type and retryable flag
 */
public class EmbeddingExceptionTest {

    // ========== Constructor Tests ==========

    @Test
    public void testConstructorWithMessage() {
        EmbeddingException exception = new EmbeddingException("Test error message");

        assertEquals("Message should match", "Test error message", exception.getMessage());
        assertEquals("Default error type should be UNKNOWN", 
                EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType());
        assertFalse("Default retryable should be false", exception.isRetryable());
        assertNull("Cause should be null", exception.getCause());
    }

    @Test
    public void testConstructorWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception = new EmbeddingException("Test error", cause);

        assertEquals("Message should match", "Test error", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Default error type should be UNKNOWN", 
                EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType());
        assertFalse("Default retryable should be false", exception.isRetryable());
    }

    @Test
    public void testConstructorWithMessageErrorTypeAndRetryable() {
        EmbeddingException exception = new EmbeddingException(
                "Service error", 
                EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, 
                true);

        assertEquals("Message should match", "Service error", exception.getMessage());
        assertEquals("Error type should match", 
                EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, exception.getErrorType());
        assertTrue("Retryable should be true", exception.isRetryable());
        assertNull("Cause should be null", exception.getCause());
    }

    @Test
    public void testConstructorWithAllParameters() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception = new EmbeddingException(
                "Connection failed", 
                cause,
                EmbeddingException.ErrorType.CONNECTION_ERROR, 
                true);

        assertEquals("Message should match", "Connection failed", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should match", 
                EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType());
        assertTrue("Retryable should be true", exception.isRetryable());
    }

    // ========== Factory Method Tests ==========

    @Test
    public void testConnectionError() {
        Throwable cause = new RuntimeException("Network error");
        EmbeddingException exception = EmbeddingException.connectionError("Connection failed", cause);

        assertEquals("Message should match", "Connection failed", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be CONNECTION_ERROR", 
                EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType());
        assertTrue("Connection errors should be retryable", exception.isRetryable());
    }

    @Test
    public void testServiceUnavailable() {
        EmbeddingException exception = EmbeddingException.serviceUnavailable("Service is down");

        assertEquals("Message should match", "Service is down", exception.getMessage());
        assertNull("Cause should be null", exception.getCause());
        assertEquals("Error type should be SERVICE_UNAVAILABLE", 
                EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, exception.getErrorType());
        assertTrue("Service unavailable errors should be retryable", exception.isRetryable());
    }

    @Test
    public void testTimeout() {
        Throwable cause = new RuntimeException("Socket timeout");
        EmbeddingException exception = EmbeddingException.timeout("Request timed out", cause);

        assertEquals("Message should match", "Request timed out", exception.getMessage());
        assertEquals("Cause should match", cause, exception.getCause());
        assertEquals("Error type should be TIMEOUT", 
                EmbeddingException.ErrorType.TIMEOUT, exception.getErrorType());
        assertTrue("Timeout errors should be retryable", exception.isRetryable());
    }

    @Test
    public void testInvalidInput() {
        EmbeddingException exception = EmbeddingException.invalidInput("Text cannot be empty");

        assertEquals("Message should match", "Text cannot be empty", exception.getMessage());
        assertNull("Cause should be null", exception.getCause());
        assertEquals("Error type should be INVALID_INPUT", 
                EmbeddingException.ErrorType.INVALID_INPUT, exception.getErrorType());
        assertFalse("Invalid input errors should NOT be retryable", exception.isRetryable());
    }

    // ========== Error Type Tests ==========

    @Test
    public void testAllErrorTypes() {
        // Verify all error types exist
        EmbeddingException.ErrorType[] types = EmbeddingException.ErrorType.values();
        
        assertEquals("Should have 6 error types", 6, types.length);
        
        // Verify each type
        assertNotNull(EmbeddingException.ErrorType.CONNECTION_ERROR);
        assertNotNull(EmbeddingException.ErrorType.SERVICE_UNAVAILABLE);
        assertNotNull(EmbeddingException.ErrorType.INVALID_INPUT);
        assertNotNull(EmbeddingException.ErrorType.RATE_LIMITED);
        assertNotNull(EmbeddingException.ErrorType.TIMEOUT);
        assertNotNull(EmbeddingException.ErrorType.UNKNOWN);
    }

    @Test
    public void testErrorTypeValueOf() {
        assertEquals(EmbeddingException.ErrorType.CONNECTION_ERROR, 
                EmbeddingException.ErrorType.valueOf("CONNECTION_ERROR"));
        assertEquals(EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, 
                EmbeddingException.ErrorType.valueOf("SERVICE_UNAVAILABLE"));
        assertEquals(EmbeddingException.ErrorType.INVALID_INPUT, 
                EmbeddingException.ErrorType.valueOf("INVALID_INPUT"));
        assertEquals(EmbeddingException.ErrorType.RATE_LIMITED, 
                EmbeddingException.ErrorType.valueOf("RATE_LIMITED"));
        assertEquals(EmbeddingException.ErrorType.TIMEOUT, 
                EmbeddingException.ErrorType.valueOf("TIMEOUT"));
        assertEquals(EmbeddingException.ErrorType.UNKNOWN, 
                EmbeddingException.ErrorType.valueOf("UNKNOWN"));
    }

    // ========== Retryable Flag Tests ==========

    @Test
    public void testRetryableForConnectionError() {
        EmbeddingException exception = EmbeddingException.connectionError("error", null);
        assertTrue("CONNECTION_ERROR should be retryable", exception.isRetryable());
    }

    @Test
    public void testRetryableForServiceUnavailable() {
        EmbeddingException exception = EmbeddingException.serviceUnavailable("error");
        assertTrue("SERVICE_UNAVAILABLE should be retryable", exception.isRetryable());
    }

    @Test
    public void testRetryableForTimeout() {
        EmbeddingException exception = EmbeddingException.timeout("error", null);
        assertTrue("TIMEOUT should be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForInvalidInput() {
        EmbeddingException exception = EmbeddingException.invalidInput("error");
        assertFalse("INVALID_INPUT should NOT be retryable", exception.isRetryable());
    }

    @Test
    public void testNotRetryableForDefaultConstructor() {
        EmbeddingException exception = new EmbeddingException("error");
        assertFalse("Default constructor should create non-retryable exception", 
                exception.isRetryable());
    }

    // ========== Exception Hierarchy Tests ==========

    @Test
    public void testExceptionIsCheckedException() {
        EmbeddingException exception = new EmbeddingException("test");
        
        assertTrue("EmbeddingException should be an Exception", 
                exception instanceof Exception);
        assertFalse("EmbeddingException should not be a RuntimeException", 
                exception instanceof RuntimeException);
    }

    @Test
    public void testExceptionCanBeCaught() {
        try {
            throw new EmbeddingException("Test exception");
        } catch (EmbeddingException e) {
            assertEquals("Caught exception message should match", 
                    "Test exception", e.getMessage());
        }
    }

    @Test
    public void testExceptionCanBeCaughtAsException() {
        try {
            throw new EmbeddingException("Test exception");
        } catch (Exception e) {
            assertTrue("Should be caught as Exception", e instanceof EmbeddingException);
        }
    }

    // ========== Edge Cases ==========

    @Test
    public void testNullMessage() {
        EmbeddingException exception = new EmbeddingException(null);
        
        assertNull("Null message should be preserved", exception.getMessage());
        assertEquals("Error type should still be UNKNOWN", 
                EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType());
    }

    @Test
    public void testEmptyMessage() {
        EmbeddingException exception = new EmbeddingException("");
        
        assertEquals("Empty message should be preserved", "", exception.getMessage());
    }

    @Test
    public void testNullCause() {
        EmbeddingException exception = new EmbeddingException("error", null);
        
        assertNull("Null cause should be preserved", exception.getCause());
    }

    @Test
    public void testConnectionErrorWithNullCause() {
        EmbeddingException exception = EmbeddingException.connectionError("error", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be CONNECTION_ERROR", 
                EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType());
    }

    @Test
    public void testTimeoutWithNullCause() {
        EmbeddingException exception = EmbeddingException.timeout("error", null);
        
        assertNull("Null cause should be preserved in factory method", exception.getCause());
        assertEquals("Error type should still be TIMEOUT", 
                EmbeddingException.ErrorType.TIMEOUT, exception.getErrorType());
    }

    // ========== Chained Exception Tests ==========

    @Test
    public void testChainedExceptions() {
        Throwable rootCause = new IllegalArgumentException("Root cause");
        Throwable middleCause = new RuntimeException("Middle cause", rootCause);
        EmbeddingException exception = new EmbeddingException("Top level error", middleCause);

        assertEquals("Direct cause should be middle cause", middleCause, exception.getCause());
        assertEquals("Root cause should be accessible", rootCause, exception.getCause().getCause());
    }

    @Test
    public void testGetStackTrace() {
        EmbeddingException exception = new EmbeddingException("test");
        
        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull("Stack trace should not be null", stackTrace);
        assertTrue("Stack trace should have elements", stackTrace.length > 0);
    }
}
