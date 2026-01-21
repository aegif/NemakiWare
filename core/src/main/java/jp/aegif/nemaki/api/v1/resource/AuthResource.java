package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.request.LoginRequest;
import jp.aegif.nemaki.api.v1.model.response.AuthResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.UserResponse;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
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
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Component
@Path("/auth")
@Tag(name = "auth", description = "Authentication operations")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    
    private static final Logger logger = Logger.getLogger(AuthResource.class.getName());
    
    @Autowired
    private ContentService contentService;
    
    @Autowired
    private TokenService tokenService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Path("/repositories/{repositoryId}/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Login",
            description = "Authenticates a user and returns an access token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid credentials",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response login(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            LoginRequest request) {
        
        try {
            if (request == null) {
                throw ApiException.invalidArgument("Request body is required");
            }
            
            logger.info("API v1: Login attempt for user " + request.getUserId() + " in repository " + repositoryId);
            
            if (StringUtils.isBlank(request.getUserId())) {
                throw ApiException.invalidArgument("userId is required");
            }
            if (StringUtils.isBlank(request.getPassword())) {
                throw ApiException.invalidArgument("password is required");
            }
            
            UserItem user = contentService.getUserItemById(repositoryId, request.getUserId());
            if (user == null) {
                throw ApiException.unauthorized("Invalid credentials");
            }
            
            if (!AuthenticationUtil.passwordMatches(request.getPassword(), user.getPassowrd())) {
                throw ApiException.unauthorized("Invalid credentials");
            }
            
            if (tokenService == null) {
                throw ApiException.internalError("Token service not available");
            }
            
            Token token = tokenService.setToken("", repositoryId, request.getUserId());
            
            AuthResponse response = new AuthResponse();
            response.setToken(token.getToken());
            response.setExpiresAt(token.getExpiration());
            response.setRepositoryId(repositoryId);
            response.setUser(convertToUserResponse(user, repositoryId));
            
            logger.info("API v1: Login successful for user " + request.getUserId());
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during login: " + e.getMessage());
            throw ApiException.internalError("Login failed: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/repositories/{repositoryId}/logout")
    @Operation(
            summary = "Logout",
            description = "Invalidates the current access token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Logout successful"
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Not authenticated",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response logout(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Logout request for repository " + repositoryId);
        
        try {
            String username = getAuthenticatedUsername(repositoryId);
            if (username == null || "anonymous".equals(username)) {
                throw ApiException.unauthorized("Not authenticated");
            }
            
            if (tokenService != null) {
                tokenService.removeToken("", repositoryId, username);
            }
            
            logger.info("API v1: Logout successful for user " + username);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during logout: " + e.getMessage());
            throw ApiException.internalError("Logout failed: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/repositories/{repositoryId}/token/refresh")
    @Operation(
            summary = "Refresh token",
            description = "Refreshes the current access token and returns a new one"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token refreshed successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Not authenticated or token expired",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response refreshToken(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Token refresh request for repository " + repositoryId);
        
        try {
            String username = getAuthenticatedUsername(repositoryId);
            if (username == null || "anonymous".equals(username)) {
                throw ApiException.unauthorized("Not authenticated");
            }
            
            UserItem user = contentService.getUserItemById(repositoryId, username);
            if (user == null) {
                throw ApiException.unauthorized("User not found");
            }
            
            if (tokenService == null) {
                throw ApiException.internalError("Token service not available");
            }
            
            Token token = tokenService.setToken("", repositoryId, username);
            
            AuthResponse response = new AuthResponse();
            response.setToken(token.getToken());
            response.setExpiresAt(token.getExpiration());
            response.setRepositoryId(repositoryId);
            response.setUser(convertToUserResponse(user, repositoryId));
            
            logger.info("API v1: Token refresh successful for user " + username);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during token refresh: " + e.getMessage());
            throw ApiException.internalError("Token refresh failed: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/repositories/{repositoryId}/me")
    @Operation(
            summary = "Get current user",
            description = "Gets information about the currently authenticated user"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Current user information",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Not authenticated",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getCurrentUser(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Get current user request for repository " + repositoryId);
        
        try {
            String username = getAuthenticatedUsername(repositoryId);
            if (username == null || "anonymous".equals(username)) {
                throw ApiException.unauthorized("Not authenticated");
            }
            
            UserItem user = contentService.getUserItemById(repositoryId, username);
            if (user == null) {
                throw ApiException.userNotFound(username, repositoryId);
            }
            
            UserResponse response = convertToUserResponse(user, repositoryId);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting current user: " + e.getMessage());
            throw ApiException.internalError("Failed to get current user: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/repositories/{repositoryId}/saml")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "SAML authentication",
            description = "Authenticates a user using a SAML response and returns an access token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "SAML authentication successful",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid SAML response",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response samlAuth(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            Map<String, String> request) {
        
        logger.info("API v1: SAML authentication request for repository " + repositoryId);
        
        try {
            String samlResponse = request.get("saml_response");
            if (StringUtils.isBlank(samlResponse)) {
                throw ApiException.invalidArgument("saml_response is required");
            }
            
            String username = extractUserNameFromSAMLResponse(samlResponse);
            if (StringUtils.isBlank(username)) {
                throw ApiException.unauthorized("Could not extract username from SAML response");
            }
            
            UserItem user = getOrCreateUser(repositoryId, username);
            if (user == null) {
                throw ApiException.internalError("Failed to create or find user");
            }
            
            if (tokenService == null) {
                throw ApiException.internalError("Token service not available");
            }
            
            Token token = tokenService.setToken("", repositoryId, username);
            
            AuthResponse response = new AuthResponse();
            response.setToken(token.getToken());
            response.setExpiresAt(token.getExpiration());
            response.setRepositoryId(repositoryId);
            response.setUser(convertToUserResponse(user, repositoryId));
            
            logger.info("API v1: SAML authentication successful for user " + username);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during SAML authentication: " + e.getMessage());
            throw ApiException.internalError("SAML authentication failed: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/repositories/{repositoryId}/oidc")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "OIDC authentication",
            description = "Authenticates a user using OIDC user info and returns an access token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "OIDC authentication successful",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid OIDC user info",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response oidcAuth(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            Map<String, Object> request) {
        
        logger.info("API v1: OIDC authentication request for repository " + repositoryId);
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> userInfo = (Map<String, Object>) request.get("user_info");
            if (userInfo == null) {
                throw ApiException.invalidArgument("user_info is required");
            }
            
            String username = extractUserNameFromOIDCUserInfo(userInfo);
            if (StringUtils.isBlank(username)) {
                throw ApiException.unauthorized("Could not extract username from OIDC user info");
            }
            
            UserItem user = getOrCreateUser(repositoryId, username);
            if (user == null) {
                throw ApiException.internalError("Failed to create or find user");
            }
            
            if (tokenService == null) {
                throw ApiException.internalError("Token service not available");
            }
            
            Token token = tokenService.setToken("", repositoryId, username);
            
            AuthResponse response = new AuthResponse();
            response.setToken(token.getToken());
            response.setExpiresAt(token.getExpiration());
            response.setRepositoryId(repositoryId);
            response.setUser(convertToUserResponse(user, repositoryId));
            
            logger.info("API v1: OIDC authentication successful for user " + username);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error during OIDC authentication: " + e.getMessage());
            throw ApiException.internalError("OIDC authentication failed: " + e.getMessage(), e);
        }
    }
    
    private String getAuthenticatedUsername(String repositoryId) {
        if (httpRequest != null && httpRequest.getUserPrincipal() != null) {
            return httpRequest.getUserPrincipal().getName();
        }
        
        String authHeader = httpRequest != null ? httpRequest.getHeader("Authorization") : null;
        if (authHeader != null) {
            if (authHeader.startsWith("Basic ")) {
                try {
                    String credentials = new String(Base64.getDecoder().decode(authHeader.substring(6)));
                    int colonIndex = credentials.indexOf(':');
                    if (colonIndex > 0) {
                        return credentials.substring(0, colonIndex);
                    }
                } catch (Exception e) {
                    logger.warning("Failed to decode Basic auth header: " + e.getMessage());
                }
            } else if (authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                return getUsernameFromBearerToken(repositoryId, token);
            }
        }
        
        return null;
    }
    
    private String getUsernameFromBearerToken(String repositoryId, String token) {
        if (tokenService == null || StringUtils.isBlank(token)) {
            return null;
        }
        return tokenService.validateToken("", repositoryId, token);
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
    
    /**
     * SECURITY WARNING: This is a simplified SAML implementation for development/testing purposes only.
     * It does NOT perform proper SAML signature verification, Issuer validation, or Condition checks.
     * For production use, integrate a proper SAML library (e.g., OpenSAML) with full security validation.
     * Using this implementation in production could allow arbitrary NameID injection attacks.
     */
    private String extractUserNameFromSAMLResponse(String samlResponse) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(samlResponse);
            String xml = new String(decodedBytes);
            
            int nameIdStart = xml.indexOf("<saml:NameID");
            if (nameIdStart == -1) {
                nameIdStart = xml.indexOf("<NameID");
            }
            if (nameIdStart != -1) {
                int valueStart = xml.indexOf(">", nameIdStart) + 1;
                int valueEnd = xml.indexOf("<", valueStart);
                if (valueStart > 0 && valueEnd > valueStart) {
                    return xml.substring(valueStart, valueEnd).trim();
                }
            }
            
            return null;
        } catch (Exception e) {
            logger.warning("Failed to extract username from SAML response: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * SECURITY WARNING: This is a simplified OIDC implementation for development/testing purposes only.
     * It does NOT perform proper ID token signature verification or access token validation.
     * The user_info is accepted directly from the client without verification against the IdP.
     * For production use, implement proper OIDC token validation with signature verification.
     */
    private String extractUserNameFromOIDCUserInfo(Map<String, Object> userInfo) {
        if (userInfo.containsKey("preferred_username")) {
            return (String) userInfo.get("preferred_username");
        }
        if (userInfo.containsKey("email")) {
            return (String) userInfo.get("email");
        }
        if (userInfo.containsKey("sub")) {
            return (String) userInfo.get("sub");
        }
        return null;
    }
    
    private UserItem getOrCreateUser(String repositoryId, String userName) {
        try {
            UserItem userItem = contentService.getUserItemById(repositoryId, userName);
            if (userItem != null) {
                return userItem;
            }
            
            Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");
            if (usersFolder == null) {
                logger.severe("Failed to get or create users folder for SSO user: " + userName);
                return null;
            }
            
            String randomPassword = UUID.randomUUID().toString();
            String passwordHash = BCrypt.hashpw(randomPassword, BCrypt.gensalt());
            
            UserItem newUser = new UserItem(
                null,
                NemakiObjectType.nemakiUser,
                userName,
                userName,
                passwordHash,
                false,
                usersFolder.getId()
            );
            
            newUser.setCreator(userName);
            newUser.setModifier(userName);
            newUser.setCreated(new java.util.GregorianCalendar());
            newUser.setModified(new java.util.GregorianCalendar());
            
            contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, newUser);
            
            return contentService.getUserItemById(repositoryId, userName);
            
        } catch (Exception e) {
            logger.severe("Failed to get or create user: " + userName + " - " + e.getMessage());
            return null;
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
                return null;
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
}
