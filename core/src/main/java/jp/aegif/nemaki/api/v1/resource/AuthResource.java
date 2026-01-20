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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.request.LoginRequest;
import jp.aegif.nemaki.api.v1.model.response.AuthResponse;
import jp.aegif.nemaki.api.v1.model.response.CurrentUserResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.commons.lang3.StringUtils;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/auth")
@Tag(name = "auth", description = "Authentication operations")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    
    private static final Logger logger = Logger.getLogger(AuthResource.class.getName());
    private static final String DEFAULT_REPOSITORY_ID = "bedroom";
    private static final long TOKEN_EXPIRATION_SECONDS = 3600L;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Autowired
    private ContentService contentService;
    
    @Autowired(required = false)
    private TokenService tokenService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Login and obtain authentication token",
            description = "Authenticates a user and returns an authentication token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
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
                    responseCode = "401",
                    description = "Invalid credentials",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response login(
            @Parameter(description = "Login request", required = true)
            LoginRequest request) {
        
        logger.info("API v1: Login attempt for user: " + request.getUserId());
        
        try {
            if (StringUtils.isBlank(request.getUserId())) {
                throw ApiException.invalidArgument("User ID is required");
            }
            if (StringUtils.isBlank(request.getPassword())) {
                throw ApiException.invalidArgument("Password is required");
            }
            
            String repositoryId = StringUtils.isNotBlank(request.getRepositoryId()) 
                    ? request.getRepositoryId() 
                    : DEFAULT_REPOSITORY_ID;
            
            if (!repositoryService.hasThisRepositoryId(repositoryId)) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            UserItem userItem = contentService.getUserItemById(repositoryId, request.getUserId());
            if (userItem == null) {
                throw ApiException.unauthorized("Invalid credentials");
            }
            
            String storedPassword = userItem.getPassword();
            if (storedPassword == null || !BCrypt.checkpw(request.getPassword(), storedPassword)) {
                throw ApiException.unauthorized("Invalid credentials");
            }
            
            String token = generateToken(repositoryId, request.getUserId());
            
            AuthResponse response = new AuthResponse();
            response.setToken(token);
            response.setTokenType("Bearer");
            response.setExpiresIn(TOKEN_EXPIRATION_SECONDS);
            response.setUserId(userItem.getUserId());
            response.setName(userItem.getName());
            response.setIsAdmin(userItem.isAdmin());
            response.setGroups(getUserGroups(repositoryId, request.getUserId()));
            response.setRepositoryId(repositoryId);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during login: " + e.getMessage());
            throw ApiException.internalError("Login failed: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/logout")
    @Operation(
            summary = "Logout and invalidate token",
            description = "Invalidates the current authentication token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Logout successful"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Not authenticated",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response logout() {
        logger.info("API v1: Logout request");
        
        try {
            String token = extractToken();
            
            if (tokenService != null && StringUtils.isNotBlank(token)) {
                tokenService.invalidateToken(token);
            }
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "Logout successful");
            
            return Response.ok(response).build();
        } catch (Exception e) {
            logger.severe("Error during logout: " + e.getMessage());
            throw ApiException.internalError("Logout failed: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/token/refresh")
    @Operation(
            summary = "Refresh authentication token",
            description = "Refreshes the current authentication token and returns a new one"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token refreshed successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid or expired token",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response refreshToken(
            @Parameter(description = "Repository ID", example = "bedroom")
            @QueryParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Token refresh request");
        
        try {
            String currentUserId = getCurrentUserId();
            if (StringUtils.isBlank(currentUserId)) {
                throw ApiException.unauthorized("Not authenticated");
            }
            
            String repoId = StringUtils.isNotBlank(repositoryId) ? repositoryId : DEFAULT_REPOSITORY_ID;
            
            if (!repositoryService.hasThisRepositoryId(repoId)) {
                throw ApiException.repositoryNotFound(repoId);
            }
            
            UserItem userItem = contentService.getUserItemById(repoId, currentUserId);
            if (userItem == null) {
                throw ApiException.unauthorized("User not found");
            }
            
            String newToken = generateToken(repoId, currentUserId);
            
            AuthResponse response = new AuthResponse();
            response.setToken(newToken);
            response.setTokenType("Bearer");
            response.setExpiresIn(TOKEN_EXPIRATION_SECONDS);
            response.setUserId(userItem.getUserId());
            response.setName(userItem.getName());
            response.setIsAdmin(userItem.isAdmin());
            response.setGroups(getUserGroups(repoId, currentUserId));
            response.setRepositoryId(repoId);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error refreshing token: " + e.getMessage());
            throw ApiException.internalError("Token refresh failed: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/me")
    @Operation(
            summary = "Get current user information",
            description = "Returns information about the currently authenticated user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Current user information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = CurrentUserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Not authenticated",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getCurrentUser(
            @Parameter(description = "Repository ID for user details", example = "bedroom")
            @QueryParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Get current user request");
        
        try {
            String currentUserId = getCurrentUserId();
            if (StringUtils.isBlank(currentUserId)) {
                throw ApiException.unauthorized("Not authenticated");
            }
            
            String repoId = StringUtils.isNotBlank(repositoryId) ? repositoryId : DEFAULT_REPOSITORY_ID;
            
            UserItem userItem = null;
            if (repositoryService.hasThisRepositoryId(repoId)) {
                userItem = contentService.getUserItemById(repoId, currentUserId);
            }
            
            CurrentUserResponse response = new CurrentUserResponse();
            response.setUserId(currentUserId);
            
            if (userItem != null) {
                response.setName(userItem.getName());
                response.setIsAdmin(userItem.isAdmin());
                response.setFirstName(getPropertyValue(userItem, "nemaki:firstName"));
                response.setLastName(getPropertyValue(userItem, "nemaki:lastName"));
                response.setEmail(getPropertyValue(userItem, "nemaki:email"));
                response.setGroups(getUserGroups(repoId, currentUserId));
            } else {
                response.setName(currentUserId);
                response.setIsAdmin("admin".equals(currentUserId));
                response.setGroups(new ArrayList<>());
            }
            
            List<String> repositories = new ArrayList<>();
            List<RepositoryInfo> repoInfos = repositoryService.getRepositoryInfos();
            for (RepositoryInfo info : repoInfos) {
                repositories.add(info.getId());
            }
            response.setRepositories(repositories);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "auth/me"));
            links.put("logout", LinkInfo.of(baseUri + "auth/logout"));
            response.setLinks(links);
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting current user: " + e.getMessage());
            throw ApiException.internalError("Failed to get current user: " + e.getMessage(), e);
        }
    }
    
    private String generateToken(String repositoryId, String userId) {
        if (tokenService != null) {
            try {
                return tokenService.generateToken(repositoryId, userId);
            } catch (Exception e) {
                logger.warning("TokenService failed, using fallback: " + e.getMessage());
            }
        }
        
        return java.util.Base64.getEncoder().encodeToString(
                (repositoryId + ":" + userId + ":" + System.currentTimeMillis()).getBytes()
        );
    }
    
    private String extractToken() {
        String authHeader = httpRequest.getHeader("Authorization");
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
    
    private String getCurrentUserId() {
        return AuthenticationUtil.getUserIdFromRequest(httpRequest);
    }
    
    private List<String> getUserGroups(String repositoryId, String userId) {
        List<String> userGroups = new ArrayList<>();
        
        try {
            List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
            for (GroupItem group : allGroups) {
                List<String> members = group.getUsers();
                if (members != null && members.contains(userId)) {
                    userGroups.add(group.getGroupId());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to get user groups: " + e.getMessage());
        }
        
        return userGroups;
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
}
