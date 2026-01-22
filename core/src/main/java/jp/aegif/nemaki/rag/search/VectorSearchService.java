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
     * Check if vector search is enabled and available.
     *
     * @return true if vector search can be performed
     */
    boolean isEnabled();
}
