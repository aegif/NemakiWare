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
import jakarta.ws.rs.PUT;
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
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.DateUtil;
import jp.aegif.nemaki.util.constant.NodeType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/archives")
@Tag(name = "archives", description = "Archive (trash) management operations")
@Produces(MediaType.APPLICATION_JSON)
public class ArchiveResource {
    
    private static final Logger logger = Logger.getLogger(ArchiveResource.class.getName());
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Autowired
    private ContentService contentService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Operation(
            summary = "List archived items",
            description = "Returns a list of archived (deleted) items in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of archived items"
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
    public Response listArchives(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Number of items to skip", example = "0")
            @QueryParam("skip") Integer skip,
            @Parameter(description = "Maximum number of items to return", example = "50")
            @QueryParam("limit") Integer limit,
            @Parameter(description = "Sort in descending order by date", example = "true")
            @QueryParam("desc") Boolean desc) {
        
        logger.info("API v1: Listing archives for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            List<Archive> archives = contentService.getArchives(repositoryId, skip, limit, desc);
            
            List<Map<String, Object>> archiveList = new ArrayList<>();
            for (Archive archive : archives) {
                if (NodeType.ATTACHMENT.value().equals(archive.getType())) {
                    continue;
                } else if (NodeType.CMIS_DOCUMENT.value().equals(archive.getType())) {
                    boolean isLatestVersion = archive.isLatestVersion() != null ? archive.isLatestVersion() : false;
                    if (!isLatestVersion) continue;
                }
                
                Map<String, Object> archiveMap = buildArchiveMap(archive, repositoryId);
                archiveList.add(archiveMap);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("archives", archiveList);
            response.put("totalCount", archiveList.size());
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/archives"));
            response.put("_links", links);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error listing archives: " + e.getMessage());
            throw ApiException.internalError("Failed to list archives: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{archiveId}")
    @Operation(
            summary = "Get archive by ID",
            description = "Returns information about a specific archived item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Archive information"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Archive not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getArchive(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Archive ID", required = true)
            @PathParam("archiveId") String archiveId) {
        
        logger.info("API v1: Getting archive " + archiveId + " for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            Archive archive = contentService.getArchive(repositoryId, archiveId);
            if (archive == null) {
                throw ApiException.objectNotFound(archiveId, repositoryId);
            }
            
            Map<String, Object> response = buildArchiveMap(archive, repositoryId);
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting archive " + archiveId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get archive: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{archiveId}/restore")
    @Operation(
            summary = "Restore an archived item",
            description = "Restores an archived item back to its original location"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Archive restored successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Archive not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Parent folder no longer exists",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response restoreArchive(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Archive ID", required = true)
            @PathParam("archiveId") String archiveId) {
        
        logger.info("API v1: Restoring archive " + archiveId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            contentService.restoreArchive(repositoryId, archiveId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("archiveId", archiveId);
            response.put("restored", true);
            response.put("message", "Archive restored successfully");
            
            return Response.ok(response).build();
        } catch (ParentNoLongerExistException e) {
            throw ApiException.conflict("Cannot restore: parent folder no longer exists");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error restoring archive " + archiveId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to restore archive: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{archiveId}")
    @Operation(
            summary = "Permanently delete an archived item",
            description = "Permanently destroys an archived item (cannot be undone)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Archive permanently deleted"
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
                    description = "Archive not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response destroyArchive(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Archive ID", required = true)
            @PathParam("archiveId") String archiveId) {
        
        logger.info("API v1: Destroying archive " + archiveId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            contentService.destroyArchive(repositoryId, archiveId);
            
            return Response.noContent().build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error destroying archive " + archiveId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to destroy archive: " + e.getMessage(), e);
        }
    }
    
    private Map<String, Object> buildArchiveMap(Archive archive, String repositoryId) {
        Map<String, Object> archiveMap = new HashMap<>();
        archiveMap.put("id", archive.getId());
        archiveMap.put("type", archive.getType());
        archiveMap.put("name", archive.getName());
        archiveMap.put("originalId", archive.getOriginalId());
        archiveMap.put("parentId", archive.getParentId());
        archiveMap.put("isDeletedWithParent", archive.isDeletedWithParent());
        archiveMap.put("creator", archive.getCreator());
        
        try {
            if (archive.getCreated() != null) {
                archiveMap.put("created", DateUtil.formatSystemDateTime(archive.getCreated()));
            }
        } catch (Exception e) {
            logger.warning("Archive " + archive.getId() + " has broken 'created' property");
        }
        
        if (archive.isDocument()) {
            archiveMap.put("mimeType", archive.getMimeType());
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/archives/" + archive.getId()));
        links.put("restore", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/archives/" + archive.getId() + "/restore"));
        archiveMap.put("_links", links);
        
        return archiveMap;
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
