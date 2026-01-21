package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
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
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/cache")
@Tag(name = "cache", description = "Cache management operations")
@Produces(MediaType.APPLICATION_JSON)
public class CacheResource {
    
    private static final Logger logger = Logger.getLogger(CacheResource.class.getName());
    
    @Autowired
    private NemakiCachePool nemakiCachePool;
    
    @Autowired
    private ThreadLockService threadLockService;
    
    @Autowired
    private TypeManager typeManager;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for cache management operations");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can perform cache management operations");
        }
    }
    
    @DELETE
    @Path("/objects/{objectId}")
    @Operation(
            summary = "Invalidate object cache",
            description = "Removes the specified object from the cache. Optionally only invalidates if the cache is older than a specified date."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Cache invalidation result",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = CacheInvalidationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid date format",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response invalidateObjectCache(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID to invalidate", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Only invalidate if cache is older than this date (ISO 8601 format)")
            @QueryParam("beforeDate") String beforeDate) {
        
        logger.info("API v1: Invalidating cache for object " + objectId + " in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        Lock lock = threadLockService.getWriteLock(repositoryId, objectId);
        try {
            CacheService cache = nemakiCachePool.get(repositoryId);
            lock.lock();
            
            CacheInvalidationResponse response = new CacheInvalidationResponse();
            response.setObjectId(objectId);
            
            if (StringUtils.isNotEmpty(beforeDate)) {
                GregorianCalendar beforeDateCal;
                try {
                    beforeDateCal = DataUtil.convertToCalender(beforeDate);
                } catch (ParseException e) {
                    throw ApiException.invalidArgument("Invalid date format: " + beforeDate + ". Use ISO 8601 format.");
                }
                
                Content c = cache.getContentCache().get(objectId);
                if (c == null) {
                    logger.info("Target cache not found for object " + objectId);
                    response.setDeleted(false);
                    response.setMessage("Cache entry not found");
                } else {
                    if (beforeDateCal.compareTo(c.getModified()) > 0) {
                        cache.removeCmisAndContentCache(objectId);
                        response.setDeleted(true);
                        response.setMessage("Cache invalidated (entry was older than specified date)");
                    } else {
                        response.setDeleted(false);
                        response.setMessage("Cache entry is newer than specified date, not invalidated");
                    }
                }
            } else {
                cache.removeCmisAndContentCache(objectId);
                response.setDeleted(true);
                response.setMessage("Cache invalidated successfully");
            }
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/cache/objects/" + objectId));
            links.put("object", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + objectId));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error invalidating cache for object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate cache: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
    
    @DELETE
    @Path("/tree/{folderId}")
    @Operation(
            summary = "Invalidate tree cache",
            description = "Removes the tree cache for the specified folder and its descendants"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Tree cache invalidation result",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = CacheInvalidationResponse.class)
                    )
            )
    })
    public Response invalidateTreeCache(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId) {
        
        logger.info("API v1: Invalidating tree cache for folder " + folderId + " in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        CacheInvalidationResponse response = new CacheInvalidationResponse();
        response.setObjectId(folderId);
        
        if (!nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()) {
            response.setDeleted(false);
            response.setMessage("Tree cache is disabled");
            response.setTreeCacheEnabled(false);
            return Response.ok(response).build();
        }
        
        Lock lock = threadLockService.getWriteLock(repositoryId, folderId);
        try {
            CacheService cache = nemakiCachePool.get(repositoryId);
            lock.lock();
            
            cache.removeCmisAndTreeCache(folderId);
            
            response.setDeleted(true);
            response.setTreeCacheEnabled(true);
            response.setMessage("Tree cache invalidated successfully");
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/cache/tree/" + folderId));
            links.put("folder", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + folderId));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            logger.severe("Error invalidating tree cache for folder " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate tree cache: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
    
    @DELETE
    @Path("/types")
    @Operation(
            summary = "Invalidate type definition cache",
            description = "Invalidates and regenerates the type definition cache. This forces TypeManager to rebuild all type definitions."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Type cache invalidation result",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = TypeCacheInvalidationResponse.class)
                    )
            )
    })
    public Response invalidateTypeCache(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Invalidating type cache for repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            if (typeManager == null) {
                throw ApiException.internalError("TypeManager not available");
            }
            
            java.lang.reflect.Method invalidateMethod = typeManager.getClass().getDeclaredMethod("invalidateTypeDefinitionCache", String.class);
            invalidateMethod.setAccessible(true);
            invalidateMethod.invoke(typeManager, repositoryId);
            
            TypeCacheInvalidationResponse response = new TypeCacheInvalidationResponse();
            response.setRepositoryId(repositoryId);
            response.setInvalidated(true);
            response.setMessage("Type definition cache invalidated and regenerated successfully");
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/cache/types"));
            links.put("types", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/types"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (NoSuchMethodException e) {
            logger.warning("TypeManager does not support cache invalidation: " + e.getMessage());
            TypeCacheInvalidationResponse response = new TypeCacheInvalidationResponse();
            response.setRepositoryId(repositoryId);
            response.setInvalidated(false);
            response.setMessage("Type cache invalidation not supported by this TypeManager implementation");
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error invalidating type cache: " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate type cache: " + e.getMessage(), e);
        }
    }
    
    @Schema(description = "Cache invalidation response")
    public static class CacheInvalidationResponse {
        @Schema(description = "Object ID that was targeted for invalidation")
        private String objectId;
        
        @Schema(description = "Whether the cache entry was deleted")
        private boolean deleted;
        
        @Schema(description = "Whether tree cache is enabled (for tree cache operations)")
        private Boolean treeCacheEnabled;
        
        @Schema(description = "Status message")
        private String message;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getObjectId() { return objectId; }
        public void setObjectId(String objectId) { this.objectId = objectId; }
        public boolean isDeleted() { return deleted; }
        public void setDeleted(boolean deleted) { this.deleted = deleted; }
        public Boolean getTreeCacheEnabled() { return treeCacheEnabled; }
        public void setTreeCacheEnabled(Boolean treeCacheEnabled) { this.treeCacheEnabled = treeCacheEnabled; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Type cache invalidation response")
    public static class TypeCacheInvalidationResponse {
        @Schema(description = "Repository ID")
        private String repositoryId;
        
        @Schema(description = "Whether the type cache was invalidated")
        private boolean invalidated;
        
        @Schema(description = "Status message")
        private String message;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
        public boolean isInvalidated() { return invalidated; }
        public void setInvalidated(boolean invalidated) { this.invalidated = invalidated; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
}
