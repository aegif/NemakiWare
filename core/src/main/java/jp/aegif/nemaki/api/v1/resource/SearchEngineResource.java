package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGReindexStatus;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGHealthStatus;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;

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
    private SolrUtil solrUtil;
    
    @Autowired
    private SolrIndexMaintenanceService solrIndexMaintenanceService;

    @Autowired
    private RAGIndexMaintenanceService ragIndexMaintenanceService;

    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for search engine management operations");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can perform search engine management operations");
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
                    description = "Solr URL retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SolrUrlResponse.class)
                    )
            )
    })
    public Response getSolrUrl(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting Solr URL for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrUtil == null) {
                throw ApiException.internalError("Solr utility is not available");
            }
            
            SolrUrlResponse response = new SolrUrlResponse();
            response.setUrl(solrUtil.getSolrUrl());
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/url"));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            links.put("health", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/health"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting Solr URL: " + e.getMessage());
            throw ApiException.internalError("Failed to get Solr URL: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/status")
    @Operation(
            summary = "Get reindex status",
            description = "Returns the current status of any ongoing reindex operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Reindex status retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ReindexStatusResponse.class)
                    )
            )
    })
    public Response getReindexStatus(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting reindex status for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            ReindexStatus status = solrIndexMaintenanceService.getReindexStatus(repositoryId);
            
            ReindexStatusResponse response = new ReindexStatusResponse();
            response.setRepositoryId(status.getRepositoryId());
            response.setStatus(status.getStatus());
            response.setTotalDocuments(status.getTotalDocuments());
            response.setIndexedCount(status.getIndexedCount());
            response.setErrorCount(status.getErrorCount());
            response.setSilentDropCount(status.getSilentDropCount());
            response.setReindexedCount(status.getReindexedCount());
            response.setStartTime(status.getStartTime());
            response.setEndTime(status.getEndTime());
            response.setCurrentFolder(status.getCurrentFolder());
            response.setErrorMessage(status.getErrorMessage());
            response.setErrors(status.getErrors() != null ? status.getErrors() : new ArrayList<>());
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            links.put("cancel", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/cancel"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting reindex status: " + e.getMessage());
            throw ApiException.internalError("Failed to get reindex status: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/health")
    @Operation(
            summary = "Check index health",
            description = "Checks the health of the search index by comparing document counts between Solr and CouchDB"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Health check completed",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = IndexHealthResponse.class)
                    )
            )
    })
    public Response checkHealth(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Checking index health for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            IndexHealthStatus healthStatus = solrIndexMaintenanceService.checkIndexHealth(repositoryId);
            
            IndexHealthResponse response = new IndexHealthResponse();
            response.setRepositoryId(healthStatus.getRepositoryId());
            response.setSolrDocumentCount(healthStatus.getSolrDocumentCount());
            response.setCouchDbDocumentCount(healthStatus.getCouchDbDocumentCount());
            response.setMissingInSolr(healthStatus.getMissingInSolr());
            response.setOrphanedInSolr(healthStatus.getOrphanedInSolr());
            response.setHealthy(healthStatus.isHealthy());
            response.setMessage(healthStatus.getMessage());
            response.setCheckTime(healthStatus.getCheckTime());
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/health"));
            links.put("reindex", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/reindex"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error checking index health: " + e.getMessage());
            throw ApiException.internalError("Failed to check index health: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/init")
    @Operation(
            summary = "Initialize search index",
            description = "Initializes the Solr search index for the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Index initialization started",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            )
    })
    public Response initializeIndex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Initializing search index for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrUtil == null) {
                throw ApiException.internalError("Solr utility is not available");
            }
            
            String solrUrl = solrUtil.getSolrUrl();
            String encodedRepoId = java.net.URLEncoder.encode(repositoryId, "UTF-8");
            String url = solrUrl + "admin/cores?core=nemaki&action=init&repositoryId=" + encodedRepoId;
            
            org.apache.hc.client5.http.classic.HttpClient httpClient = 
                org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create().build();
            org.apache.hc.client5.http.classic.methods.HttpGet httpGet =
                new org.apache.hc.client5.http.classic.methods.HttpGet(url);

            httpClient.execute(httpGet, response -> {
                int responseStatus = response.getCode();
                if (org.apache.hc.core5.http.HttpStatus.SC_OK != responseStatus) {
                    throw new RuntimeException("Solr server connection failed with status: " + responseStatus);
                }
                return org.apache.hc.core5.http.io.entity.EntityUtils.toString(response.getEntity(), "UTF-8");
            });

            OperationResponse response = new OperationResponse();
            response.setSuccess(true);
            response.setMessage("Search index initialization completed");
            response.setRepositoryId(repositoryId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/init"));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error initializing search index: " + e.getMessage());
            throw ApiException.internalError("Failed to initialize search index: " + e.getMessage(), e);
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
                    responseCode = "202",
                    description = "Reindex started",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response startFullReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Starting full reindex for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean started = solrIndexMaintenanceService.startFullReindex(repositoryId);
            if (!started) {
                throw ApiException.conflict("Reindex already in progress for this repository");
            }
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(true);
            response.setMessage("Full reindex started");
            response.setRepositoryId(repositoryId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/reindex"));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            links.put("cancel", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/cancel"));
            response.setLinks(links);
            
            return Response.status(Response.Status.ACCEPTED).entity(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error starting full reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to start full reindex: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/reindex/folder/{folderId}")
    @Operation(
            summary = "Reindex folder",
            description = "Reindexes all documents in the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "202",
                    description = "Folder reindex started",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress",
                    content = @io.swagger.v3.oas.annotations.media.Content(
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
            @Parameter(description = "Include subfolders recursively")
            @QueryParam("recursive") @DefaultValue("true") boolean recursive) {
        
        logger.info("API v1: Starting folder reindex for " + folderId + " in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean started = solrIndexMaintenanceService.startFolderReindex(repositoryId, folderId, recursive);
            if (!started) {
                throw ApiException.conflict("Reindex already in progress for this repository");
            }
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(true);
            response.setMessage("Folder reindex started");
            response.setRepositoryId(repositoryId);
            response.setFolderId(folderId);
            response.setRecursive(recursive);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/reindex/folder/" + folderId));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            links.put("folder", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + folderId));
            response.setLinks(links);
            
            return Response.status(Response.Status.ACCEPTED).entity(response).build();
            
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
            summary = "Reindex single document",
            description = "Reindexes a single document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document reindexed successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response reindexDocument(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document object ID", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Reindexing document " + objectId + " in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean success = solrIndexMaintenanceService.reindexDocument(repositoryId, objectId);
            if (!success) {
                throw ApiException.internalError("Failed to reindex document: " + objectId);
            }
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(true);
            response.setMessage("Document reindexed successfully");
            response.setRepositoryId(repositoryId);
            response.setObjectId(objectId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/reindex/document/" + objectId));
            links.put("document", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + objectId));
            response.setLinks(links);
            
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
                    description = "Cancel request processed",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            )
    })
    public Response cancelReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Cancelling reindex for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean cancelled = solrIndexMaintenanceService.cancelReindex(repositoryId);
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(cancelled);
            response.setMessage(cancelled ? "Reindex cancelled" : "No reindex in progress to cancel");
            response.setRepositoryId(repositoryId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/cancel"));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/status"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error cancelling reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to cancel reindex: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/delete/{objectId}")
    @Operation(
            summary = "Delete from index",
            description = "Removes a document from the search index"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document removed from index",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            )
    })
    public Response deleteFromIndex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID to remove from index", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Deleting " + objectId + " from index in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean success = solrIndexMaintenanceService.deleteFromIndex(repositoryId, objectId);
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(success);
            response.setMessage(success ? "Document removed from index" : "Failed to remove document from index");
            response.setRepositoryId(repositoryId);
            response.setObjectId(objectId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/delete/" + objectId));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting from index: " + e.getMessage());
            throw ApiException.internalError("Failed to delete from index: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/clear")
    @Operation(
            summary = "Clear index",
            description = "Clears all documents from the search index for this repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Index cleared",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            )
    })
    public Response clearIndex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Clearing index for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean success = solrIndexMaintenanceService.clearIndex(repositoryId);
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(success);
            response.setMessage(success ? "Index cleared successfully" : "Failed to clear index");
            response.setRepositoryId(repositoryId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/clear"));
            links.put("reindex", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/reindex"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error clearing index: " + e.getMessage());
            throw ApiException.internalError("Failed to clear index: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/optimize")
    @Operation(
            summary = "Optimize index",
            description = "Optimizes the search index for better performance"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Index optimized",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            )
    })
    public Response optimizeIndex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Optimizing index for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            boolean success = solrIndexMaintenanceService.optimizeIndex(repositoryId);
            
            OperationResponse response = new OperationResponse();
            response.setSuccess(success);
            response.setMessage(success ? "Index optimized successfully" : "Failed to optimize index");
            response.setRepositoryId(repositoryId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/optimize"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error optimizing index: " + e.getMessage());
            throw ApiException.internalError("Failed to optimize index: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/query")
    @Operation(
            summary = "Execute Solr query",
            description = "Executes a raw Solr query for debugging and administration purposes. Parameters are passed as query parameters."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Query executed successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = QueryResponse.class)
                    )
            )
    })
    public Response executeSolrQuery(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Solr query string", required = true)
            @QueryParam("q") String query,
            @Parameter(description = "Start offset")
            @QueryParam("start") @DefaultValue("0") int start,
            @Parameter(description = "Number of rows to return")
            @QueryParam("rows") @DefaultValue("10") int rows,
            @Parameter(description = "Sort order")
            @QueryParam("sort") String sort,
            @Parameter(description = "Fields to return")
            @QueryParam("fl") String fields) {
        
        logger.info("API v1: Executing Solr query in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (solrIndexMaintenanceService == null) {
                throw ApiException.internalError("Solr index maintenance service is not available");
            }
            
            SolrQueryResult queryResult = solrIndexMaintenanceService.executeSolrQuery(repositoryId, query, start, rows, sort, fields);
            
            if (queryResult.getErrorMessage() != null) {
                throw ApiException.invalidArgument("Query error: " + queryResult.getErrorMessage());
            }
            
            QueryResponse response = new QueryResponse();
            response.setNumFound(queryResult.getNumFound());
            response.setStart(queryResult.getStart());
            response.setQueryTime(queryResult.getQueryTime());
            response.setDocs(queryResult.getDocs() != null ? queryResult.getDocs() : new ArrayList<>());
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/query"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error executing Solr query: " + e.getMessage());
            throw ApiException.internalError("Failed to execute query: " + e.getMessage(), e);
        }
    }
    
    @Schema(description = "Solr URL response")
    public static class SolrUrlResponse {
        @Schema(description = "Solr server URL")
        private String url;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Reindex status response")
    public static class ReindexStatusResponse {
        @Schema(description = "Repository ID")
        private String repositoryId;
        
        @Schema(description = "Current status (idle, running, completed, failed)")
        private String status;
        
        @Schema(description = "Total number of documents to index")
        private long totalDocuments;
        
        @Schema(description = "Number of documents indexed so far")
        private long indexedCount;
        
        @Schema(description = "Number of errors encountered")
        private long errorCount;
        
        @Schema(description = "Number of documents silently dropped")
        private long silentDropCount;
        
        @Schema(description = "Number of documents reindexed")
        private long reindexedCount;
        
        @Schema(description = "Start time of the operation (epoch millis)")
        private long startTime;
        
        @Schema(description = "End time of the operation (epoch millis)")
        private long endTime;
        
        @Schema(description = "Current folder being processed")
        private String currentFolder;
        
        @Schema(description = "Error message if failed")
        private String errorMessage;
        
        @Schema(description = "List of error messages")
        private List<String> errors;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(long totalDocuments) { this.totalDocuments = totalDocuments; }
        public long getIndexedCount() { return indexedCount; }
        public void setIndexedCount(long indexedCount) { this.indexedCount = indexedCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public long getSilentDropCount() { return silentDropCount; }
        public void setSilentDropCount(long silentDropCount) { this.silentDropCount = silentDropCount; }
        public long getReindexedCount() { return reindexedCount; }
        public void setReindexedCount(long reindexedCount) { this.reindexedCount = reindexedCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getCurrentFolder() { return currentFolder; }
        public void setCurrentFolder(String currentFolder) { this.currentFolder = currentFolder; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Index health response")
    public static class IndexHealthResponse {
        @Schema(description = "Repository ID")
        private String repositoryId;
        
        @Schema(description = "Number of documents in Solr")
        private long solrDocumentCount;
        
        @Schema(description = "Number of documents in CouchDB")
        private long couchDbDocumentCount;
        
        @Schema(description = "Number of documents missing in Solr")
        private long missingInSolr;
        
        @Schema(description = "Number of orphaned documents in Solr")
        private long orphanedInSolr;
        
        @Schema(description = "Whether the index is healthy")
        private boolean healthy;
        
        @Schema(description = "Health check message")
        private String message;
        
        @Schema(description = "Time of the health check (epoch millis)")
        private long checkTime;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public long getSolrDocumentCount() { return solrDocumentCount; }
        public void setSolrDocumentCount(long solrDocumentCount) { this.solrDocumentCount = solrDocumentCount; }
        public long getCouchDbDocumentCount() { return couchDbDocumentCount; }
        public void setCouchDbDocumentCount(long couchDbDocumentCount) { this.couchDbDocumentCount = couchDbDocumentCount; }
        public long getMissingInSolr() { return missingInSolr; }
        public void setMissingInSolr(long missingInSolr) { this.missingInSolr = missingInSolr; }
        public long getOrphanedInSolr() { return orphanedInSolr; }
        public void setOrphanedInSolr(long orphanedInSolr) { this.orphanedInSolr = orphanedInSolr; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getCheckTime() { return checkTime; }
        public void setCheckTime(long checkTime) { this.checkTime = checkTime; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Operation response")
    public static class OperationResponse {
        @Schema(description = "Whether the operation was successful")
        private boolean success;
        
        @Schema(description = "Status message")
        private String message;
        
        @Schema(description = "Repository ID")
        private String repositoryId;
        
        @Schema(description = "Object ID (if applicable)")
        private String objectId;
        
        @Schema(description = "Folder ID (if applicable)")
        private String folderId;
        
        @Schema(description = "Whether operation was recursive (if applicable)")
        private Boolean recursive;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId; }
        public String getFolderId() { return folderId; }
        public void setFolderId(String folderId) { this.folderId = folderId; }
        public Boolean getRecursive() { return recursive; }
        public void setRecursive(Boolean recursive) { this.recursive = recursive; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Query response")
    public static class QueryResponse {
        @Schema(description = "Total number of matching documents")
        private long numFound;
        
        @Schema(description = "Start offset")
        private long start;
        
        @Schema(description = "Query execution time in milliseconds")
        private long queryTime;
        
        @Schema(description = "List of matching documents")
        private List<Map<String, Object>> docs;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public long getNumFound() { return numFound; }
        public void setNumFound(long numFound) { this.numFound = numFound; }
        public long getStart() { return start; }
        public void setStart(long start) { this.start = start; }
        public long getQueryTime() { return queryTime; }
        public void setQueryTime(long queryTime) { this.queryTime = queryTime; }
        public List<Map<String, Object>> getDocs() { return docs; }
        public void setDocs(List<Map<String, Object>> docs) { this.docs = docs; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }

    // ==================== RAG Endpoints ====================

    @GET
    @Path("/rag/enabled")
    @Operation(
            summary = "Check if RAG is enabled",
            description = "Returns whether RAG (Retrieval-Augmented Generation) indexing and search is enabled and available"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG enabled status retrieved successfully"
            )
    })
    public Response isRAGEnabled(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Checking RAG enabled status for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            Map<String, Object> response = new HashMap<>();
            response.put("enabled", ragIndexMaintenanceService.isRAGEnabled());
            response.put("repositoryId", repositoryId);
            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error checking RAG enabled status: " + e.getMessage());
            throw ApiException.internalError("Failed to check RAG enabled status: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/rag/status")
    @Operation(
            summary = "Get RAG reindex status",
            description = "Returns the current status of any ongoing RAG reindex operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG reindex status retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RAGReindexStatusResponse.class)
                    )
            )
    })
    public Response getRAGReindexStatus(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Getting RAG reindex status for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            RAGReindexStatus status = ragIndexMaintenanceService.getRAGReindexStatus(repositoryId);
            RAGReindexStatusResponse response = new RAGReindexStatusResponse();
            response.setRepositoryId(status.getRepositoryId());
            response.setStatus(status.getStatus());
            response.setTotalDocuments(status.getTotalDocuments());
            response.setIndexedCount(status.getIndexedCount());
            response.setSkippedCount(status.getSkippedCount());
            response.setErrorCount(status.getErrorCount());
            response.setStartTime(status.getStartTime());
            response.setEndTime(status.getEndTime());
            response.setCurrentDocument(status.getCurrentDocument());
            response.setErrorMessage(status.getErrorMessage());
            response.setErrors(status.getErrors());

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/status"));
            links.put("health", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/health"));
            links.put("reindex", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/reindex"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error getting RAG reindex status: " + e.getMessage());
            throw ApiException.internalError("Failed to get RAG reindex status: " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/rag/health")
    @Operation(
            summary = "Check RAG index health",
            description = "Performs a health check on the RAG index, showing document and chunk counts"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG health check completed successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RAGHealthResponse.class)
                    )
            )
    })
    public Response checkRAGHealth(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Checking RAG health for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            RAGHealthStatus healthStatus = ragIndexMaintenanceService.checkRAGHealth(repositoryId);
            RAGHealthResponse response = new RAGHealthResponse();
            response.setRepositoryId(healthStatus.getRepositoryId());
            response.setRagDocumentCount(healthStatus.getRagDocumentCount());
            response.setRagChunkCount(healthStatus.getRagChunkCount());
            response.setEligibleDocuments(healthStatus.getEligibleDocuments());
            response.setEnabled(healthStatus.isEnabled());
            response.setHealthy(healthStatus.isHealthy());
            response.setMessage(healthStatus.getMessage());
            response.setCheckTime(healthStatus.getCheckTime());

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/health"));
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/status"));
            links.put("reindex", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/reindex"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error checking RAG health: " + e.getMessage());
            throw ApiException.internalError("Failed to check RAG health: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/rag/reindex")
    @Operation(
            summary = "Start full RAG reindex",
            description = "Starts a full RAG reindex operation for all documents in the repository. " +
                    "The operation runs asynchronously. Use the status endpoint to monitor progress."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG reindex started successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "RAG is not enabled"
            )
    })
    public Response startFullRAGReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Starting full RAG reindex for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            if (!ragIndexMaintenanceService.isRAGEnabled()) {
                throw ApiException.serviceUnavailable("RAG is not enabled or available");
            }

            boolean started = ragIndexMaintenanceService.startFullRAGReindex(repositoryId);

            OperationResponse response = new OperationResponse();
            response.setRepositoryId(repositoryId);

            if (started) {
                response.setSuccess(true);
                response.setMessage("RAG reindex started successfully");
            } else {
                response.setSuccess(false);
                response.setMessage("RAG reindex is already running");
            }

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/status"));
            links.put("cancel", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/cancel"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error starting RAG reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to start RAG reindex: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/rag/reindex/folder/{folderId}")
    @Operation(
            summary = "Start folder RAG reindex",
            description = "Starts a RAG reindex operation for all documents in the specified folder. " +
                    "The operation runs asynchronously."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Folder RAG reindex started successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OperationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Reindex already in progress"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "RAG is not enabled"
            )
    })
    public Response startFolderRAGReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID to reindex", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Whether to include subfolders")
            @QueryParam("recursive") @DefaultValue("true") boolean recursive) {

        logger.info("API v1: Starting folder RAG reindex for repository " + repositoryId + ", folder " + folderId);

        checkAdminAuthorization();

        try {
            if (!ragIndexMaintenanceService.isRAGEnabled()) {
                throw ApiException.serviceUnavailable("RAG is not enabled or available");
            }

            boolean started = ragIndexMaintenanceService.startFolderRAGReindex(repositoryId, folderId, recursive);

            OperationResponse response = new OperationResponse();
            response.setRepositoryId(repositoryId);
            response.setFolderId(folderId);
            response.setRecursive(recursive);

            if (started) {
                response.setSuccess(true);
                response.setMessage("Folder RAG reindex started successfully");
            } else {
                response.setSuccess(false);
                response.setMessage("RAG reindex is already running");
            }

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/status"));
            links.put("cancel", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/cancel"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error starting folder RAG reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to start folder RAG reindex: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/rag/cancel")
    @Operation(
            summary = "Cancel RAG reindex",
            description = "Cancels a running RAG reindex operation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG reindex cancelled successfully"
            )
    })
    public Response cancelRAGReindex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Cancelling RAG reindex for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            boolean cancelled = ragIndexMaintenanceService.cancelRAGReindex(repositoryId);

            OperationResponse response = new OperationResponse();
            response.setRepositoryId(repositoryId);

            if (cancelled) {
                response.setSuccess(true);
                response.setMessage("RAG reindex cancelled");
            } else {
                response.setSuccess(false);
                response.setMessage("No RAG reindex operation to cancel");
            }

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("status", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/status"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error cancelling RAG reindex: " + e.getMessage());
            throw ApiException.internalError("Failed to cancel RAG reindex: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/rag/clear")
    @Operation(
            summary = "Clear RAG index",
            description = "Clears all RAG index data for the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "RAG index cleared successfully"
            )
    })
    public Response clearRAGIndex(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {

        logger.info("API v1: Clearing RAG index for repository " + repositoryId);

        checkAdminAuthorization();

        try {
            boolean cleared = ragIndexMaintenanceService.clearRAGIndex(repositoryId);

            OperationResponse response = new OperationResponse();
            response.setRepositoryId(repositoryId);

            if (cleared) {
                response.setSuccess(true);
                response.setMessage("RAG index cleared successfully");
            } else {
                response.setSuccess(false);
                response.setMessage("Failed to clear RAG index");
            }

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("health", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/health"));
            links.put("reindex", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/reindex"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error clearing RAG index: " + e.getMessage());
            throw ApiException.internalError("Failed to clear RAG index: " + e.getMessage(), e);
        }
    }

    @POST
    @Path("/rag/reindex/document/{objectId}")
    @Operation(
            summary = "RAG reindex single document",
            description = "Reindexes a single document in the RAG index"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document RAG reindexed successfully"
            )
    })
    public Response reindexDocumentRAG(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document object ID to reindex", required = true)
            @PathParam("objectId") String objectId) {

        logger.info("API v1: RAG reindexing document " + objectId + " in repository " + repositoryId);

        checkAdminAuthorization();

        try {
            if (!ragIndexMaintenanceService.isRAGEnabled()) {
                throw ApiException.serviceUnavailable("RAG is not enabled or available");
            }

            boolean reindexed = ragIndexMaintenanceService.reindexDocument(repositoryId, objectId);

            OperationResponse response = new OperationResponse();
            response.setRepositoryId(repositoryId);
            response.setObjectId(objectId);

            if (reindexed) {
                response.setSuccess(true);
                response.setMessage("Document RAG reindexed successfully");
            } else {
                response.setSuccess(false);
                response.setMessage("Failed to RAG reindex document (may be a folder or unsupported type)");
            }

            Map<String, LinkInfo> links = new HashMap<>();
            links.put("health", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/search-engine/rag/health"));
            response.setLinks(links);

            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error RAG reindexing document: " + e.getMessage());
            throw ApiException.internalError("Failed to RAG reindex document: " + e.getMessage(), e);
        }
    }

    // ==================== RAG Response DTOs ====================

    @Schema(description = "RAG reindex status response")
    public static class RAGReindexStatusResponse {
        @Schema(description = "Repository ID")
        private String repositoryId;

        @Schema(description = "Status of the reindex operation (idle, running, completed, error, cancelled)")
        private String status;

        @Schema(description = "Total number of documents to process")
        private long totalDocuments;

        @Schema(description = "Number of documents indexed")
        private long indexedCount;

        @Schema(description = "Number of documents skipped (unsupported MIME type)")
        private long skippedCount;

        @Schema(description = "Number of errors")
        private long errorCount;

        @Schema(description = "Start time (epoch millis)")
        private long startTime;

        @Schema(description = "End time (epoch millis)")
        private long endTime;

        @Schema(description = "Currently processing document")
        private String currentDocument;

        @Schema(description = "Error message if status is error")
        private String errorMessage;

        @Schema(description = "List of error messages")
        private List<String> errors;

        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getTotalDocuments() { return totalDocuments; }
        public void setTotalDocuments(long totalDocuments) { this.totalDocuments = totalDocuments; }
        public long getIndexedCount() { return indexedCount; }
        public void setIndexedCount(long indexedCount) { this.indexedCount = indexedCount; }
        public long getSkippedCount() { return skippedCount; }
        public void setSkippedCount(long skippedCount) { this.skippedCount = skippedCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public String getCurrentDocument() { return currentDocument; }
        public void setCurrentDocument(String currentDocument) { this.currentDocument = currentDocument; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }

    @Schema(description = "RAG health response")
    public static class RAGHealthResponse {
        @Schema(description = "Repository ID")
        private String repositoryId;

        @Schema(description = "Number of documents with RAG vectors")
        private long ragDocumentCount;

        @Schema(description = "Total number of chunks in RAG index")
        private long ragChunkCount;

        @Schema(description = "Number of documents eligible for RAG indexing")
        private long eligibleDocuments;

        @Schema(description = "Whether RAG is enabled")
        private boolean enabled;

        @Schema(description = "Whether the RAG index is healthy")
        private boolean healthy;

        @Schema(description = "Health check message")
        private String message;

        @Schema(description = "Time of the health check (epoch millis)")
        private long checkTime;

        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;

        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public long getRagDocumentCount() { return ragDocumentCount; }
        public void setRagDocumentCount(long ragDocumentCount) { this.ragDocumentCount = ragDocumentCount; }
        public long getRagChunkCount() { return ragChunkCount; }
        public void setRagChunkCount(long ragChunkCount) { this.ragChunkCount = ragChunkCount; }
        public long getEligibleDocuments() { return eligibleDocuments; }
        public void setEligibleDocuments(long eligibleDocuments) { this.eligibleDocuments = eligibleDocuments; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getCheckTime() { return checkTime; }
        public void setCheckTime(long checkTime) { this.checkTime = checkTime; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
}
