package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import jp.aegif.nemaki.api.v1.model.request.PasswordChangeRequest;
import jp.aegif.nemaki.api.v1.model.request.UserCreateRequest;
import jp.aegif.nemaki.api.v1.model.request.UserUpdateRequest;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.UserListResponse;
import jp.aegif.nemaki.api.v1.model.response.UserResponse;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;

import org.apache.commons.lang3.StringUtils;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/users")
@Tag(name = "users", description = "User management operations")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {
    
    private static final Logger logger = Logger.getLogger(UserResource.class.getName());
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
            summary = "List all users",
            description = "Returns a list of all users in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of users",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserListResponse.class)
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
    public Response listUsers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Listing all users for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            List<UserItem> userItems = contentService.getUserItems(repositoryId);
            
            List<UserResponse> users = new ArrayList<>();
            for (UserItem userItem : userItems) {
                users.add(mapToResponse(userItem, repositoryId));
            }
            
            UserListResponse response = new UserListResponse();
            response.setUsers(users);
            response.setTotalCount(users.size());
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/users"));
            response.setLinks(links);
            
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
            description = "Returns information about a specific user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
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
                    description = "User not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true, example = "john.doe")
            @PathParam("userId") String userId) {
        
        logger.info("API v1: Getting user " + userId + " for repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            UserItem userItem = contentService.getUserItemById(repositoryId, userId);
            if (userItem == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            UserResponse response = mapToResponse(userItem, repositoryId);
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
            description = "Search users by query string (matches userId or name)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Search results",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserListResponse.class)
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
    public Response searchUsers(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search query", required = true, example = "john")
            @QueryParam("query") String query,
            @Parameter(description = "Maximum number of results", example = "50")
            @QueryParam("maxResults") Integer maxResults) {
        
        logger.info("API v1: Searching users with query: " + query);
        
        try {
            validateRepository(repositoryId);
            
            if (StringUtils.isBlank(query)) {
                throw ApiException.invalidArgument("Query parameter is required");
            }
            
            int limit = maxResults != null ? maxResults : 50;
            
            List<UserItem> allUsers = contentService.getUserItems(repositoryId);
            List<UserResponse> matchingUsers = new ArrayList<>();
            
            for (UserItem userItem : allUsers) {
                if (matchingUsers.size() >= limit) {
                    break;
                }
                
                String userId = userItem.getUserId();
                String userName = userItem.getName();
                
                boolean matches = (StringUtils.isNotEmpty(userId) && userId.toLowerCase().contains(query.toLowerCase())) ||
                                  (StringUtils.isNotEmpty(userName) && userName.toLowerCase().contains(query.toLowerCase()));
                
                if (matches) {
                    matchingUsers.add(mapToResponse(userItem, repositoryId));
                }
            }
            
            UserListResponse response = new UserListResponse();
            response.setUsers(matchingUsers);
            response.setTotalCount(matchingUsers.size());
            
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
            summary = "Create a new user",
            description = "Creates a new user in the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "User created successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
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
                    description = "User already exists",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User creation request", required = true)
            UserCreateRequest request) {
        
        logger.info("API v1: Creating user " + request.getUserId() + " in repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            validateUserCreateRequest(request);
            
            UserItem existingUser = contentService.getUserItemById(repositoryId, request.getUserId());
            if (existingUser != null) {
                throw ApiException.conflict("User with ID '" + request.getUserId() + "' already exists");
            }
            
            String passwordHash = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());
            
            Folder usersFolder = getOrCreateUsersFolder(repositoryId);
            
            UserItem userItem = new UserItem(
                    null,
                    NemakiObjectType.nemakiUser,
                    request.getUserId(),
                    request.getName(),
                    passwordHash,
                    request.getIsAdmin() != null ? request.getIsAdmin() : false,
                    usersFolder.getId()
            );
            
            List<Property> properties = new ArrayList<>();
            if (request.getFirstName() != null) {
                properties.add(new Property("nemaki:firstName", request.getFirstName()));
            }
            if (request.getLastName() != null) {
                properties.add(new Property("nemaki:lastName", request.getLastName()));
            }
            if (request.getEmail() != null) {
                properties.add(new Property("nemaki:email", request.getEmail()));
            }
            userItem.setSubTypeProperties(properties);
            
            setCreationSignature(userItem);
            
            contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, userItem);
            
            if (request.getGroups() != null && !request.getGroups().isEmpty()) {
                updateUserGroups(repositoryId, request.getUserId(), request.getGroups());
            }
            
            UserItem createdUser = contentService.getUserItemById(repositoryId, request.getUserId());
            UserResponse response = mapToResponse(createdUser, repositoryId);
            
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
            summary = "Update a user",
            description = "Updates an existing user's information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
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
                    description = "User not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response updateUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true, example = "john.doe")
            @PathParam("userId") String userId,
            @Parameter(description = "User update request", required = true)
            UserUpdateRequest request) {
        
        logger.info("API v1: Updating user " + userId + " in repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            UserItem userItem = contentService.getUserItemById(repositoryId, userId);
            if (userItem == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            if (request.getName() != null) {
                userItem.setName(request.getName());
            }
            if (request.getIsAdmin() != null) {
                userItem.setAdmin(request.getIsAdmin());
            }
            
            List<Property> properties = userItem.getSubTypeProperties();
            if (properties == null) {
                properties = new ArrayList<>();
            }
            
            updateProperty(properties, "nemaki:firstName", request.getFirstName());
            updateProperty(properties, "nemaki:lastName", request.getLastName());
            updateProperty(properties, "nemaki:email", request.getEmail());
            
            userItem.setSubTypeProperties(properties);
            setModificationSignature(userItem);
            
            contentService.updateUserItem(new SystemCallContext(repositoryId), repositoryId, userItem);
            
            if (request.getGroups() != null) {
                updateUserGroups(repositoryId, userId, request.getGroups());
            }
            
            UserItem updatedUser = contentService.getUserItemById(repositoryId, userId);
            UserResponse response = mapToResponse(updatedUser, repositoryId);
            
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
            summary = "Delete a user",
            description = "Deletes a user from the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "User deleted successfully"
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
                    description = "User not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true, example = "john.doe")
            @PathParam("userId") String userId) {
        
        logger.info("API v1: Deleting user " + userId + " from repository: " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            checkAdminAccess(repositoryId);
            
            UserItem userItem = contentService.getUserItemById(repositoryId, userId);
            if (userItem == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            if ("admin".equals(userId)) {
                throw ApiException.constraintViolation("Cannot delete the admin user");
            }
            
            removeUserFromAllGroups(repositoryId, userId);
            
            contentService.deleteUserItem(new SystemCallContext(repositoryId), repositoryId, userId);
            
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
            summary = "Change user password",
            description = "Changes a user's password. Admin users can change any user's password without providing the current password."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Password changed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request (e.g., wrong current password)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "User not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response changePassword(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "User ID", required = true, example = "john.doe")
            @PathParam("userId") String userId,
            @Parameter(description = "Password change request", required = true)
            PasswordChangeRequest request) {
        
        logger.info("API v1: Changing password for user " + userId);
        
        try {
            validateRepository(repositoryId);
            
            if (StringUtils.isBlank(request.getNewPassword())) {
                throw ApiException.invalidArgument("New password is required");
            }
            
            UserItem userItem = contentService.getUserItemById(repositoryId, userId);
            if (userItem == null) {
                throw ApiException.userNotFound(userId, repositoryId);
            }
            
            String currentUserId = getCurrentUserId();
            boolean isAdmin = isCurrentUserAdmin(repositoryId);
            
            if (!isAdmin && !userId.equals(currentUserId)) {
                throw ApiException.permissionDenied("You can only change your own password");
            }
            
            if (!isAdmin && userId.equals(currentUserId)) {
                if (StringUtils.isBlank(request.getCurrentPassword())) {
                    throw ApiException.invalidArgument("Current password is required");
                }
                
                if (!BCrypt.checkpw(request.getCurrentPassword(), userItem.getPassword())) {
                    throw ApiException.invalidArgument("Current password is incorrect");
                }
            }
            
            String newPasswordHash = BCrypt.hashpw(request.getNewPassword(), BCrypt.gensalt());
            userItem.setPassowrd(newPasswordHash);
            setModificationSignature(userItem);
            
            contentService.updateUserItem(new SystemCallContext(repositoryId), repositoryId, userItem);
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "Password changed successfully");
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error changing password for user " + userId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to change password: " + e.getMessage(), e);
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
    
    private void validateUserCreateRequest(UserCreateRequest request) {
        if (StringUtils.isBlank(request.getUserId())) {
            throw ApiException.invalidArgument("User ID is required");
        }
        if (StringUtils.isBlank(request.getName())) {
            throw ApiException.invalidArgument("Name is required");
        }
        if (StringUtils.isBlank(request.getPassword())) {
            throw ApiException.invalidArgument("Password is required");
        }
    }
    
    private Folder getOrCreateUsersFolder(String repositoryId) {
        Folder systemFolder = contentService.getSystemFolder(repositoryId);
        if (systemFolder == null) {
            throw ApiException.internalError("System folder not found");
        }
        
        List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        for (jp.aegif.nemaki.model.Content child : children) {
            if ("users".equals(child.getName()) && child instanceof Folder) {
                return (Folder) child;
            }
        }
        
        Folder usersFolder = new Folder();
        usersFolder.setName("users");
        usersFolder.setObjectType("cmis:folder");
        usersFolder.setParentId(systemFolder.getId());
        
        contentService.createFolder(new SystemCallContext(repositoryId), repositoryId, usersFolder);
        
        children = contentService.getChildren(repositoryId, systemFolder.getId());
        for (jp.aegif.nemaki.model.Content child : children) {
            if ("users".equals(child.getName()) && child instanceof Folder) {
                return (Folder) child;
            }
        }
        
        throw ApiException.internalError("Failed to create users folder");
    }
    
    private void updateUserGroups(String repositoryId, String userId, List<String> newGroupIds) {
        List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers();
            boolean isMember = members != null && members.contains(userId);
            boolean shouldBeMember = newGroupIds.contains(group.getGroupId());
            
            if (isMember && !shouldBeMember) {
                List<String> newMembers = new ArrayList<>(members);
                newMembers.remove(userId);
                group.setUsers(newMembers);
                contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            } else if (!isMember && shouldBeMember) {
                List<String> newMembers = members != null ? new ArrayList<>(members) : new ArrayList<>();
                newMembers.add(userId);
                group.setUsers(newMembers);
                contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            }
        }
    }
    
    private void removeUserFromAllGroups(String repositoryId, String userId) {
        List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers();
            if (members != null && members.contains(userId)) {
                List<String> newMembers = new ArrayList<>(members);
                newMembers.remove(userId);
                group.setUsers(newMembers);
                contentService.updateGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            }
        }
    }
    
    private List<String> getUserGroups(String repositoryId, String userId) {
        List<String> userGroups = new ArrayList<>();
        List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
        
        for (GroupItem group : allGroups) {
            List<String> members = group.getUsers();
            if (members != null && members.contains(userId)) {
                userGroups.add(group.getGroupId());
            }
        }
        
        return userGroups;
    }
    
    private void updateProperty(List<Property> properties, String key, String value) {
        if (value == null) {
            return;
        }
        
        for (Property prop : properties) {
            if (key.equals(prop.getKey())) {
                prop.setValue(value);
                return;
            }
        }
        
        properties.add(new Property(key, value));
    }
    
    private String getPropertyValue(UserItem userItem, String key) {
        List<Property> properties = userItem.getSubTypeProperties();
        if (properties == null) {
            return null;
        }
        
        for (Property prop : properties) {
            if (key.equals(prop.getKey()) && prop.getValue() != null) {
                return prop.getValue().toString();
            }
        }
        
        return null;
    }
    
    private void setCreationSignature(UserItem userItem) {
        String userId = getCurrentUserId();
        java.util.Date now = new java.util.Date();
        userItem.setCreator(userId);
        userItem.setCreated(new java.util.GregorianCalendar());
        userItem.getCreated().setTime(now);
        userItem.setModifier(userId);
        userItem.setModified(new java.util.GregorianCalendar());
        userItem.getModified().setTime(now);
    }
    
    private void setModificationSignature(UserItem userItem) {
        String userId = getCurrentUserId();
        java.util.Date now = new java.util.Date();
        userItem.setModifier(userId);
        userItem.setModified(new java.util.GregorianCalendar());
        userItem.getModified().setTime(now);
    }
    
    private UserResponse mapToResponse(UserItem userItem, String repositoryId) {
        UserResponse response = new UserResponse();
        
        response.setUserId(userItem.getUserId());
        response.setName(userItem.getName());
        response.setIsAdmin(userItem.isAdmin());
        response.setFirstName(getPropertyValue(userItem, "nemaki:firstName"));
        response.setLastName(getPropertyValue(userItem, "nemaki:lastName"));
        response.setEmail(getPropertyValue(userItem, "nemaki:email"));
        response.setGroups(getUserGroups(repositoryId, userItem.getUserId()));
        
        if (userItem.getCreated() != null) {
            response.setCreated(ISO_DATE_FORMAT.format(userItem.getCreated().getTime()));
        }
        if (userItem.getModified() != null) {
            response.setModified(ISO_DATE_FORMAT.format(userItem.getModified().getTime()));
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/users/" + userItem.getUserId()));
        response.setLinks(links);
        
        return response;
    }
}
