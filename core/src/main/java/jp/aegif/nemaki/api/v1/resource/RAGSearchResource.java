package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.UserItem;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import jp.aegif.nemaki.rag.search.VectorSearchException;
import jp.aegif.nemaki.rag.search.VectorSearchResult;
import jp.aegif.nemaki.rag.search.VectorSearchService;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * REST API resource for RAG (Retrieval-Augmented Generation) semantic search.
 *
 * Provides vector-based semantic search capabilities for NemakiWare documents.
 * Uses dense vector embeddings for similarity search with ACL filtering.
 * Supports weighted search combining property and content similarity.
 *
 * Note: This resource is discovered by Jersey package scanning and Spring-managed
 * via Jersey-Spring integration. Do NOT add @Component annotation to avoid duplicate
 * bean issues.
 */
@Path("/repositories/{repositoryId}/rag")
@Tag(name = "rag", description = "RAG semantic search operations")
@Produces(MediaType.APPLICATION_JSON)
public class RAGSearchResource {

    private static final Log log = LogFactory.getLog(RAGSearchResource.class);

    /** Maximum allowed value for topK to prevent Solr overload */
    private static final int MAX_TOP_K = 100;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ContentService contentService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private TypeManager typeManager;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpServletRequest httpRequest;

    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Semantic search",
            description = "Performs semantic search using vector embeddings. " +
                    "Finds documents similar in meaning to the query text, not just keyword matches. " +
                    "Supports weighted search combining property (metadata) and content similarity."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search results",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RAGSearchResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "RAG search not available",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response search(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search request", required = true)
            RAGSearchRequest request) {

        // Log search call at DEBUG level - only log query length to avoid PII exposure
        if (log.isDebugEnabled()) {
            log.debug(String.format("RAG Search called: repo=%s, queryLength=%d, service=%s",
                    repositoryId,
                    request != null && request.getQuery() != null ? request.getQuery().length() : 0,
                    vectorSearchService != null ? "present" : "null"));
        }

        try {
            validateRepository(repositoryId);

            // Check if VectorSearchService is available (DI failure protection)
            if (vectorSearchService == null || !vectorSearchService.isEnabled()) {
                throw ApiException.serviceUnavailable("RAG semantic search is not available");
            }

            if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                throw ApiException.invalidArgument("Query text is required");
            }

            // Validate boost values (0.0 to 1.0 range) and topK
            validateBoostValues(request);
            validateTopK(request);

            // Get current user with null safety
            CallContext context = getCallContext();
            if (context == null || context.getUsername() == null) {
                throw ApiException.unauthorized("Authentication required for RAG search");
            }
            String userId = context.getUsername();

            // Admin simulation: allow admins to search as another user
            if (request.getSimulateAsUserId() != null && !request.getSimulateAsUserId().trim().isEmpty()) {
                UserItem currentUser = contentService.getUserItemById(repositoryId, context.getUsername());
                if (currentUser == null || !currentUser.isAdmin()) {
                    throw ApiException.permissionDenied("Only administrators can simulate search as another user");
                }
                String targetUserId = request.getSimulateAsUserId().trim();
                // Validate that the target user exists
                UserItem targetUser = contentService.getUserItemById(repositoryId, targetUserId);
                if (targetUser == null) {
                    throw ApiException.invalidArgument("User not found: " + targetUserId);
                }
                userId = targetUserId;
                log.info(String.format("Admin %s simulating RAG search as user %s",
                        context.getUsername(), userId));
            }

            // Set defaults with topK upper limit
            int topK = request.getTopK() != null ? Math.min(request.getTopK(), MAX_TOP_K) : 10;
            float minScore = request.getMinScore() != null ? request.getMinScore() : 0.7f;

            // Execute search
            List<VectorSearchResult> results;
            if (request.getFolderId() != null && !request.getFolderId().isEmpty()) {
                results = vectorSearchService.searchInFolder(
                        repositoryId, userId, request.getQuery(), request.getFolderId(), topK);
            } else if (hasCustomBoost(request)) {
                // Use weighted search with custom boost values
                float propertyBoost = request.getPropertyBoost() != null ? request.getPropertyBoost() : 0.3f;
                float contentBoost = request.getContentBoost() != null ? request.getContentBoost() : 0.7f;
                results = vectorSearchService.searchWithBoost(
                        repositoryId, userId, request.getQuery(), topK, minScore, propertyBoost, contentBoost);
            } else {
                results = vectorSearchService.search(
                        repositoryId, userId, request.getQuery(), topK, minScore);
            }

            // SECURITY: Filter results by current CMIS permissions (double-check against stale Solr ACL)
            // The CallContext for permission check must use the actual authenticated user, not simulated user
            List<VectorSearchResult> filteredResults = filterByCurrentPermissions(
                    repositoryId, context, results);

            if (log.isDebugEnabled() && filteredResults.size() != results.size()) {
                log.debug(String.format("RAG search filtered %d results due to permission changes (original: %d)",
                        results.size() - filteredResults.size(), results.size()));
            }

            // Build response with topK transparency
            RAGSearchResponse response = new RAGSearchResponse();
            response.setQuery(request.getQuery());
            response.setResults(filteredResults);
            response.setTotalResults(filteredResults.size());
            response.setTopK(topK);
            response.setTopKLimit(MAX_TOP_K);

            return Response.ok(response).build();

        } catch (ApiException e) {
            throw e;
        } catch (VectorSearchException e) {
            log.warn("Vector search failed: " + e.getMessage());
            throw ApiException.internalError("Vector search failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in RAG search", e);
            throw ApiException.internalError("An unexpected error occurred");
        }
    }

    @GET
    @Path("/search")
    @Operation(
            summary = "Semantic search (GET)",
            description = "Performs semantic search using query parameters. " +
                    "Supports weighted search with propertyBoost and contentBoost parameters."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Search results"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "503", description = "RAG search not available")
    })
    public Response searchGet(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Query text", required = true)
            @QueryParam("q") String query,
            @Parameter(description = "Maximum results")
            @QueryParam("topK") @DefaultValue("10") int topK,
            @Parameter(description = "Minimum similarity score (0.0-1.0)")
            @QueryParam("minScore") @DefaultValue("0.7") float minScore,
            @Parameter(description = "Folder ID to search within")
            @QueryParam("folderId") String folderId,
            @Parameter(description = "Property boost factor (0.0-1.0, higher = more weight on metadata)")
            @QueryParam("propertyBoost") Float propertyBoost,
            @Parameter(description = "Content boost factor (0.0-1.0, higher = more weight on body content)")
            @QueryParam("contentBoost") Float contentBoost,
            @Parameter(description = "Admin only: Simulate search as another user")
            @QueryParam("simulateAsUserId") String simulateAsUserId) {

        RAGSearchRequest request = new RAGSearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setMinScore(minScore);
        request.setFolderId(folderId);
        request.setPropertyBoost(propertyBoost);
        request.setContentBoost(contentBoost);
        request.setSimulateAsUserId(simulateAsUserId);

        return search(repositoryId, request);
    }

    @GET
    @Path("/health")
    @Operation(
            summary = "RAG health check",
            description = "Check if RAG semantic search is enabled and healthy"
    )
    public Response health(
            @PathParam("repositoryId") String repositoryId) {

        Map<String, Object> health = new HashMap<>();
        boolean enabled = vectorSearchService != null && vectorSearchService.isEnabled();
        health.put("enabled", enabled);
        health.put("status", enabled ? "healthy" : "unavailable");

        return Response.ok(health).build();
    }


    @GET
    @Path("/similar/{documentId}")
    @Operation(
            summary = "Find similar documents",
            description = "Finds documents that are semantically similar to the specified document. " +
                    "Uses vector similarity on indexed document embeddings. " +
                    "Only available for documents that have been RAG-indexed."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Similar documents",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SimilarDocumentsResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found or not RAG-indexed",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "RAG search not available",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response findSimilarDocuments(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document ID to find similar documents for", required = true)
            @PathParam("documentId") String documentId,
            @Parameter(description = "Maximum number of similar documents to return")
            @QueryParam("topK") @DefaultValue("10") int topK,
            @Parameter(description = "Minimum similarity score (0.0-1.0)")
            @QueryParam("minScore") @DefaultValue("0.5") float minScore) {

        if (log.isDebugEnabled()) {
            log.debug(String.format("Find similar documents: repo=%s, docId=%s, topK=%d, minScore=%.2f",
                    repositoryId, documentId, topK, minScore));
        }

        try {
            validateRepository(repositoryId);

            if (vectorSearchService == null || !vectorSearchService.isEnabled()) {
                throw ApiException.serviceUnavailable("RAG semantic search is not available");
            }

            // Validate parameters
            if (documentId == null || documentId.trim().isEmpty()) {
                throw ApiException.invalidArgument("Document ID is required");
            }
            if (topK < 1) {
                throw ApiException.invalidArgument("topK must be at least 1");
            }
            if (minScore < 0.0f || minScore > 1.0f) {
                throw ApiException.invalidArgument("minScore must be between 0.0 and 1.0");
            }

            // Limit topK
            int effectiveTopK = Math.min(topK, MAX_TOP_K);

            // Get current user
            CallContext context = getCallContext();
            if (context == null || context.getUsername() == null) {
                throw ApiException.unauthorized("Authentication required");
            }
            String userId = context.getUsername();

            // Find similar documents
            List<VectorSearchResult> results = vectorSearchService.findSimilarDocuments(
                    repositoryId, userId, documentId, effectiveTopK, minScore);

            // Security: Filter by current permissions
            List<VectorSearchResult> filteredResults = filterByCurrentPermissions(
                    repositoryId, context, results);

            // Build response
            SimilarDocumentsResponse response = new SimilarDocumentsResponse();
            response.setSourceDocumentId(documentId);
            response.setResults(filteredResults);
            response.setTotalResults(filteredResults.size());
            response.setTopK(effectiveTopK);
            response.setMinScore(minScore);

            return Response.ok(response).build();

        } catch (ApiException e) {
            throw e;
        } catch (VectorSearchException e) {
            // Check if it's a "not indexed" error
            if (e.getMessage() != null && e.getMessage().contains("not found in RAG index")) {
                throw ApiException.objectNotFound(documentId, repositoryId);
            }
            log.warn("Vector search failed: " + e.getMessage());
            throw ApiException.internalError("Vector search failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in find similar documents", e);
            throw ApiException.internalError("An unexpected error occurred");
        }
    }

    private boolean hasCustomBoost(RAGSearchRequest request) {
        return request.getPropertyBoost() != null || request.getContentBoost() != null;
    }

    private void validateBoostValues(RAGSearchRequest request) {
        if (request.getPropertyBoost() != null) {
            float propertyBoost = request.getPropertyBoost();
            if (propertyBoost < 0.0f || propertyBoost > 1.0f) {
                throw ApiException.invalidArgument(
                        "propertyBoost must be between 0.0 and 1.0, got: " + propertyBoost);
            }
        }
        if (request.getContentBoost() != null) {
            float contentBoost = request.getContentBoost();
            if (contentBoost < 0.0f || contentBoost > 1.0f) {
                throw ApiException.invalidArgument(
                        "contentBoost must be between 0.0 and 1.0, got: " + contentBoost);
            }
        }
        if (request.getMinScore() != null) {
            float minScore = request.getMinScore();
            if (minScore < 0.0f || minScore > 1.0f) {
                throw ApiException.invalidArgument(
                        "minScore must be between 0.0 and 1.0, got: " + minScore);
            }
        }
    }

    /**
     * Validates topK parameter.
     * - If topK < 1, throws an error (invalid input)
     * - If topK > MAX_TOP_K, it will be clamped to MAX_TOP_K (not an error, just adjusted)
     *   This allows clients to request "as many as possible" without needing to know the limit.
     */
    private void validateTopK(RAGSearchRequest request) {
        if (request.getTopK() != null) {
            int topK = request.getTopK();
            if (topK < 1) {
                throw ApiException.invalidArgument("topK must be at least 1, got: " + topK);
            }
            // Note: topK > MAX_TOP_K is NOT an error - it will be clamped in search execution
        }
    }

    private void validateRepository(String repositoryId) {
        try {
            repositoryService.getRepositoryInfo(repositoryId);
        } catch (Exception e) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }

    private CallContext getCallContext() {
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    /**
     * Filter search results by current CMIS permissions using batch operations.
     * This provides a security double-check against potentially stale Solr ACL data.
     *
     * <p><strong>Performance Optimization:</strong> Uses batch APIs to reduce database calls:</p>
     * <ul>
     *   <li>Batch content retrieval: O(n) individual calls → O(1) bulk fetch</li>
     *   <li>Batch ACL calculation: O(n) individual calls → O(1) with caching</li>
     *   <li>Batch permission check: O(n) with single user/group lookup</li>
     * </ul>
     *
     * @param repositoryId Repository ID
     * @param context Current user's call context
     * @param results Search results to filter
     * @return Filtered results that the user can actually access
     */
    private List<VectorSearchResult> filterByCurrentPermissions(
            String repositoryId, CallContext context, List<VectorSearchResult> results) {

        if (results == null || results.isEmpty()) {
            return results;
        }

        // Collect unique document IDs (RAG results may have multiple chunks from same document)
        List<String> documentIds = results.stream()
                .map(VectorSearchResult::getDocumentId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        if (documentIds.isEmpty()) {
            return new ArrayList<>();
        }

        // Batch fetch all contents
        Map<String, jp.aegif.nemaki.model.Content> contents = contentService.getContentsByIds(repositoryId, documentIds);

        if (contents.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("RAG filter: none of " + documentIds.size() + " documents found");
            }
            return new ArrayList<>();
        }

        // Build baseTypes map from TypeManager
        Map<String, String> baseTypes = new HashMap<>();
        for (Map.Entry<String, jp.aegif.nemaki.model.Content> entry : contents.entrySet()) {
            String docId = entry.getKey();
            jp.aegif.nemaki.model.Content content = entry.getValue();
            try {
                TypeDefinition td = typeManager.getTypeDefinition(repositoryId, content);
                if (td != null && td.getBaseTypeId() != null) {
                    baseTypes.put(docId, td.getBaseTypeId().value());
                }
            } catch (Exception e) {
                log.warn("Failed to get type definition for document " + docId + ": " + e.getMessage());
            }
        }

        // Remove contents without valid base types
        contents.keySet().retainAll(baseTypes.keySet());

        if (contents.isEmpty()) {
            return new ArrayList<>();
        }

        // Batch calculate ACLs
        Map<String, jp.aegif.nemaki.model.Acl> acls = contentService.calculateAcls(repositoryId, contents.values());

        // Batch permission check
        String permissionKey = PermissionMapping.CAN_GET_PROPERTIES_OBJECT;
        Map<String, Boolean> permissions = permissionService.checkPermissions(
                context, repositoryId, permissionKey, acls, baseTypes, contents);

        // Filter results based on permissions
        List<VectorSearchResult> filtered = new ArrayList<>();
        int removedCount = 0;

        for (VectorSearchResult result : results) {
            String documentId = result.getDocumentId();
            if (documentId == null) {
                continue;
            }

            Boolean hasPermission = permissions.get(documentId);
            if (hasPermission != null && hasPermission) {
                filtered.add(result);
            } else {
                removedCount++;
                if (log.isDebugEnabled()) {
                    log.debug("RAG result filtered: user " + context.getUsername() +
                            " lacks permission for document " + documentId);
                }
            }
        }

        if (log.isDebugEnabled() && removedCount > 0) {
            log.debug("RAG filter removed " + removedCount + " results due to permission checks");
        }

        return filtered;
    }

    // Request/Response DTOs

    @Schema(description = "RAG search request")
    public static class RAGSearchRequest {
        @Schema(description = "Query text for semantic search", required = true)
        private String query;

        @Schema(description = "Maximum number of results", defaultValue = "10")
        private Integer topK;

        @Schema(description = "Minimum similarity score (0.0-1.0)", defaultValue = "0.7")
        private Float minScore;

        @Schema(description = "Folder ID to restrict search scope")
        private String folderId;

        @Schema(description = "Property boost factor (0.0-1.0). Higher values give more weight to document metadata (name, description). Default: 0.3")
        private Float propertyBoost;

        @Schema(description = "Content boost factor (0.0-1.0). Higher values give more weight to document body content. Default: 0.7")
        private Float contentBoost;

        @Schema(description = "Admin only: Simulate search as another user. Requires admin privileges.")
        private String simulateAsUserId;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getTopK() {
            return topK;
        }

        public void setTopK(Integer topK) {
            this.topK = topK;
        }

        public Float getMinScore() {
            return minScore;
        }

        public void setMinScore(Float minScore) {
            this.minScore = minScore;
        }

        public String getFolderId() {
            return folderId;
        }

        public void setFolderId(String folderId) {
            this.folderId = folderId;
        }

        public Float getPropertyBoost() {
            return propertyBoost;
        }

        public void setPropertyBoost(Float propertyBoost) {
            this.propertyBoost = propertyBoost;
        }

        public Float getContentBoost() {
            return contentBoost;
        }

        public void setContentBoost(Float contentBoost) {
            this.contentBoost = contentBoost;
        }

        public String getSimulateAsUserId() {
            return simulateAsUserId;
        }

        public void setSimulateAsUserId(String simulateAsUserId) {
            this.simulateAsUserId = simulateAsUserId;
        }
    }

    @Schema(description = "RAG search response")
    public static class RAGSearchResponse {
        @Schema(description = "Original query")
        private String query;

        @Schema(description = "Total number of results")
        private int totalResults;

        @Schema(description = "Search results")
        private List<VectorSearchResult> results;

        @Schema(description = "Actual topK used (may be clamped to server limit)")
        private Integer topK;

        @Schema(description = "Server's maximum topK limit")
        private Integer topKLimit;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public int getTotalResults() {
            return totalResults;
        }

        public void setTotalResults(int totalResults) {
            this.totalResults = totalResults;
        }

        public List<VectorSearchResult> getResults() {
            return results;
        }

        public void setResults(List<VectorSearchResult> results) {
            this.results = results;
        }

        public Integer getTopK() {
            return topK;
        }

        public void setTopK(Integer topK) {
            this.topK = topK;
        }

        public Integer getTopKLimit() {
            return topKLimit;
        }

        public void setTopKLimit(Integer topKLimit) {
            this.topKLimit = topKLimit;
        }
    }


    @Schema(description = "Similar documents response")
    public static class SimilarDocumentsResponse {
        @Schema(description = "Source document ID")
        private String sourceDocumentId;

        @Schema(description = "Total number of similar documents found")
        private int totalResults;

        @Schema(description = "List of similar documents")
        private List<VectorSearchResult> results;

        @Schema(description = "Requested topK value")
        private int topK;

        @Schema(description = "Minimum similarity score threshold used")
        private float minScore;

        public String getSourceDocumentId() {
            return sourceDocumentId;
        }

        public void setSourceDocumentId(String sourceDocumentId) {
            this.sourceDocumentId = sourceDocumentId;
        }

        public int getTotalResults() {
            return totalResults;
        }

        public void setTotalResults(int totalResults) {
            this.totalResults = totalResults;
        }

        public List<VectorSearchResult> getResults() {
            return results;
        }

        public void setResults(List<VectorSearchResult> results) {
            this.results = results;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public float getMinScore() {
            return minScore;
        }

        public void setMinScore(float minScore) {
            this.minScore = minScore;
        }
    }
}
