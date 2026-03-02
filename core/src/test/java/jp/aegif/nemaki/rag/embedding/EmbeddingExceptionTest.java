package jp.aegif.nemaki.rag.embedding;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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

        assertEquals("Test error message", exception.getMessage(), "Message should match");
        assertEquals(EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType(), "Default error type should be UNKNOWN");
        assertFalse(exception.isRetryable(), "Default retryable should be false");
        assertNull(exception.getCause(), "Cause should be null");
    }

    @Test
    public void testConstructorWithMessageAndCause() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception = new EmbeddingException("Test error", cause);

        assertEquals("Test error", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType(), "Default error type should be UNKNOWN");
        assertFalse(exception.isRetryable(), "Default retryable should be false");
    }

    @Test
    public void testConstructorWithMessageErrorTypeAndRetryable() {
        EmbeddingException exception = new EmbeddingException(
                "Service error", 
                EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, 
                true);

        assertEquals("Service error", exception.getMessage(), "Message should match");
        assertEquals(EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, exception.getErrorType(), "Error type should match");
        assertTrue(exception.isRetryable(), "Retryable should be true");
        assertNull(exception.getCause(), "Cause should be null");
    }

    @Test
    public void testConstructorWithAllParameters() {
        Throwable cause = new RuntimeException("Root cause");
        EmbeddingException exception = new EmbeddingException(
                "Connection failed", 
                cause,
                EmbeddingException.ErrorType.CONNECTION_ERROR, 
                true);

        assertEquals("Connection failed", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType(), "Error type should match");
        assertTrue(exception.isRetryable(), "Retryable should be true");
    }

    // ========== Factory Method Tests ==========

    @Test
    public void testConnectionError() {
        Throwable cause = new RuntimeException("Network error");
        EmbeddingException exception = EmbeddingException.connectionError("Connection failed", cause);

        assertEquals("Connection failed", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType(), "Error type should be CONNECTION_ERROR");
        assertTrue(exception.isRetryable(), "Connection errors should be retryable");
    }

    @Test
    public void testServiceUnavailable() {
        EmbeddingException exception = EmbeddingException.serviceUnavailable("Service is down");

        assertEquals("Service is down", exception.getMessage(), "Message should match");
        assertNull(exception.getCause(), "Cause should be null");
        assertEquals(EmbeddingException.ErrorType.SERVICE_UNAVAILABLE, exception.getErrorType(), "Error type should be SERVICE_UNAVAILABLE");
        assertTrue(exception.isRetryable(), "Service unavailable errors should be retryable");
    }

    @Test
    public void testTimeout() {
        Throwable cause = new RuntimeException("Socket timeout");
        EmbeddingException exception = EmbeddingException.timeout("Request timed out", cause);

        assertEquals("Request timed out", exception.getMessage(), "Message should match");
        assertEquals(cause, exception.getCause(), "Cause should match");
        assertEquals(EmbeddingException.ErrorType.TIMEOUT, exception.getErrorType(), "Error type should be TIMEOUT");
        assertTrue(exception.isRetryable(), "Timeout errors should be retryable");
    }

    @Test
    public void testInvalidInput() {
        EmbeddingException exception = EmbeddingException.invalidInput("Text cannot be empty");

        assertEquals("Text cannot be empty", exception.getMessage(), "Message should match");
        assertNull(exception.getCause(), "Cause should be null");
        assertEquals(EmbeddingException.ErrorType.INVALID_INPUT, exception.getErrorType(), "Error type should be INVALID_INPUT");
        assertFalse(exception.isRetryable(), "Invalid input errors should NOT be retryable");
    }

    // ========== Error Type Tests ==========

    @Test
    public void testAllErrorTypes() {
        // Verify all error types exist
        EmbeddingException.ErrorType[] types = EmbeddingException.ErrorType.values();
        
        assertEquals(6, types.length, "Should have 6 error types");
        
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
        assertTrue(exception.isRetryable(), "CONNECTION_ERROR should be retryable");
    }

    @Test
    public void testRetryableForServiceUnavailable() {
        EmbeddingException exception = EmbeddingException.serviceUnavailable("error");
        assertTrue(exception.isRetryable(), "SERVICE_UNAVAILABLE should be retryable");
    }

    @Test
    public void testRetryableForTimeout() {
        EmbeddingException exception = EmbeddingException.timeout("error", null);
        assertTrue(exception.isRetryable(), "TIMEOUT should be retryable");
    }

    @Test
    public void testNotRetryableForInvalidInput() {
        EmbeddingException exception = EmbeddingException.invalidInput("error");
        assertFalse(exception.isRetryable(), "INVALID_INPUT should NOT be retryable");
    }

    @Test
    public void testNotRetryableForDefaultConstructor() {
        EmbeddingException exception = new EmbeddingException("error");
        assertFalse(exception.isRetryable(), "Default constructor should create non-retryable exception");
    }

    // ========== Exception Hierarchy Tests ==========

    @Test
    public void testExceptionIsCheckedException() {
        EmbeddingException exception = new EmbeddingException("test");

        assertTrue(exception instanceof Exception, "EmbeddingException should be an Exception");
        // EmbeddingException extends Exception (not RuntimeException)
        // This is enforced by the type system - no runtime check needed
        assertFalse(RuntimeException.class.isAssignableFrom(EmbeddingException.class), "EmbeddingException should not be a RuntimeException");
    }

    @Test
    public void testExceptionCanBeCaught() {
        try {
            throw new EmbeddingException("Test exception");
        } catch (EmbeddingException e) {
            assertEquals("Test exception", e.getMessage(), "Caught exception message should match");
        }
    }

    @Test
    public void testExceptionCanBeCaughtAsException() {
        try {
            throw new EmbeddingException("Test exception");
        } catch (Exception e) {
            assertTrue(e instanceof EmbeddingException, "Should be caught as Exception");
        }
    }

    // ========== Edge Cases ==========

    @Test
    public void testNullMessage() {
        EmbeddingException exception = new EmbeddingException(null);
        
        assertNull(exception.getMessage(), "Null message should be preserved");
        assertEquals(EmbeddingException.ErrorType.UNKNOWN, exception.getErrorType(), "Error type should still be UNKNOWN");
    }

    @Test
    public void testEmptyMessage() {
        EmbeddingException exception = new EmbeddingException("");
        
        assertEquals("", exception.getMessage(), "Empty message should be preserved");
    }

    @Test
    public void testNullCause() {
        EmbeddingException exception = new EmbeddingException("error", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved");
    }

    @Test
    public void testConnectionErrorWithNullCause() {
        EmbeddingException exception = EmbeddingException.connectionError("error", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(EmbeddingException.ErrorType.CONNECTION_ERROR, exception.getErrorType(), "Error type should still be CONNECTION_ERROR");
    }

    @Test
    public void testTimeoutWithNullCause() {
        EmbeddingException exception = EmbeddingException.timeout("error", null);
        
        assertNull(exception.getCause(), "Null cause should be preserved in factory method");
        assertEquals(EmbeddingException.ErrorType.TIMEOUT, exception.getErrorType(), "Error type should still be TIMEOUT");
    }

    // ========== Chained Exception Tests ==========

    @Test
    public void testChainedExceptions() {
        Throwable rootCause = new IllegalArgumentException("Root cause");
        Throwable middleCause = new RuntimeException("Middle cause", rootCause);
        EmbeddingException exception = new EmbeddingException("Top level error", middleCause);

        assertEquals(middleCause, exception.getCause(), "Direct cause should be middle cause");
        assertEquals(rootCause, exception.getCause().getCause(), "Root cause should be accessible");
    }

    @Test
    public void testGetStackTrace() {
        EmbeddingException exception = new EmbeddingException("test");
        
        StackTraceElement[] stackTrace = exception.getStackTrace();
        assertNotNull(stackTrace, "Stack trace should not be null");
        assertTrue(stackTrace.length > 0, "Stack trace should have elements");
    }
}
