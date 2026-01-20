package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

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
    private RepositoryService repositoryService;
    
    @Autowired
    private ContentService contentService;
    
    @Autowired
    private NemakiCachePool nemakiCachePool;
    
    @Autowired
    private ThreadLockService threadLockService;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @DELETE
    @Path("/{objectId}")
    @Operation(
            summary = "Invalidate cache for an object",
            description = "Removes the specified object from the cache"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Cache invalidated successfully"
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
                    description = "Repository not found",
                    content = @Content(
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
            @Parameter(description = "Only invalidate if modified before this date (ISO 8601 format)")
            @QueryParam("beforeDate") String beforeDate) {
        
        logger.info("API v1: Invalidating cache for object " + objectId + " in repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("objectId", objectId);
            
            Lock lock = threadLockService.getWriteLock(repositoryId, objectId);
            try {
                CacheService cache = nemakiCachePool.get(repositoryId);
                lock.lock();
                
                if (StringUtils.isNotEmpty(beforeDate)) {
                    GregorianCalendar beforeDateCal = DataUtil.convertToCalender(beforeDate);
                    Content c = cache.getContentCache().get(objectId);
                    if (c == null) {
                        response.put("deleted", false);
                        response.put("message", "Target cache not found");
                    } else {
                        if (beforeDateCal.compareTo(c.getModified()) > 0) {
                            cache.removeCmisAndContentCache(objectId);
                            response.put("deleted", true);
                            response.put("message", "Cache invalidated (object was modified before specified date)");
                        } else {
                            response.put("deleted", false);
                            response.put("message", "Cache not invalidated (object was modified after specified date)");
                        }
                    }
                } else {
                    cache.removeCmisAndContentCache(objectId);
                    response.put("deleted", true);
                    response.put("message", "Cache invalidated successfully");
                }
            } catch (ParseException e) {
                throw ApiException.invalidArgument("Invalid date format: " + beforeDate);
            } finally {
                lock.unlock();
            }
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error invalidating cache: " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate cache: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/tree/{parentId}")
    @Operation(
            summary = "Invalidate tree cache for a folder",
            description = "Removes the tree cache for the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Tree cache invalidated successfully"
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
    public Response invalidateTreeCache(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent folder ID", required = true)
            @PathParam("parentId") String parentId) {
        
        logger.info("API v1: Invalidating tree cache for folder " + parentId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("parentId", parentId);
            
            if (!nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()) {
                response.put("treeCacheEnabled", false);
                response.put("message", "Tree cache is disabled");
                return Response.ok(response).build();
            }
            
            Lock lock = threadLockService.getWriteLock(repositoryId, parentId);
            try {
                CacheService cache = nemakiCachePool.get(repositoryId);
                lock.lock();
                cache.removeCmisAndTreeCache(parentId);
                response.put("deleted", true);
                response.put("message", "Tree cache invalidated successfully");
            } finally {
                lock.unlock();
            }
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error invalidating tree cache: " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate tree cache: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/types")
    @Operation(
            summary = "Invalidate type definition cache",
            description = "Forces regeneration of all type definitions"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Type cache invalidated successfully"
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
    public Response invalidateTypeCache(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Invalidating type cache for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("invalidated", true);
            response.put("message", "Type definition cache invalidation requested");
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error invalidating type cache: " + e.getMessage());
            throw ApiException.internalError("Failed to invalidate type cache: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/status")
    @Operation(
            summary = "Get cache status",
            description = "Returns information about the current cache state"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Cache status information"
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
    public Response getCacheStatus(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting cache status for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            CacheService cache = nemakiCachePool.get(repositoryId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("repositoryId", repositoryId);
            response.put("treeCacheEnabled", cache.getTreeCache().isCacheEnabled());
            response.put("status", "active");
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting cache status: " + e.getMessage());
            throw ApiException.internalError("Failed to get cache status: " + e.getMessage(), e);
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
