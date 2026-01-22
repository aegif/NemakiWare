package jp.aegif.nemaki.rag.embedding;

/**
 * Exception thrown when embedding generation fails.
 *
 * This can occur due to:
 * - Network connectivity issues with the embedding service
 * - Embedding service unavailability
 * - Invalid input text
 * - Rate limiting or resource exhaustion
 */
public class EmbeddingException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Error type classification for better error handling.
     */
    public enum ErrorType {
        /** Network or connectivity error */
        CONNECTION_ERROR,
        /** Service is unavailable or unhealthy */
        SERVICE_UNAVAILABLE,
        /** Input validation error */
        INVALID_INPUT,
        /** Rate limit exceeded */
        RATE_LIMITED,
        /** Timeout waiting for response */
        TIMEOUT,
        /** Unknown or unclassified error */
        UNKNOWN
    }

    private final ErrorType errorType;
    private final boolean retryable;

    public EmbeddingException(String message) {
        super(message);
        this.errorType = ErrorType.UNKNOWN;
        this.retryable = false;
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = ErrorType.UNKNOWN;
        this.retryable = false;
    }

    public EmbeddingException(String message, ErrorType errorType, boolean retryable) {
        super(message);
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public EmbeddingException(String message, Throwable cause, ErrorType errorType, boolean retryable) {
        super(message, cause);
        this.errorType = errorType;
        this.retryable = retryable;
    }

    /**
     * Get the error type classification.
     *
     * @return Error type enum
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * Whether this error is retryable.
     *
     * @return true if the operation can be retried
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Create a connection error exception.
     */
    public static EmbeddingException connectionError(String message, Throwable cause) {
        return new EmbeddingException(message, cause, ErrorType.CONNECTION_ERROR, true);
    }

    /**
     * Create a service unavailable exception.
     */
    public static EmbeddingException serviceUnavailable(String message) {
        return new EmbeddingException(message, ErrorType.SERVICE_UNAVAILABLE, true);
    }

    /**
     * Create a timeout exception.
     */
    public static EmbeddingException timeout(String message, Throwable cause) {
        return new EmbeddingException(message, cause, ErrorType.TIMEOUT, true);
    }

    /**
     * Create an invalid input exception.
     */
    public static EmbeddingException invalidInput(String message) {
        return new EmbeddingException(message, ErrorType.INVALID_INPUT, false);
    }
}
