package jp.aegif.nemaki.rag.indexing;

/**
 * Exception thrown when RAG indexing operations fail.
 *
 * This can occur due to:
 * - RAG service being disabled
 * - Unsupported MIME type
 * - Text extraction failure
 * - Embedding generation failure
 * - Solr indexing failure
 * - ACL expansion failure
 */
public class RAGIndexingException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Error type classification for better error handling.
     */
    public enum ErrorType {
        /** RAG service is disabled */
        SERVICE_DISABLED,
        /** MIME type is not supported for indexing */
        UNSUPPORTED_MIME_TYPE,
        /** Text extraction failed */
        TEXT_EXTRACTION_FAILED,
        /** No text content could be extracted or produced */
        NO_CONTENT,
        /** Embedding generation failed */
        EMBEDDING_FAILED,
        /** Solr indexing operation failed */
        SOLR_ERROR,
        /** ACL expansion failed */
        ACL_ERROR,
        /** Unknown or unclassified error */
        UNKNOWN
    }

    private final ErrorType errorType;
    private final boolean retryable;

    public RAGIndexingException(String message) {
        super(message);
        this.errorType = ErrorType.UNKNOWN;
        this.retryable = false;
    }

    public RAGIndexingException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = ErrorType.UNKNOWN;
        this.retryable = false;
    }

    public RAGIndexingException(String message, ErrorType errorType, boolean retryable) {
        super(message);
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public RAGIndexingException(String message, Throwable cause, ErrorType errorType, boolean retryable) {
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
     * Create an exception for disabled service.
     */
    public static RAGIndexingException serviceDisabled(String message) {
        return new RAGIndexingException(message, ErrorType.SERVICE_DISABLED, false);
    }

    /**
     * Create an exception for unsupported MIME type.
     */
    public static RAGIndexingException unsupportedMimeType(String mimeType) {
        return new RAGIndexingException(
                "MIME type not supported: " + mimeType,
                ErrorType.UNSUPPORTED_MIME_TYPE, false);
    }

    /**
     * Create an exception for text extraction failure.
     */
    public static RAGIndexingException textExtractionFailed(String documentId, Throwable cause) {
        return new RAGIndexingException(
                "Text extraction failed for document: " + documentId,
                cause, ErrorType.TEXT_EXTRACTION_FAILED, false);
    }

    /**
     * Create an exception for no content.
     */
    public static RAGIndexingException noContent(String reason) {
        return new RAGIndexingException(
                "No text content: " + reason,
                ErrorType.NO_CONTENT, false);
    }

    /**
     * Create an exception for embedding failure (retryable).
     */
    public static RAGIndexingException embeddingFailed(String documentId, Throwable cause) {
        return new RAGIndexingException(
                "Failed to generate embeddings for document: " + documentId,
                cause, ErrorType.EMBEDDING_FAILED, true);
    }

    /**
     * Create an exception for Solr error (retryable).
     */
    public static RAGIndexingException solrError(String documentId, Throwable cause) {
        return new RAGIndexingException(
                "Solr indexing failed for document: " + documentId,
                cause, ErrorType.SOLR_ERROR, true);
    }

    /**
     * Create an exception for ACL expansion failure.
     */
    public static RAGIndexingException aclError(String documentId, Throwable cause) {
        return new RAGIndexingException(
                "ACL expansion failed for document: " + documentId,
                cause, ErrorType.ACL_ERROR, false);
    }
}
