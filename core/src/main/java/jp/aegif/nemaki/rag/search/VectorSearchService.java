package jp.aegif.nemaki.rag.search;

import java.util.List;

/**
 * Service interface for vector-based semantic search.
 *
 * Provides semantic search using dense vector similarity.
 * Results are filtered by ACL to ensure users only see documents they have access to.
 */
public interface VectorSearchService {

    /**
     * Perform semantic search using a query string.
     *
     * @param repositoryId Repository ID
     * @param userId Current user ID for ACL filtering
     * @param query Query text (will be embedded)
     * @param topK Maximum number of results
     * @return List of search results sorted by similarity score
     * @throws VectorSearchException if search fails
     */
    List<VectorSearchResult> search(String repositoryId, String userId, String query, int topK)
            throws VectorSearchException;

    /**
     * Perform semantic search with minimum similarity threshold.
     *
     * @param repositoryId Repository ID
     * @param userId Current user ID for ACL filtering
     * @param query Query text (will be embedded)
     * @param topK Maximum number of results
     * @param minScore Minimum similarity score (0.0 to 1.0)
     * @return List of search results sorted by similarity score
     * @throws VectorSearchException if search fails
     */
    List<VectorSearchResult> search(String repositoryId, String userId, String query,
                                    int topK, float minScore) throws VectorSearchException;

    /**
     * Perform semantic search with custom property and content boost factors.
     * Final score = (propertyBoost × property_similarity) + (contentBoost × content_similarity)
     *
     * @param repositoryId Repository ID
     * @param userId Current user ID for ACL filtering
     * @param query Query text (will be embedded)
     * @param topK Maximum number of results
     * @param minScore Minimum similarity score (0.0 to 1.0)
     * @param propertyBoost Weight for property-based similarity (0.0 to 1.0)
     * @param contentBoost Weight for content-based similarity (0.0 to 1.0)
     * @return List of search results sorted by combined similarity score
     * @throws VectorSearchException if search fails
     */
    List<VectorSearchResult> searchWithBoost(String repositoryId, String userId, String query,
                                             int topK, float minScore,
                                             float propertyBoost, float contentBoost) throws VectorSearchException;

    /**
     * Perform semantic search within a specific folder.
     *
     * @param repositoryId Repository ID
     * @param userId Current user ID for ACL filtering
     * @param query Query text (will be embedded)
     * @param folderId Folder ID to search within
     * @param topK Maximum number of results
     * @return List of search results sorted by similarity score
     * @throws VectorSearchException if search fails
     */
    List<VectorSearchResult> searchInFolder(String repositoryId, String userId, String query,
                                            String folderId, int topK) throws VectorSearchException;

    /**
     * Folder-scoped search with the caller's weighting and threshold.
     *
     * <p>The five-argument form uses the server's configured boosts and similarity threshold.
     * That silently discarded whatever the caller asked for: {@code RAGSearchResource} routes
     * every request carrying a {@code folderId} here, so {@code propertyBoost},
     * {@code contentBoost} and {@code minScore} were accepted by the API and then dropped,
     * with no error. A caller asking for content-only search inside a folder got the default
     * mix instead — which also meant a folder filter that was broken for chunks could be
     * masked entirely by the property half.
     *
     * @param minScore minimum combined similarity, or null for the configured threshold
     * @param propertyBoost weight for metadata similarity, or null for the configured value
     * @param contentBoost weight for body similarity, or null for the configured value
     */
    List<VectorSearchResult> searchInFolder(String repositoryId, String userId, String query,
                                            String folderId, int topK, Float minScore,
                                            Float propertyBoost, Float contentBoost)
            throws VectorSearchException;


    /**
     * Find documents similar to a given document based on vector similarity.
     *
     * @param repositoryId Repository ID
     * @param userId Current user ID for ACL filtering
     * @param documentId Document ID to find similar documents for
     * @param topK Maximum number of results (excluding the source document)
     * @param minScore Minimum similarity score (0.0 to 1.0)
     * @return List of similar documents sorted by similarity score
     * @throws VectorSearchException if the document is not indexed or search fails
     */
    List<VectorSearchResult> findSimilarDocuments(String repositoryId, String userId,
                                                   String documentId, int topK, float minScore)
            throws VectorSearchException;

    /**
     * Check if vector search is enabled and available.
     *
     * @return true if vector search can be performed
     */
    boolean isEnabled();
}
