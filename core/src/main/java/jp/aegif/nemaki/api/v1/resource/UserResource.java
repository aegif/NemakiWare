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
import jp.aegif.nemaki.api.v1.model.request.PasswordChangeRequest;
import jp.aegif.nemaki.api.v1.model.request.UserRequest;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.UserListResponse;
import jp.aegif.nemaki.api.v1.model.response.UserResponse;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.DateUtil;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mindrot.jbcrypt.BCrypt;
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
@Path("/repositories/{repositoryId}/users")
@Tag(name = "users", description = "User management operations")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {
    
    private static final Logger logger = Logger.getLogger(UserResource.class.getName());
    
    @Autowired
    private ContentService contentService;
    
    @Context
    private UriInfo uriInfo;
    
        @Context
        private HttpServletRequest httpRequest;
    
        private void checkAdminAuthorization() {
            CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
            if (callContext == null) {
                throw ApiException.unauthorized("Authentication required for user management operations");
            }
            Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
            if (isAdmin == null || !isAdmin) {
                throw ApiException.permissionDenied("Only administrators can perform user management operations");
            }
        }
    
        @GET
        @Operation(
                summary = "List all users",
            description = "Gets a list of all users in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of users",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserListResponse.class)
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
    public Response listUsers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
                logger.info("API v1: Listing users for repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    List<UserItem> allUsers = ObjectUtils.defaultIfNull(
                    contentService.getUserItems(repositoryId), Collections.emptyList());
            
            int totalCount = allUsers.size();
            int endIndex = Math.min(skipCount + maxItems, totalCount);
            List<UserItem> pagedUsers = skipCount < totalCount 
                    ? allUsers.subList(skipCount, endIndex) 
                    : Collections.emptyList();
            
            List<UserResponse> userResponses = pagedUsers.stream()
                    .map(user -> convertToUserResponse(user, repositoryId))
                    .collect(Collectors.toList());
            
            UserListResponse response = new UserListResponse();
            response.setUsers(userResponses);
            response.setNumItems(totalCount);
            response.setHasMoreItems(endIndex < totalCount);
            response.setLinks(buildListLinks(repositoryId, skipCount, maxItems, totalCount));
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error listing users: " + e.getMessage());
            throw ApiException.internalError("Failed to list users: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{userId}")
    @Operation(
            summary = "Get user by ID",
            description = "Gets the specified user by user ID"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User information",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true, example = "admin")
            @PathParam("userId") String userId) {
        
                logger.info("API v1: Getting user " + userId + " from repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    UserItem user = contentService.getUserItemById(repositoryId, userId);
            
            if (user == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            UserResponse response = convertToUserResponse(user, repositoryId);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting user " + userId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get user: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/search")
    @Operation(
            summary = "Search users",
            description = "Searches for users by query string (matches userId or userName)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search results",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserListResponse.class)
                    )
            )
    })
    public Response searchUsers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search query", required = true)
            @QueryParam("query") String query,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("50") Integer maxItems) {
        
                logger.info("API v1: Searching users with query '" + query + "' in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    if (StringUtils.isBlank(query)) {
                throw ApiException.invalidArgument("Query parameter is required");
            }
            
            List<UserItem> allUsers = ObjectUtils.defaultIfNull(
                    contentService.getUserItems(repositoryId), Collections.emptyList());
            
            List<UserResponse> matchedUsers = new ArrayList<>();
            for (UserItem user : allUsers) {
                String userId = user.getUserId();
                String userName = user.getName();
                boolean matches = (StringUtils.isNotEmpty(userId) && userId.contains(query)) ||
                                  (StringUtils.isNotEmpty(userName) && userName.contains(query));
                if (matches && matchedUsers.size() < maxItems) {
                    matchedUsers.add(convertToUserResponse(user, repositoryId));
                }
            }
            
            UserListResponse response = new UserListResponse();
            response.setUsers(matchedUsers);
            response.setNumItems(matchedUsers.size());
            response.setHasMoreItems(false);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error searching users: " + e.getMessage());
            throw ApiException.internalError("Failed to search users: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create user",
            description = "Creates a new user in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "User created successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
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
                    description = "User already exists",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            UserRequest request) {
        
                checkAdminAuthorization();
        
                try {
                    if (request == null) {
                        throw ApiException.invalidArgument("Request body is required");
                    }
            
                    logger.info("API v1: Creating user " + request.getUserId() + " in repository " + repositoryId);
            
                    validateCreateUserRequest(request, repositoryId);
            
            UserItem existingUser = contentService.getUserItemById(repositoryId, request.getUserId());
            if (existingUser != null) {
                throw ApiException.conflict("User with ID '" + request.getUserId() + "' already exists");
            }
            
            String passwordHash = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());
            
            Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");
            
            UserItem user = new UserItem(null, NemakiObjectType.nemakiUser, 
                    request.getUserId(), request.getUserName(), passwordHash, false, usersFolder.getId());
            
            Map<String, Object> propMap = new HashMap<>();
            if (request.getFirstName() != null) propMap.put("nemaki:firstName", request.getFirstName());
            if (request.getLastName() != null) propMap.put("nemaki:lastName", request.getLastName());
            if (request.getEmail() != null) propMap.put("nemaki:email", request.getEmail());
            
            List<Property> properties = new ArrayList<>();
            for (String key : propMap.keySet()) {
                properties.add(new Property(key, propMap.get(key)));
            }
            user.setSubTypeProperties(properties);
            
            setCreationSignature(user);
            
            contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, user);
            
            if (request.getGroups() != null && !request.getGroups().isEmpty()) {
                updateUserGroups(repositoryId, request.getUserId(), request.getGroups());
            }
            
            UserItem createdUser = contentService.getUserItemById(repositoryId, request.getUserId());
            UserResponse response = convertToUserResponse(createdUser, repositoryId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating user: " + e.getMessage());
            throw ApiException.internalError("Failed to create user: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Update user",
            description = "Updates an existing user in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User updated successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response updateUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true)
            @PathParam("userId") String userId,
            UserRequest request) {
        
                logger.info("API v1: Updating user " + userId + " in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    UserItem user = contentService.getUserItemById(repositoryId, userId);
                    if (user == null) {
                        throw ApiException.userNotFound(userId, repositoryId);
                    }
            
                    if (request.getUserName() != null) {
                user.setName(request.getUserName());
            }
            
            Map<String, Object> propMap = new HashMap<>();
            for (Property prop : user.getSubTypeProperties()) {
                propMap.put(prop.getKey(), prop.getValue());
            }
            
            if (request.getFirstName() != null) propMap.put("nemaki:firstName", request.getFirstName());
            if (request.getLastName() != null) propMap.put("nemaki:lastName", request.getLastName());
            if (request.getEmail() != null) propMap.put("nemaki:email", request.getEmail());
            
            List<Property> properties = new ArrayList<>();
            for (String key : propMap.keySet()) {
                properties.add(new Property(key, propMap.get(key)));
            }
            user.setSubTypeProperties(properties);
            
            if (StringUtils.isNotBlank(request.getPassword())) {
                String passwordHash = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());
                user.setPassowrd(passwordHash);
            }
            
            setModificationSignature(user);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, user);
            
            if (request.getGroups() != null) {
                updateUserGroups(repositoryId, userId, request.getGroups());
            }
            
            UserItem updatedUser = contentService.getUserItemById(repositoryId, userId);
            UserResponse response = convertToUserResponse(updatedUser, repositoryId);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error updating user " + userId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to update user: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{userId}")
    @Operation(
            summary = "Delete user",
            description = "Deletes the specified user from the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "User deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true)
            @PathParam("userId") String userId) {
        
                logger.info("API v1: Deleting user " + userId + " from repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    UserItem user = contentService.getUserItemById(repositoryId, userId);
                    if (user == null) {
                        throw ApiException.userNotFound(userId, repositoryId);
                    }
            
                    removeUserFromAllGroups(repositoryId, userId);
            
            contentService.delete(new SystemCallContext(repositoryId), repositoryId, user.getId(), false);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting user " + userId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete user: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{userId}/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change password",
            description = "Changes the password for the specified user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Password changed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid old password",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response changePassword(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true)
            @PathParam("userId") String userId,
            PasswordChangeRequest request) {
        
                logger.info("API v1: Changing password for user " + userId + " in repository " + repositoryId);
        
                checkAdminAuthorization();
        
                try {
                    if (request.getOldPassword() == null || request.getNewPassword() == null) {
                throw ApiException.invalidArgument("Both oldPassword and newPassword are required");
            }
            
            UserItem user = contentService.getUserItemById(repositoryId, userId);
            if (user == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            if (!AuthenticationUtil.passwordMatches(request.getOldPassword(), user.getPassowrd())) {
                throw ApiException.invalidArgument("Old password is incorrect");
            }
            
            String newPasswordHash = BCrypt.hashpw(request.getNewPassword(), BCrypt.gensalt());
            user.setPassowrd(newPasswordHash);
            
            setModificationSignature(user);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, user);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error changing password for user " + userId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to change password: " + e.getMessage(), e);
        }
    }
    
    private void validateCreateUserRequest(UserRequest request, String repositoryId) {
        if (StringUtils.isBlank(request.getUserId())) {
            throw ApiException.invalidArgument("userId is required");
        }
        if (StringUtils.isBlank(request.getUserName())) {
            throw ApiException.invalidArgument("userName is required");
        }
        if (StringUtils.isBlank(request.getPassword())) {
            throw ApiException.invalidArgument("password is required");
        }
    }
    
    private UserResponse convertToUserResponse(UserItem user, String repositoryId) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUserName(user.getName());
        response.setType(user.getType());
        response.setCreatedBy(user.getCreator());
        response.setLastModifiedBy(user.getModifier());
        
        if (user.getCreated() != null) {
            response.setCreationDate(DateUtil.formatSystemDateTime(user.getCreated()));
        }
        if (user.getModified() != null) {
            response.setLastModificationDate(DateUtil.formatSystemDateTime(user.getModified()));
        }
        
        Map<String, Object> propMap = new HashMap<>();
        for (Property prop : user.getSubTypeProperties()) {
            propMap.put(prop.getKey(), prop.getValue());
        }
        
        response.setFirstName((String) MapUtils.getObject(propMap, "nemaki:firstName", ""));
        response.setLastName((String) MapUtils.getObject(propMap, "nemaki:lastName", ""));
        response.setEmail((String) MapUtils.getObject(propMap, "nemaki:email", ""));
        
        Object favoritesObj = propMap.get("nemaki:favorites");
        if (favoritesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> favorites = (List<String>) favoritesObj;
            response.setFavorites(favorites);
        }
        
        List<String> userGroups = getUserGroups(repositoryId, user.getUserId());
        response.setGroups(userGroups);
        
        response.setIsAdmin(userGroups.contains("admin") || "admin".equals(user.getUserId()));
        
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/users/" + user.getUserId()));
        response.setLinks(links);
        
        return response;
    }
    
    private List<String> getUserGroups(String repositoryId, String userId) {
        List<String> userGroups = new ArrayList<>();
        List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                contentService.getGroupItems(repositoryId), Collections.emptyList());
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers();
            if (members != null && members.contains(userId)) {
                userGroups.add(group.getGroupId());
            }
        }
        
        return userGroups;
    }
    
    private void updateUserGroups(String repositoryId, String userId, List<String> targetGroups) {
        List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                contentService.getGroupItems(repositoryId), Collections.emptyList());
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers() != null ? new ArrayList<>(group.getUsers()) : new ArrayList<>();
            boolean isMember = members.contains(userId);
            boolean shouldBeMember = targetGroups.contains(group.getGroupId());
            
            if (shouldBeMember && !isMember) {
                members.add(userId);
                group.setUsers(members);
                contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
            } else if (!shouldBeMember && isMember) {
                members.remove(userId);
                group.setUsers(members);
                contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
            }
        }
    }
    
    private void removeUserFromAllGroups(String repositoryId, String userId) {
        List<GroupItem> allGroups = ObjectUtils.defaultIfNull(
                contentService.getGroupItems(repositoryId), Collections.emptyList());
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers();
            if (members != null && members.contains(userId)) {
                List<String> updatedMembers = new ArrayList<>(members);
                updatedMembers.remove(userId);
                group.setUsers(updatedMembers);
                contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
                logger.info("Removed user " + userId + " from group " + group.getGroupId());
            }
        }
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
    
    private void setCreationSignature(UserItem user) {
        String username = getAuthenticatedUsername();
        user.setCreator(username);
        user.setCreated(new java.util.GregorianCalendar());
        user.setModifier(username);
        user.setModified(new java.util.GregorianCalendar());
    }
    
    private void setModificationSignature(UserItem user) {
        String username = getAuthenticatedUsername();
        user.setModifier(username);
        user.setModified(new java.util.GregorianCalendar());
    }
    
    private String getAuthenticatedUsername() {
        if (httpRequest != null && httpRequest.getUserPrincipal() != null) {
            return httpRequest.getUserPrincipal().getName();
        }
        return "system";
    }
    
    private Map<String, LinkInfo> buildListLinks(String repositoryId, int skipCount, int maxItems, int totalCount) {
        Map<String, LinkInfo> links = new HashMap<>();
        String basePath = "/api/v1/cmis/repositories/" + repositoryId + "/users";
        
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
