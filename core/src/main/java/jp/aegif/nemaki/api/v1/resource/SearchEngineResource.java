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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/search-engine")
@Tag(name = "search-engine", description = "Search engine (Solr) management operations")
@Produces(MediaType.APPLICATION_JSON)
public class SearchEngineResource {
    
    private static final Logger logger = Logger.getLogger(SearchEngineResource.class.getName());
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Autowired
    private ContentService contentService;
    
    @Autowired(required = false)
    private SolrUtil solrUtil;
    
    @Autowired(required = false)
    private SolrIndexMaintenanceService solrIndexMaintenanceService;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Path("/status")
    @Operation(
            summary = "Get reindex status",
            description = "Returns the current status of any ongoing reindex operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Reindex status information"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getStatus(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting search engine status for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            ReindexStatus reindexStatus = solrIndexMaintenanceService.getReindexStatus(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", reindexStatus.getRepositoryId());
            response.put("status", reindexStatus.getStatus());
            response.put("totalDocuments", reindexStatus.getTotalDocuments());
            response.put("indexedCount", reindexStatus.getIndexedCount());
            response.put("errorCount", reindexStatus.getErrorCount());
            response.put("silentDropCount", reindexStatus.getSilentDropCount());
            response.put("reindexedCount", reindexStatus.getReindexedCount());
            response.put("startTime", reindexStatus.getStartTime());
            response.put("endTime", reindexStatus.getEndTime());
            response.put("currentFolder", reindexStatus.getCurrentFolder());
            response.put("errorMessage", reindexStatus.getErrorMessage());
            
            List<String> errors = reindexStatus.getErrors();
            response.put("errors", errors != null ? errors : new ArrayList<>());
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting search engine status: " + e.getMessage());
            throw ApiException.internalError("Failed to get search engine status: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/health")
    @Operation(
            summary = "Check index health",
            description = "Compares Solr index with CouchDB to identify missing or orphaned documents"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Index health information"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response checkHealth(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Checking search engine health for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            IndexHealthStatus healthStatus = solrIndexMaintenanceService.checkIndexHealth(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", healthStatus.getRepositoryId());
            response.put("solrDocumentCount", healthStatus.getSolrDocumentCount());
            response.put("couchDbDocumentCount", healthStatus.getCouchDbDocumentCount());
            response.put("missingInSolr", healthStatus.getMissingInSolr());
            response.put("orphanedInSolr", healthStatus.getOrphanedInSolr());
            response.put("healthy", healthStatus.isHealthy());
            response.put("message", healthStatus.getMessage());
            response.put("checkTime", healthStatus.getCheckTime());
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error checking search engine health: " + e.getMessage());
            throw ApiException.internalError("Failed to check search engine health: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/reindex")
    @Operation(
            summary = "Start full reindex",
            description = "Starts a full reindex of all documents in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Reindex started successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response startReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Starting full reindex for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean started = solrIndexMaintenanceService.startFullReindex(repositoryId);
            if (!started) {
                throw ApiException.conflict("Reindex already in progress for this repository");
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("message", "Full reindex started");
            response.put("started", true);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error starting reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to start reindex: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/reindex/folder/{folderId}")
    @Operation(
            summary = "Reindex a folder",
            description = "Reindexes all documents in the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Folder reindex started successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response reindexFolder(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID to reindex", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Include subfolders recursively", example = "true")
            @QueryParam("recursive") Boolean recursive) {
        
        logger.info("API v1: Starting folder reindex for folder " + folderId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean isRecursive = recursive == null || recursive;
            boolean started = solrIndexMaintenanceService.startFolderReindex(repositoryId, folderId, isRecursive);
            if (!started) {
                throw ApiException.conflict("Reindex already in progress for this repository");
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("folderId", folderId);
            response.put("recursive", isRecursive);
            response.put("message", "Folder reindex started");
            response.put("started", true);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error starting folder reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to start folder reindex: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/reindex/document/{objectId}")
    @Operation(
            summary = "Reindex a single document",
            description = "Reindexes a single document in the search engine"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document reindexed successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response reindexDocument(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID to reindex", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Reindexing document " + objectId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean success = solrIndexMaintenanceService.reindexDocument(repositoryId, objectId);
            if (!success) {
                throw ApiException.internalError("Failed to reindex document: " + objectId);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("objectId", objectId);
            response.put("message", "Document reindexed successfully");
            response.put("success", true);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error reindexing document: " + e.getMessage());
            throw ApiException.internalError("Failed to reindex document: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/cancel")
    @Operation(
            summary = "Cancel reindex",
            description = "Cancels any ongoing reindex operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Reindex cancelled"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response cancelReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Cancelling reindex for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean cancelled = solrIndexMaintenanceService.cancelReindex(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("cancelled", cancelled);
            response.put("message", cancelled ? "Reindex cancelled" : "No reindex in progress");
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error cancelling reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to cancel reindex: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/url")
    @Operation(
            summary = "Get Solr URL",
            description = "Returns the configured Solr server URL"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Solr URL"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getSolrUrl(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting Solr URL");
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrUtil == null) {
                throw ApiException.internalError("Solr utility is not available");
            }
            
            String solrUrl = solrUtil.getSolrUrl();
            
            Map<String, Object> response = new HashMap<>();
            response.put("url", solrUrl);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting Solr URL: " + e.getMessage());
            throw ApiException.internalError("Failed to get Solr URL: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(
            summary = "Execute Solr query",
            description = "Executes a raw Solr query for debugging purposes"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Query results"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response executeSolrQuery(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Solr query string", required = true)
            @QueryParam("q") String query,
            @Parameter(description = "Start offset", example = "0")
            @QueryParam("start") Integer start,
            @Parameter(description = "Number of rows to return", example = "10")
            @QueryParam("rows") Integer rows,
            @Parameter(description = "Sort order")
            @QueryParam("sort") String sort,
            @Parameter(description = "Fields to return")
            @QueryParam("fl") String fields) {
        
        logger.info("API v1: Executing Solr query: " + query);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            int startOffset = start != null ? start : 0;
            int numRows = rows != null ? rows : 10;
            
            SolrQueryResult queryResult = solrIndexMaintenanceService.executeSolrQuery(
                repositoryId, query, startOffset, numRows, sort, fields);
            
            if (queryResult.getErrorMessage() != null) {
                throw ApiException.invalidArgument(queryResult.getErrorMessage());
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("numFound", queryResult.getNumFound());
            response.put("start", queryResult.getStart());
            response.put("queryTime", queryResult.getQueryTime());
            response.put("docs", queryResult.getDocs() != null ? queryResult.getDocs() : new ArrayList<>());
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error executing Solr query: " + e.getMessage());
            throw ApiException.internalError("Failed to execute Solr query: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (!repositoryService.hasThisRepositoryId(repositoryId)) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }
    
    private void checkAdminAccess(String repositoryId) {
        if (!isCurrentUserAdmin(repositoryId)) {
            throw ApiException.permissionDenied("Admin access required for this operation");
        }
    }
    
    private boolean isCurrentUserAdmin(String repositoryId) {
        String userId = getCurrentUserId();
        if ("admin".equals(userId)) {
            return true;
        }
        
        UserItem userItem = contentService.getUserItemById(repositoryId, userId);
        return userItem != null && Boolean.TRUE.equals(userItem.isAdmin());
    }
    
    private String getCurrentUserId() {
        return AuthenticationUtil.getUserIdFromRequest(httpRequest);
    }
}
