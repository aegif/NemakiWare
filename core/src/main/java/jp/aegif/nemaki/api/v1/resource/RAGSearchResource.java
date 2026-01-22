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
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.rag.search.VectorSearchException;
import jp.aegif.nemaki.rag.search.VectorSearchResult;
import jp.aegif.nemaki.rag.search.VectorSearchService;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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

    private static final Logger logger = Logger.getLogger(RAGSearchResource.class.getName());

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private RepositoryService repositoryService;

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

        logger.info("=== RAG Search called ===");
        logger.info("Repository: " + repositoryId);
        logger.info("Query: " + (request != null ? request.getQuery() : "null"));
        logger.info("VectorSearchService: " + (vectorSearchService != null ? "present" : "null"));

        try {
            validateRepository(repositoryId);

            if (!vectorSearchService.isEnabled()) {
                throw ApiException.serviceUnavailable("RAG semantic search is not available");
            }

            if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                throw ApiException.invalidArgument("Query text is required");
            }

            // Get current user
            CallContext context = getCallContext();
            String userId = context.getUsername();

            // Set defaults
            int topK = request.getTopK() != null ? request.getTopK() : 10;
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

            // Build response
            RAGSearchResponse response = new RAGSearchResponse();
            response.setQuery(request.getQuery());
            response.setResults(results);
            response.setTotalResults(results.size());

            return Response.ok(response).build();

        } catch (ApiException e) {
            throw e;
        } catch (VectorSearchException e) {
            logger.warning("Vector search failed: " + e.getMessage());
            throw ApiException.internalError("Vector search failed: " + e.getMessage());
        } catch (Exception e) {
            logger.log(java.util.logging.Level.SEVERE, "Unexpected error in RAG search", e);
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
            @QueryParam("contentBoost") Float contentBoost) {

        RAGSearchRequest request = new RAGSearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setMinScore(minScore);
        request.setFolderId(folderId);
        request.setPropertyBoost(propertyBoost);
        request.setContentBoost(contentBoost);

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
        health.put("enabled", vectorSearchService.isEnabled());
        health.put("status", vectorSearchService.isEnabled() ? "healthy" : "unavailable");

        return Response.ok(health).build();
    }

    private boolean hasCustomBoost(RAGSearchRequest request) {
        return request.getPropertyBoost() != null || request.getContentBoost() != null;
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
    }

    @Schema(description = "RAG search response")
    public static class RAGSearchResponse {
        @Schema(description = "Original query")
        private String query;

        @Schema(description = "Total number of results")
        private int totalResults;

        @Schema(description = "Search results")
        private List<VectorSearchResult> results;

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
    }
}
