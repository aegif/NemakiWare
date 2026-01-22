package jp.aegif.nemaki.rag.search;

/**
 * Exception thrown when vector search fails.
 */
public class VectorSearchException extends Exception {

    private static final long serialVersionUID = 1L;

    public VectorSearchException(String message) {
        super(message);
    }

    public VectorSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
