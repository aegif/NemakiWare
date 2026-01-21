package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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
import jp.aegif.nemaki.api.v1.model.request.GroupMembersRequest;
import jp.aegif.nemaki.api.v1.model.request.GroupRequest;
import jp.aegif.nemaki.api.v1.model.response.GroupListResponse;
import jp.aegif.nemaki.api.v1.model.response.GroupResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.DateUtil;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Component
@Path("/repositories/{repositoryId}/groups")
@Tag(name = "groups", description = "Group management operations")
@Produces(MediaType.APPLICATION_JSON)
public class GroupResource {
    
    private static final Logger logger = Logger.getLogger(GroupResource.class.getName());
    
    @Autowired
    private ContentService contentService;
    
    @Context
    private UriInfo uriInfo;
    
        @Context
        private HttpServletRequest httpRequest;
    
        private void checkAdminAuthorization() {
            CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
            if (callContext == null) {
                throw ApiException.unauthorized("Authentication required for group management operations");
            }
            Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
            if (isAdmin == null || !isAdmin) {
                throw ApiException.permissionDenied("Only administrators can perform group management operations");
            }
        }
    
        @GET
        @Operation(
                summary = "List all groups",
            description = "Gets a list of all groups in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of groups",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response listGroups(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
                logger.info("API v1: Listing groups for repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                    contentService.getGroupItems(repositoryId), Collections.emptyList());
            
            int totalCount = allGroups.size();
            int endIndex = Math.min(skipCount + maxItems, totalCount);
            List<GroupItem> pagedGroups = skipCount < totalCount 
                    ? allGroups.subList(skipCount, endIndex) 
                    : Collections.emptyList();
            
            List<GroupResponse> groupResponses = pagedGroups.stream()
                    .map(group -> convertToGroupResponse(group, repositoryId))
                    .collect(Collectors.toList());
            
            GroupListResponse response = new GroupListResponse();
            response.setGroups(groupResponses);
            response.setNumItems(totalCount);
            response.setHasMoreItems(endIndex < totalCount);
            response.setLinks(buildListLinks(repositoryId, skipCount, maxItems, totalCount));
            
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
            description = "Gets the specified group by group ID"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Group information",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true, example = "administrators")
            @PathParam("groupId") String groupId) {
        
                logger.info("API v1: Getting group " + groupId + " from repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
            
                    if (group == null) {
                throw ApiException.groupNotFound(groupId, repositoryId);
            }
            
            GroupResponse response = convertToGroupResponse(group, repositoryId);
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
            description = "Searches for groups by query string (matches groupId or groupName)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search results",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupListResponse.class)
                    )
            )
    })
    public Response searchGroups(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search query", required = true)
            @QueryParam("query") String query,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("50") Integer maxItems) {
        
                logger.info("API v1: Searching groups with query '" + query + "' in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    if (StringUtils.isBlank(query)) {
                throw ApiException.invalidArgument("Query parameter is required");
            }
            
            List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                    contentService.getGroupItems(repositoryId), Collections.emptyList());
            
            List<GroupResponse> matchedGroups = new ArrayList<>();
            for (GroupItem group : allGroups) {
                String groupId = group.getGroupId();
                String groupName = group.getName();
                boolean matches = (StringUtils.isNotEmpty(groupId) && groupId.contains(query)) ||
                                  (StringUtils.isNotEmpty(groupName) && groupName.contains(query));
                if (matches && matchedGroups.size() < maxItems) {
                    matchedGroups.add(convertToGroupResponse(group, repositoryId));
                }
            }
            
            GroupListResponse response = new GroupListResponse();
            response.setGroups(matchedGroups);
            response.setNumItems(matchedGroups.size());
            response.setHasMoreItems(false);
            
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
            summary = "Create group",
            description = "Creates a new group in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Group created successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Group already exists",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            GroupRequest request) {
        
                checkAdminAuthorization();
        
                try {
                    if (request == null) {
                        throw ApiException.invalidArgument("Request body is required");
                    }
            
                    logger.info("API v1: Creating group " + request.getGroupId() + " in repository " + repositoryId);
            
                    validateCreateGroupRequest(request, repositoryId);
            
            GroupItem existingGroup = contentService.getGroupItemById(repositoryId, request.getGroupId());
            if (existingGroup != null) {
                throw ApiException.conflict("Group with ID '" + request.getGroupId() + "' already exists");
            }
            
            List<String> users = request.getUsers() != null ? request.getUsers() : new ArrayList<>();
            List<String> groups = request.getGroups() != null ? request.getGroups() : new ArrayList<>();
            
            validateMemberIds(repositoryId, users, groups);
            
            GroupItem group = new GroupItem(null, NemakiObjectType.nemakiGroup, 
                    request.getGroupId(), request.getGroupName(), users, groups);
            
            Folder groupsFolder = getOrCreateSystemSubFolder(repositoryId, "groups");
            group.setParentId(groupsFolder.getId());
            
            setCreationSignature(group);
            
            contentService.createGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            
            GroupItem createdGroup = contentService.getGroupItemById(repositoryId, request.getGroupId());
            GroupResponse response = convertToGroupResponse(createdGroup, repositoryId);
            
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
            summary = "Update group",
            description = "Updates an existing group in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Group updated successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response updateGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true)
            @PathParam("groupId") String groupId,
            GroupRequest request) {
        
                logger.info("API v1: Updating group " + groupId + " in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
                    if (group == null) {
                        throw ApiException.groupNotFound(groupId, repositoryId);
                    }
            
                    if (request.getGroupName() != null) {
                group.setName(request.getGroupName());
            }
            
            if (request.getUsers() != null) {
                group.setUsers(request.getUsers());
            }
            
            if (request.getGroups() != null) {
                group.setGroups(request.getGroups());
            }
            
            setModificationSignature(group);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = convertToGroupResponse(updatedGroup, repositoryId);
            
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
            summary = "Delete group",
            description = "Deletes the specified group from the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Group deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteGroup(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true)
            @PathParam("groupId") String groupId) {
        
                logger.info("API v1: Deleting group " + groupId + " from repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
                    if (group == null) {
                        throw ApiException.groupNotFound(groupId, repositoryId);
                    }
            
                    removeGroupFromAllNestedGroups(repositoryId, groupId);

            contentService.delete(new SystemCallContext(repositoryId), repositoryId, group.getId(), false);

            // TODO: Cache invalidation issue - After deletion, getGroupItemById() still returns
            // the deleted group due to caching in ContentService. The group is deleted from CouchDB
            // but the cache is not invalidated. Need to add cache invalidation for GroupItem.
            // See: ContentServiceImpl.getGroupItemById() caching mechanism
            // Related test: management-api.spec.ts "should create, get, update, and delete group"

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
            summary = "Add members to group",
            description = "Adds users and/or groups as members of the specified group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Members added successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response addMembers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true)
            @PathParam("groupId") String groupId,
            GroupMembersRequest request) {
        
                logger.info("API v1: Adding members to group " + groupId + " in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
                    if (group == null) {
                        throw ApiException.groupNotFound(groupId, repositoryId);
                    }
            
                    List<String> currentUsers = group.getUsers() != null ? new ArrayList<>(group.getUsers()) : new ArrayList<>();
            List<String> currentGroups = group.getGroups() != null ? new ArrayList<>(group.getGroups()) : new ArrayList<>();
            
            if (request.getUsers() != null) {
                for (String userId : request.getUsers()) {
                    UserItem user = contentService.getUserItemById(repositoryId, userId);
                    if (user == null) {
                        throw ApiException.userNotFound(userId, repositoryId);
                    }
                    if (!currentUsers.contains(userId)) {
                        currentUsers.add(userId);
                    }
                }
            }
            
            if (request.getGroups() != null) {
                for (String nestedGroupId : request.getGroups()) {
                    GroupItem nestedGroup = contentService.getGroupItemById(repositoryId, nestedGroupId);
                    if (nestedGroup == null) {
                        throw ApiException.groupNotFound(nestedGroupId, repositoryId);
                    }
                    if (!currentGroups.contains(nestedGroupId)) {
                        currentGroups.add(nestedGroupId);
                    }
                }
            }
            
            group.setUsers(currentUsers);
            group.setGroups(currentGroups);
            
            setModificationSignature(group);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = convertToGroupResponse(updatedGroup, repositoryId);
            
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
            summary = "Remove members from group",
            description = "Removes users and/or groups from the specified group"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Members removed successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = GroupResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Group not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response removeMembers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Group ID", required = true)
            @PathParam("groupId") String groupId,
            GroupMembersRequest request) {
        
                logger.info("API v1: Removing members from group " + groupId + " in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
                    if (group == null) {
                        throw ApiException.groupNotFound(groupId, repositoryId);
                    }
            
                    List<String> currentUsers = group.getUsers() != null ? new ArrayList<>(group.getUsers()) : new ArrayList<>();
                    List<String> currentGroups = group.getGroups() != null ? new ArrayList<>(group.getGroups()) : new ArrayList<>();
            
            if (request.getUsers() != null) {
                currentUsers.removeAll(request.getUsers());
            }
            
            if (request.getGroups() != null) {
                currentGroups.removeAll(request.getGroups());
            }
            
            group.setUsers(currentUsers);
            group.setGroups(currentGroups);
            
            setModificationSignature(group);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
            
            GroupItem updatedGroup = contentService.getGroupItemById(repositoryId, groupId);
            GroupResponse response = convertToGroupResponse(updatedGroup, repositoryId);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error removing members from group " + groupId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to remove members: " + e.getMessage(), e);
        }
    }
    
    private void validateCreateGroupRequest(GroupRequest request, String repositoryId) {
        if (StringUtils.isBlank(request.getGroupId())) {
            throw ApiException.invalidArgument("groupId is required");
        }
        if (StringUtils.isBlank(request.getGroupName())) {
            throw ApiException.invalidArgument("groupName is required");
        }
    }
    
    private void validateMemberIds(String repositoryId, List<String> userIds, List<String> groupIds) {
        if (CollectionUtils.isNotEmpty(userIds)) {
            for (String userId : userIds) {
                UserItem user = contentService.getUserItemById(repositoryId, userId);
                if (user == null) {
                    throw ApiException.invalidArgument("User with ID '" + userId + "' does not exist");
                }
            }
        }
        
        if (CollectionUtils.isNotEmpty(groupIds)) {
            for (String groupId : groupIds) {
                GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
                if (group == null) {
                    throw ApiException.invalidArgument("Group with ID '" + groupId + "' does not exist");
                }
            }
        }
    }
    
    private void removeGroupFromAllNestedGroups(String repositoryId, String groupId) {
        List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                contentService.getGroupItems(repositoryId), Collections.emptyList());
        
        for (GroupItem group : allGroups) {
            List<String> nestedGroups = group.getGroups();
            if (nestedGroups != null && nestedGroups.contains(groupId)) {
                List<String> updatedNestedGroups = new ArrayList<>(nestedGroups);
                updatedNestedGroups.remove(groupId);
                group.setGroups(updatedNestedGroups);
                contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
                logger.info("Removed group " + groupId + " from nestedGroups of group " + group.getGroupId());
            }
        }
    }
    
    private GroupResponse convertToGroupResponse(GroupItem group, String repositoryId) {
        GroupResponse response = new GroupResponse();
        response.setGroupId(group.getGroupId());
        response.setGroupName(group.getName());
        response.setType(group.getType());
        response.setUsers(group.getUsers() != null ? group.getUsers() : new ArrayList<>());
        response.setGroups(group.getGroups() != null ? group.getGroups() : new ArrayList<>());
        response.setCreatedBy(group.getCreator());
        response.setLastModifiedBy(group.getModifier());
        
        if (group.getCreated() != null) {
            response.setCreationDate(DateUtil.formatSystemDateTime(group.getCreated()));
        }
        if (group.getModified() != null) {
            response.setLastModificationDate(DateUtil.formatSystemDateTime(group.getModified()));
        }
        
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/groups/" + group.getGroupId()));
        response.setLinks(links);
        
        return response;
    }
    
    private Folder getOrCreateSystemSubFolder(String repositoryId, String name) {
        Folder systemFolder = contentService.getSystemFolder(repositoryId);
        
        if (systemFolder == null) {
            try {
                jp.aegif.nemaki.model.Content content = contentService.getContent(repositoryId, "34169aaa-5d6f-4685-a1d0-66bb31948877");
                if (content instanceof Folder) {
                    systemFolder = (Folder) content;
                }
            } catch (Exception e) {
                logger.severe("Failed to find .system folder via fallback: " + e.getMessage());
            }
            
            if (systemFolder == null) {
                throw ApiException.internalError(".system folder not accessible");
            }
        }
        
        List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        if (CollectionUtils.isNotEmpty(children)) {
            for (jp.aegif.nemaki.model.Content child : children) {
                if (ObjectUtils.equals(name, child.getName())) {
                    return (Folder) child;
                }
            }
        }
        
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:name", name));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
        
        return contentService.createFolder(new SystemCallContext(repositoryId), repositoryId, 
                properties, systemFolder, null, null, null, null);
    }
    
    private void setCreationSignature(GroupItem group) {
        String username = getAuthenticatedUsername();
        group.setCreator(username);
        group.setCreated(new java.util.GregorianCalendar());
        group.setModifier(username);
        group.setModified(new java.util.GregorianCalendar());
    }
    
    private void setModificationSignature(GroupItem group) {
        String username = getAuthenticatedUsername();
        group.setModifier(username);
        group.setModified(new java.util.GregorianCalendar());
    }
    
    private String getAuthenticatedUsername() {
        if (httpRequest != null && httpRequest.getUserPrincipal() != null) {
            return httpRequest.getUserPrincipal().getName();
        }
        return "system";
    }
    
    private Map<String, LinkInfo> buildListLinks(String repositoryId, int skipCount, int maxItems, int totalCount) {
        Map<String, LinkInfo> links = new HashMap<>();
        String basePath = "/api/v1/cmis/repositories/" + repositoryId + "/groups";
        
        links.put("self", new LinkInfo(basePath + "?skipCount=" + skipCount + "&maxItems=" + maxItems));
        
        if (skipCount > 0) {
            int prevSkip = Math.max(0, skipCount - maxItems);
            links.put("prev", new LinkInfo(basePath + "?skipCount=" + prevSkip + "&maxItems=" + maxItems));
        }
        
        if (skipCount + maxItems < totalCount) {
            int nextSkip = skipCount + maxItems;
            links.put("next", new LinkInfo(basePath + "?skipCount=" + nextSkip + "&maxItems=" + maxItems));
        }
        
        return links;
    }
}
