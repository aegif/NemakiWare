package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
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
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DateUtil;
import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Component
@Path("/repositories/{repositoryId}/archives")
@Tag(name = "archives", description = "Archive (trash) management operations")
@Produces(MediaType.APPLICATION_JSON)
public class ArchiveResource {
    
    private static final Logger logger = Logger.getLogger(ArchiveResource.class.getName());
    
    @Autowired
    private ContentService contentService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Operation(
            summary = "List archived items",
            description = "Returns a paginated list of archived (deleted) items in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Archives retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ArchiveListResponse.class)
                    )
            )
    })
    public Response listArchives(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") int skipCount,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") int maxItems,
            @Parameter(description = "Sort in descending order by date")
            @QueryParam("desc") @DefaultValue("true") boolean desc) {
        
        logger.info("API v1: Listing archives in repository " + repositoryId);
        
        try {
            List<Archive> allArchives = contentService.getArchives(repositoryId, skipCount, maxItems + 1, desc);
            
            List<Archive> filteredArchives = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(allArchives)) {
                for (Archive a : allArchives) {
                    if (NodeType.ATTACHMENT.value().equals(a.getType())) {
                        continue;
                    } else if (NodeType.CMIS_DOCUMENT.value().equals(a.getType())) {
                        boolean ilv = (a.isLatestVersion() != null) ? a.isLatestVersion() : false;
                        if (!ilv) continue;
                    }
                    filteredArchives.add(a);
                }
            }
            
            boolean hasMoreItems = filteredArchives.size() > maxItems;
            if (hasMoreItems) {
                filteredArchives = filteredArchives.subList(0, maxItems);
            }
            
            List<ArchiveResponse> archiveResponses = filteredArchives.stream()
                    .map(a -> convertToArchiveResponse(a, repositoryId))
                    .collect(Collectors.toList());
            
            ArchiveListResponse response = new ArchiveListResponse();
            response.setArchives(archiveResponses);
            response.setNumItems(archiveResponses.size());
            response.setHasMoreItems(hasMoreItems);
            response.setLinks(buildListLinks(repositoryId, skipCount, maxItems));
            
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
            summary = "Get archive details",
            description = "Returns details of a specific archived item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Archive retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ArchiveResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Archive not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
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
        
        logger.info("API v1: Getting archive " + archiveId + " in repository " + repositoryId);
        
        try {
            Archive archive = contentService.getArchive(repositoryId, archiveId);
            if (archive == null) {
                throw ApiException.objectNotFound(archiveId, repositoryId);
            }
            
            ArchiveResponse response = convertToArchiveResponse(archive, repositoryId);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting archive " + archiveId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get archive: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{archiveId}/restore")
    @Operation(
            summary = "Restore archived item",
            description = "Restores an archived item back to its original location"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Archive restored successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RestoreResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Archive not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Parent folder no longer exists",
                    content = @io.swagger.v3.oas.annotations.media.Content(
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
        
        logger.info("API v1: Restoring archive " + archiveId + " in repository " + repositoryId);
        
        try {
            Archive archive = contentService.getArchive(repositoryId, archiveId);
            if (archive == null) {
                throw ApiException.objectNotFound(archiveId, repositoryId);
            }
            
            contentService.restoreArchive(repositoryId, archiveId);
            
            RestoreResponse response = new RestoreResponse();
            response.setArchiveId(archiveId);
            response.setOriginalId(archive.getOriginalId());
            response.setRestored(true);
            response.setMessage("Archive restored successfully");
            
            Map<String, LinkInfo> links = new HashMap<>();
            if (archive.getOriginalId() != null) {
                links.put("restoredObject", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + archive.getOriginalId()));
            }
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ParentNoLongerExistException e) {
            logger.warning("Cannot restore archive " + archiveId + ": parent no longer exists");
            throw ApiException.conflict("Cannot restore archive: parent folder no longer exists");
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
            summary = "Permanently delete archived item",
            description = "Permanently destroys an archived item. This action cannot be undone."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Archive permanently deleted"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Archive not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
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
        
        logger.info("API v1: Destroying archive " + archiveId + " in repository " + repositoryId);
        
        try {
            Archive archive = contentService.getArchive(repositoryId, archiveId);
            if (archive == null) {
                throw ApiException.objectNotFound(archiveId, repositoryId);
            }
            
            contentService.destroyArchive(repositoryId, archiveId);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error destroying archive " + archiveId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to destroy archive: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Operation(
            summary = "Empty trash",
            description = "Permanently deletes all archived items in the repository. This action cannot be undone."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Trash emptied successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = EmptyTrashResponse.class)
                    )
            )
    })
    public Response emptyTrash(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Emptying trash in repository " + repositoryId);
        
        try {
            List<Archive> allArchives = contentService.getArchives(repositoryId, null, null, false);
            int deletedCount = 0;
            
            if (CollectionUtils.isNotEmpty(allArchives)) {
                for (Archive archive : allArchives) {
                    try {
                        contentService.destroyArchive(repositoryId, archive.getId());
                        deletedCount++;
                    } catch (Exception e) {
                        logger.warning("Failed to destroy archive " + archive.getId() + ": " + e.getMessage());
                    }
                }
            }
            
            EmptyTrashResponse response = new EmptyTrashResponse();
            response.setDeletedCount(deletedCount);
            response.setMessage("Trash emptied successfully");
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error emptying trash: " + e.getMessage());
            throw ApiException.internalError("Failed to empty trash: " + e.getMessage(), e);
        }
    }
    
    private ArchiveResponse convertToArchiveResponse(Archive archive, String repositoryId) {
        ArchiveResponse response = new ArchiveResponse();
        response.setArchiveId(archive.getId());
        response.setOriginalId(archive.getOriginalId());
        response.setName(archive.getName());
        response.setType(archive.getType());
        response.setParentId(archive.getParentId());
        response.setDeletedWithParent(archive.isDeletedWithParent());
        response.setCreator(archive.getCreator());
        
        if (archive.getCreated() != null) {
            try {
                response.setCreated(DateUtil.formatSystemDateTime(archive.getCreated()));
            } catch (Exception e) {
                logger.warning("Archive " + archive.getId() + " has broken 'created' property");
            }
        }
        
        if (archive.isDocument()) {
            response.setMimeType(archive.getMimeType());
            response.setDocument(true);
        } else {
            response.setDocument(false);
        }
        
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/archives/" + archive.getId()));
        links.put("restore", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/archives/" + archive.getId() + "/restore"));
        response.setLinks(links);
        
        return response;
    }
    
    private Map<String, LinkInfo> buildListLinks(String repositoryId, int skipCount, int maxItems) {
        Map<String, LinkInfo> links = new HashMap<>();
        String basePath = "/api/v1/cmis/repositories/" + repositoryId + "/archives";
        
        links.put("self", new LinkInfo(basePath + "?skipCount=" + skipCount + "&maxItems=" + maxItems));
        
        if (skipCount > 0) {
            int prevSkip = Math.max(0, skipCount - maxItems);
            links.put("prev", new LinkInfo(basePath + "?skipCount=" + prevSkip + "&maxItems=" + maxItems));
        }
        
        links.put("next", new LinkInfo(basePath + "?skipCount=" + (skipCount + maxItems) + "&maxItems=" + maxItems));
        
        return links;
    }
    
    @Schema(description = "Archive response")
    public static class ArchiveResponse {
        @Schema(description = "Archive ID")
        private String archiveId;
        
        @Schema(description = "Original object ID before deletion")
        private String originalId;
        
        @Schema(description = "Object name")
        private String name;
        
        @Schema(description = "Object type")
        private String type;
        
        @Schema(description = "Parent folder ID")
        private String parentId;
        
        @Schema(description = "Whether this was deleted as part of parent deletion")
        private boolean deletedWithParent;
        
        @Schema(description = "User who created the object")
        private String creator;
        
        @Schema(description = "Creation date")
        private String created;
        
        @Schema(description = "MIME type (for documents)")
        private String mimeType;
        
        @Schema(description = "Whether this is a document")
        private boolean isDocument;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getArchiveId() { return archiveId; }
        public void setArchiveId(String archiveId) { this.archiveId = archiveId; }
        public String getOriginalId() { return originalId; }
        public void setOriginalId(String originalId) { this.originalId = originalId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public boolean isDeletedWithParent() { return deletedWithParent; }
        public void setDeletedWithParent(boolean deletedWithParent) { this.deletedWithParent = deletedWithParent; }
        public String getCreator() { return creator; }
        public void setCreator(String creator) { this.creator = creator; }
        public String getCreated() { return created; }
        public void setCreated(String created) { this.created = created; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public boolean isDocument() { return isDocument; }
        public void setDocument(boolean isDocument) { this.isDocument = isDocument; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Archive list response")
    public static class ArchiveListResponse {
        @Schema(description = "List of archives")
        private List<ArchiveResponse> archives;
        
        @Schema(description = "Number of items returned")
        private int numItems;
        
        @Schema(description = "Whether there are more items available")
        private boolean hasMoreItems;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public List<ArchiveResponse> getArchives() { return archives; }
        public void setArchives(List<ArchiveResponse> archives) { this.archives = archives; }
        public int getNumItems() { return numItems; }
        public void setNumItems(int numItems) { this.numItems = numItems; }
        public boolean isHasMoreItems() { return hasMoreItems; }
        public void setHasMoreItems(boolean hasMoreItems) { this.hasMoreItems = hasMoreItems; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Restore response")
    public static class RestoreResponse {
        @Schema(description = "Archive ID that was restored")
        private String archiveId;
        
        @Schema(description = "Original object ID")
        private String originalId;
        
        @Schema(description = "Whether restoration was successful")
        private boolean restored;
        
        @Schema(description = "Status message")
        private String message;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getArchiveId() { return archiveId; }
        public void setArchiveId(String archiveId) { this.archiveId = archiveId; }
        public String getOriginalId() { return originalId; }
        public void setOriginalId(String originalId) { this.originalId = originalId; }
        public boolean isRestored() { return restored; }
        public void setRestored(boolean restored) { this.restored = restored; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Empty trash response")
    public static class EmptyTrashResponse {
        @Schema(description = "Number of items permanently deleted")
        private int deletedCount;
        
        @Schema(description = "Status message")
        private String message;
        
        public int getDeletedCount() { return deletedCount; }
        public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
