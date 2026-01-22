package jp.aegif.nemaki.rag.embedding;

import java.util.List;

/**
 * Service interface for generating text embeddings.
 *
 * Embeddings are dense vector representations of text that capture semantic meaning.
 * This service abstracts the embedding provider (TEI, OpenAI, etc.) to allow
 * for provider fallback and testing.
 *
 * The default implementation uses Hugging Face's Text Embeddings Inference (TEI)
 * with the multilingual E5 model (intfloat/multilingual-e5-large).
 *
 * @see TeiEmbeddingService
 */
public interface EmbeddingService {

    /**
     * Vector dimension for E5 multilingual-large model.
     */
    int VECTOR_DIMENSION = 1024;

    /**
     * Prefix for query texts (E5 model requirement).
     * E5 models require specific prefixes for asymmetric retrieval.
     */
    String QUERY_PREFIX = "query: ";

    /**
     * Prefix for passage/document texts (E5 model requirement).
     */
    String PASSAGE_PREFIX = "passage: ";

    /**
     * Generate embedding for a single text.
     *
     * @param text The text to embed
     * @param isQuery If true, prepends "query: " prefix; if false, prepends "passage: "
     * @return Vector embedding as float array (1024 dimensions for E5)
     * @throws EmbeddingException if embedding generation fails
     */
    float[] embed(String text, boolean isQuery) throws EmbeddingException;

    /**
     * Generate embeddings for multiple texts in batch.
     * More efficient than calling embed() multiple times.
     *
     * @param texts List of texts to embed
     * @param isQuery If true, prepends "query: " prefix to all texts
     * @return List of vector embeddings, same order as input
     * @throws EmbeddingException if embedding generation fails
     */
    List<float[]> embedBatch(List<String> texts, boolean isQuery) throws EmbeddingException;

    /**
     * Generate embedding for a query text.
     * Convenience method that calls embed(text, true).
     *
     * @param queryText The query text to embed
     * @return Vector embedding as float array
     * @throws EmbeddingException if embedding generation fails
     */
    default float[] embedQuery(String queryText) throws EmbeddingException {
        return embed(queryText, true);
    }

    /**
     * Generate embedding for a document/passage text.
     * Convenience method that calls embed(text, false).
     *
     * @param passageText The passage text to embed
     * @return Vector embedding as float array
     * @throws EmbeddingException if embedding generation fails
     */
    default float[] embedPassage(String passageText) throws EmbeddingException {
        return embed(passageText, false);
    }

    /**
     * Check if the embedding service is available and healthy.
     *
     * @return true if the service can generate embeddings
     */
    boolean isHealthy();

    /**
     * Get the vector dimension of embeddings produced by this service.
     *
     * @return Vector dimension (1024 for E5 multilingual-large)
     */
    default int getVectorDimension() {
        return VECTOR_DIMENSION;
    }
}
