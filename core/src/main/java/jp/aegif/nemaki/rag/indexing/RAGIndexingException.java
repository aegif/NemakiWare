package jp.aegif.nemaki.rag.indexing;

/**
 * Exception thrown when RAG indexing operations fail.
 */
public class RAGIndexingException extends Exception {

    private static final long serialVersionUID = 1L;

    public RAGIndexingException(String message) {
        super(message);
    }

    public RAGIndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
