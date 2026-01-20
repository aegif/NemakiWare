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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
import jp.aegif.nemaki.api.v1.model.request.GroupCreateRequest;
import jp.aegif.nemaki.api.v1.model.request.GroupMembersRequest;
import jp.aegif.nemaki.api.v1.model.request.GroupUpdateRequest;
import jp.aegif.nemaki.api.v1.model.response.GroupListResponse;
import jp.aegif.nemaki.api.v1.model.response.GroupResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/groups")
@Tag(name = "groups", description = "Group management operations")
@Produces(MediaType.APPLICATION_JSON)
public class GroupResource {
    
    private static final Logger logger = Logger.getLogger(GroupResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    
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
            summary = "List all groups",
            description = "Returns a list of all groups in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of groups",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
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
    public Response listGroups(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Listing all groups for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            List<GroupItem> groupItems = contentService.getGroupItems(repositoryId);
            
            List<GroupResponse> groups = new ArrayList<>();
            for (GroupItem groupItem : groupItems) {
                groups.add(mapToResponse(groupItem, repositoryId));
            }
            
            GroupListResponse response = new GroupListResponse();
            response.setGroups(groups);
            response.setTotalCount(groups.size());
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/groups"));
            response.setLinks(links);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error listing groups: " + e.getMessage());
            throw ApiException.internalError("Failed to list groups: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{groupId}")
    @Operation(
            summary = "Get group by ID",
            description = "Returns information about a specific group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Group information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "developers")
            @PathParam("groupId") String groupId) {
        
        logger.info("API v1: Getting group " + groupId + " for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            GroupItem groupItem = contentService.getGroupItemById(repositoryId, groupId);
            if (groupItem == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            GroupResponse response = mapToResponse(groupItem, repositoryId);
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get group: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/search")
    @Operation(
            summary = "Search groups",
            description = "Search groups by query string (matches groupId or name)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search results",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid query parameter",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response searchGroups(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search query", required = true, example = "dev")
            @QueryParam("query") String query,
            @Parameter(description = "Maximum number of results", example = "50")
            @QueryParam("maxResults") Integer maxResults) {
        
        logger.info("API v1: Searching groups with query: " + query);
        
        try {
            validateRepository(repositoryId);
            
            if (StringUtils.isBlank(query)) {
                throw ApiException.invalidArgument("Query parameter is required");
            }
            
            int limit = maxResults != null ? maxResults : 50;
            
            List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
            List<GroupResponse> matchingGroups = new ArrayList<>();
            
            for (GroupItem groupItem : allGroups) {
                if (matchingGroups.size() >= limit) {
                    break;
                }
                
                String groupId = groupItem.getGroupId();
                String groupName = groupItem.getName();
                
                boolean matches = (StringUtils.isNotEmpty(groupId) && groupId.toLowerCase().contains(query.toLowerCase())) ||
                                  (StringUtils.isNotEmpty(groupName) && groupName.toLowerCase().contains(query.toLowerCase()));
                
                if (matches) {
                    matchingGroups.add(mapToResponse(groupItem, repositoryId));
                }
            }
            
            GroupListResponse response = new GroupListResponse();
            response.setGroups(matchingGroups);
            response.setTotalCount(matchingGroups.size());
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error searching groups: " + e.getMessage());
            throw ApiException.internalError("Failed to search groups: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create a new group",
            description = "Creates a new group in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Group created successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
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
                    responseCode = "403",
                    description = "Permission denied - admin access required",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Group already exists",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group creation request", required = true)
            GroupCreateRequest request) {
        
        logger.info("API v1: Creating group " + request.getGroupId() + " in repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            validateGroupCreateRequest(request);
            
            GroupItem existingGroup = contentService.getGroupItemById(repositoryId, request.getGroupId());
            if (existingGroup != null) {
                throw ApiException.conflict("Group with ID '" + request.getGroupId() + "' already exists");
            }
            
            Folder groupsFolder = getOrCreateGroupsFolder(repositoryId);
            
            List<String> users = request.getUsers() != null ? request.getUsers() : new ArrayList<>();
            List<String> groups = request.getGroups() != null ? request.getGroups() : new ArrayList<>();
            
            GroupItem groupItem = new GroupItem(
                    null,
                    NemakiObjectType.nemakiGroup,
                    request.getGroupId(),
                    request.getName(),
                    users,
                    groups
            );
            groupItem.setParentId(groupsFolder.getId());
            
            setCreationSignature(groupItem);
            
            contentService.createGroupItem(new SystemCallContext(repositoryId), repositoryId, groupItem);
            
            GroupItem createdGroup = contentService.getGroupItemById(repositoryId, request.getGroupId());
            GroupResponse response = mapToResponse(createdGroup, repositoryId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating group: " + e.getMessage());
            throw ApiException.internalError("Failed to create group: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Update a group",
            description = "Updates an existing group's information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Group updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
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
                    description = "Group not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response updateGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "developers")
            @PathParam("groupId") String groupId,
            @Parameter(description = "Group update request", required = true)
            GroupUpdateRequest request) {
        
        logger.info("API v1: Updating group " + groupId + " in repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            GroupItem groupItem = contentService.getGroupItemById(repositoryId, groupId);
            if (groupItem == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            if (request.getName() != null) {
                groupItem.setName(request.getName());
            }
            if (request.getUsers() != null) {
                groupItem.setUsers(request.getUsers());
            }
            if (request.getGroups() != null) {
                groupItem.setGroups(request.getGroups());
            }
            
            setModificationSignature(groupItem);
            
            contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, groupItem);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = mapToResponse(updatedGroup, repositoryId);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error updating group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to update group: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{groupId}")
    @Operation(
            summary = "Delete a group",
            description = "Deletes a group from the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Group deleted successfully"
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
                    description = "Group not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "developers")
            @PathParam("groupId") String groupId) {
        
        logger.info("API v1: Deleting group " + groupId + " from repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            GroupItem groupItem = contentService.getGroupItemById(repositoryId, groupId);
            if (groupItem == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            removeGroupFromAllParentGroups(repositoryId, groupId);
            
            contentService.deleteGroupItem(new SystemCallContext(repositoryId), repositoryId, groupId);
            
            return Response.noContent().build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete group: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{groupId}/members")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Add members to a group",
            description = "Adds users and/or sub-groups to a group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Members added successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
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
                    description = "Group not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response addMembers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "developers")
            @PathParam("groupId") String groupId,
            @Parameter(description = "Members to add", required = true)
            GroupMembersRequest request) {
        
        logger.info("API v1: Adding members to group " + groupId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            GroupItem groupItem = contentService.getGroupItemById(repositoryId, groupId);
            if (groupItem == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            if (request.getUsers() != null && !request.getUsers().isEmpty()) {
                List<String> currentUsers = groupItem.getUsers() != null ? new ArrayList<>(groupItem.getUsers()) : new ArrayList<>();
                for (String userId : request.getUsers()) {
                    if (!currentUsers.contains(userId)) {
                        currentUsers.add(userId);
                    }
                }
                groupItem.setUsers(currentUsers);
            }
            
            if (request.getGroups() != null && !request.getGroups().isEmpty()) {
                List<String> currentGroups = groupItem.getGroups() != null ? new ArrayList<>(groupItem.getGroups()) : new ArrayList<>();
                for (String subGroupId : request.getGroups()) {
                    if (!currentGroups.contains(subGroupId) && !subGroupId.equals(groupId)) {
                        currentGroups.add(subGroupId);
                    }
                }
                groupItem.setGroups(currentGroups);
            }
            
            setModificationSignature(groupItem);
            
            contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, groupItem);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = mapToResponse(updatedGroup, repositoryId);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error adding members to group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to add members: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{groupId}/members")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Remove members from a group",
            description = "Removes users and/or sub-groups from a group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Members removed successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
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
                    description = "Group not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response removeMembers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "developers")
            @PathParam("groupId") String groupId,
            @Parameter(description = "Members to remove", required = true)
            GroupMembersRequest request) {
        
        logger.info("API v1: Removing members from group " + groupId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            GroupItem groupItem = contentService.getGroupItemById(repositoryId, groupId);
            if (groupItem == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            if (request.getUsers() != null && !request.getUsers().isEmpty()) {
                List<String> currentUsers = groupItem.getUsers() != null ? new ArrayList<>(groupItem.getUsers()) : new ArrayList<>();
                currentUsers.removeAll(request.getUsers());
                groupItem.setUsers(currentUsers);
            }
            
            if (request.getGroups() != null && !request.getGroups().isEmpty()) {
                List<String> currentGroups = groupItem.getGroups() != null ? new ArrayList<>(groupItem.getGroups()) : new ArrayList<>();
                currentGroups.removeAll(request.getGroups());
                groupItem.setGroups(currentGroups);
            }
            
            setModificationSignature(groupItem);
            
            contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, groupItem);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = mapToResponse(updatedGroup, repositoryId);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error removing members from group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to remove members: " + e.getMessage(), e);
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
    
    private void validateGroupCreateRequest(GroupCreateRequest request) {
        if (StringUtils.isBlank(request.getGroupId())) {
            throw ApiException.invalidArgument("Group ID is required");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw ApiException.invalidArgument("Name is required");
        }
    }
    
    private Folder getOrCreateGroupsFolder(String repositoryId) {
        Folder systemFolder = contentService.getSystemFolder(repositoryId);
        if (systemFolder == null) {
            throw ApiException.internalError("System folder not found");
        }
        
        List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        for (jp.aegif.nemaki.model.Content child : children) {
            if ("groups".equals(child.getName()) && child instanceof Folder) {
                return (Folder) child;
            }
        }
        
        Folder groupsFolder = new Folder();
        groupsFolder.setName("groups");
        groupsFolder.setObjectType("cmis:folder");
        groupsFolder.setParentId(systemFolder.getId());
        
        contentService.createFolder(new SystemCallContext(repositoryId), repositoryId, groupsFolder);
        
        children = contentService.getChildren(repositoryId, systemFolder.getId());
        for (jp.aegif.nemaki.model.Content child : children) {
            if ("groups".equals(child.getName()) && child instanceof Folder) {
                return (Folder) child;
            }
        }
        
        throw ApiException.internalError("Failed to create groups folder");
    }
    
    private void removeGroupFromAllParentGroups(String repositoryId, String groupId) {
        List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
        
        for (GroupItem group : allGroups) {
            List<String> subGroups = group.getGroups();
            if (subGroups != null && subGroups.contains(groupId)) {
                List<String> newSubGroups = new ArrayList<>(subGroups);
                newSubGroups.remove(groupId);
                group.setGroups(newSubGroups);
                contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            }
        }
    }
    
    private void setCreationSignature(GroupItem groupItem) {
        String userId = getCurrentUserId();
        java.util.Date now = new java.util.Date();
        groupItem.setCreator(userId);
        groupItem.setCreated(new java.util.GregorianCalendar());
        groupItem.getCreated().setTime(now);
        groupItem.setModifier(userId);
        groupItem.setModified(new java.util.GregorianCalendar());
        groupItem.getModified().setTime(now);
    }
    
    private void setModificationSignature(GroupItem groupItem) {
        String userId = getCurrentUserId();
        java.util.Date now = new java.util.Date();
        groupItem.setModifier(userId);
        groupItem.setModified(new java.util.GregorianCalendar());
        groupItem.getModified().setTime(now);
    }
    
    private GroupResponse mapToResponse(GroupItem groupItem, String repositoryId) {
        GroupResponse response = new GroupResponse();
        
        response.setGroupId(groupItem.getGroupId());
        response.setName(groupItem.getName());
        response.setUsers(groupItem.getUsers() != null ? groupItem.getUsers() : new ArrayList<>());
        response.setGroups(groupItem.getGroups() != null ? groupItem.getGroups() : new ArrayList<>());
        
        if (groupItem.getCreated() != null) {
            response.setCreated(ISO_DATE_FORMAT.format(groupItem.getCreated().getTime()));
        }
        if (groupItem.getModified() != null) {
            response.setModified(ISO_DATE_FORMAT.format(groupItem.getModified().getTime()));
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/groups/" + groupItem.getGroupId()));
        links.put("members", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/groups/" + groupItem.getGroupId() + "/members"));
        response.setLinks(links);
        
        return response;
    }
}
