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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    @Autowired
    private jp.aegif.nemaki.cmis.factory.auth.AuthenticationService authenticationService;

    /** Verifies the OIDC login's ID token (3.4.1, zero-trust P0). */
    private jp.aegif.nemaki.rest.OidcLogin oidcLogin = new jp.aegif.nemaki.rest.OidcLogin();
    
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
            
            // Count the guesses. This door checks the password itself instead of going through
            // AuthenticationService.login, so until 3.4.1 nothing counted it: the same guessing that
            // locks out after 15 attempts over REST, CMIS and MCP was unlimited here, and it kept
            // answering while the account was locked out everywhere else (round-5 review). Same
            // counter and key as the Basic-auth path, so a lockout holds across the doors.
            if (authenticationService == null) {
                // No counter, no door. @Autowired without required=false means this cannot happen
                // in a wired context, but the alternative stance — carry on unthrottled — is the
                // one this endpoint had until 3.4.1, and the filter next door refuses for exactly
                // this reason (round-6 review, P3).
                logger.severe("API v1: the authentication service is not available, so password"
                        + " attempts cannot be counted; refusing the login");
                throw ApiException.internalError("Authentication service not available");
            }
            if (authenticationService.isPasswordGuessingBlocked(repositoryId, request.getUserId(),
                            httpRequest)) {
                logger.warning("API v1: login temporarily locked (too many failed attempts) for user "
                        + request.getUserId() + " in repository " + repositoryId);
                throw ApiException.unauthorized("Invalid credentials");
            }

            UserItem user = contentService.getUserItemById(repositoryId, request.getUserId());
            if (user == null) {
                // An unknown name is not counted: a probe for which accounts exist must not be able
                // to lock out a name that does not, and the throttle's own contract counts genuine
                // password attempts only.
                throw ApiException.unauthorized("Invalid credentials");
            }
            
            if (!AuthenticationUtil.passwordMatches(request.getPassword(), user.getPassowrd())) {
                recordPasswordFailure(repositoryId, request.getUserId());
                throw ApiException.unauthorized("Invalid credentials");
            }

            // Enforce the account's allowedAuthMethods policy so a disabled or
            // cloud-only account cannot authenticate via password (parity with the
            // primary CMIS auth path). Return the same 401 as a wrong password so a
            // correct password on a disabled account is not an authentication oracle.
            if (!AuthenticationUtil.isAuthMethodAllowed(user, "password")) {
                logger.info("API v1: password authentication denied for user " + request.getUserId()
                        + " (not in allowedAuthMethods)");
                // Not cleared: the password was right but the account may not use it, and clearing
                // here would let a correct password on a disabled account reset the counter.
                throw ApiException.unauthorized("Invalid credentials");
            }

            recordPasswordSuccess(repositoryId, request.getUserId());

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

    /**
     * Count a wrong password at this door, on the shared counter. No null guard: the login refuses
     * before reaching here when the service is absent, so a guard would only hide a wiring fault
     * (round-6 review).
     */
    private void recordPasswordFailure(String repositoryId, String userId) {
        authenticationService.recordPasswordFailure(repositoryId, userId, httpRequest);
    }

    /** Clear the count after a password that was right AND allowed by the account's policy. */
    private void recordPasswordSuccess(String repositoryId, String userId) {
        authenticationService.recordPasswordSuccess(repositoryId, userId, httpRequest);
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
            String username = getAuthenticatedUsername();
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
            String username = getAuthenticatedUsername();
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
            String username = getAuthenticatedUsername();
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

        // SECURITY FIX: SAML response signature verification is not implemented.
        // Without signature verification, an attacker can forge arbitrary SAML responses
        // and impersonate any user. This endpoint is disabled until proper SAML signature
        // validation (e.g., via OpenSAML) is implemented.
        logger.warning("API v1: SAML authentication rejected - signature verification not implemented");
        throw ApiException.internalError(
                "SAML authentication is not available. SAML response signature verification " +
                "is not implemented. Use OIDC authentication instead.");
    }
    
    @POST
    @Path("/repositories/{repositoryId}/oidc")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "OIDC authentication",
            description = "Authenticates a user with the ID token the repository's OIDC provider issued to this application (id_token): its signature, issuer, audience (oidc.clientId) and expiry are verified, and OIDC must be enabled for the repository (sso.oidc.enabled)"
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
                    description = "OIDC is not enabled, or the ID token was not issued to this application by the configured provider, or has expired",
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
            // The ID token the provider issued to this application, verified (3.4.1, zero-trust P0). An access token was
            // sent to a UserInfo endpoint the request could name — Google's and Microsoft's among those allowed — and the
            // answer trusted, with OIDC enabled or not: any application's token logged in as its holder.
            Object idToken = request == null ? null : request.get("id_token");
            jp.aegif.nemaki.rest.OidcLogin.Outcome outcome = oidcLogin.verify(
                    jp.aegif.nemaki.rest.OidcLogin.Settings.read(getPropertyManager(), repositoryId),
                    idToken instanceof String ? (String) idToken : null);
            if (!outcome.verified()) {
                throw ApiException.unauthorized(outcome.refusal());
            }
            String username = outcome.userName();

            // The account the token may act as: created for it, linked to its subject, or one an administrator allowed SSO
            // for — linked now (batch 16 review round 1). The name alone was enough, the built-in admin included.
            String binding = jp.aegif.nemaki.rest.OidcLogin.binding(outcome);
            UserItem user = contentService.getUserItemById(repositoryId, username);
            if (user == null) {
                UserItem created = getOrCreateUser(repositoryId, username, binding);
                if (created == null) {
                    throw ApiException.internalError("Failed to create or find user");
                }
                // THE SAME RULE, asked of the account AS THE STORE HAS IT. getOrCreateUser answers
                // with the account it FOUND when one appeared between this method's read and its own,
                // and that is an EXISTING account — without this ask it was entered with no rule
                // applied and 「unset ⇒ allowed」 decided alone (R209). The STORE'S copy, not the
                // instance the creation handed back: an authorisation decision read from a copy that is
                // not the store's lets in an account the store has disabled (batch-26 review: Codex P1,
                // subagent P2-4).
                //
                // From here the account EXISTS — this login created it, or found it in the race — and it
                // takes THE SAME STEPS as one found at first (R210): the rule, then the binding written if
                // it is missing, and read back. The created arm used to only COMPARE the binding, so a race
                // that found an unbound 「cloud」 account made before 3.4.1 — which the rule allows to be
                // adopted — was refused although nothing stopped this login from linking it.
                UserItem stored = contentService.getUserItemByIdFromStore(repositoryId, username);
                if (stored == null) {
                    // Refused here with its own reason: the rule answers null with 「not linked, and SSO not
                    // allowed」, which is not what happened (R211).
                    logger.warning("API v1: OIDC login refused — the account the creation answered with for "
                            + username + " is not in the store");
                    throw ApiException.internalError("The account could not be found in the store after it was created");
                }
                user = stored;
            }
            String refused = jp.aegif.nemaki.rest.OidcLogin.refusal(user, outcome);
            if (refused != null) {
                logger.info("API v1: OIDC login refused for an existing account: " + refused);
                throw ApiException.permissionDenied(refused);
            }
            if (jp.aegif.nemaki.rest.SsoLogin.unlinked(user, jp.aegif.nemaki.rest.SsoLogin.OIDC)) {
                user.setSubTypeProperties(jp.aegif.nemaki.rest.SsoLogin.with(user,
                        jp.aegif.nemaki.rest.OidcLogin.SUBJECT_KEY, binding));
                contentService.applyUserUpdate(repositoryId, user, username);
                // READ IT BACK (R175 + R168, constraint (d)): a save that returns without storing the
                // binding would leave this login acting as an account it only believes it linked. What
                // this does NOT undo is residual R196 — a refusal that THROWS does not reach here, and
                // the cached instance keeps the binding until it is evicted.
                // FROM THE STORE, not through the cache (R207).
                UserItem stored = contentService.getUserItemByIdFromStore(repositoryId, username);
                String storedBinding = stored == null ? null
                        : jp.aegif.nemaki.rest.SsoLogin.boundSubject(stored,
                                jp.aegif.nemaki.rest.SsoLogin.OIDC);
                // Not 「binding == null」: OidcLogin.binding concatenates the issuer and a
                // subject the claims verifier requires, so it cannot be null here (batch-22
                // review, subagent P3-3). The comparison is the whole check.
                if (!binding.equals(storedBinding)) {
                    logger.warning("API v1: OIDC login refused — the binding of " + username
                            + " was not stored");
                    throw ApiException.permissionDenied(
                            "The account's link to this identity could not be stored");
                }
                // The rule again, of the copy just read back, and that copy from here on (closing batch 6 review, Codex
                // P1): the binding was the only thing compared, so an account disabled, re-marked or made password-only
                // between the rule and the read-back was entered on the copy judged before it.
                String refusedAfter = jp.aegif.nemaki.rest.OidcLogin.refusal(stored, outcome);
                if (refusedAfter != null) {
                    logger.info("API v1: OIDC login refused for the account read back: " + refusedAfter);
                    throw ApiException.permissionDenied(refusedAfter);
                }
                user = stored;
            }

            // Whether SSO is allowed for the account, read from the account itself (no service lookup to fall through).
            if (!jp.aegif.nemaki.util.AuthenticationUtil.isAuthMethodAllowed(user, "cloud")) {
                logger.info("API v1: OIDC authentication denied for user " + username + " (not in allowedAuthMethods)");
                throw ApiException.permissionDenied("Authentication method not allowed for this user");
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
    
    /**
     * Who the request is authenticated as: the identity the authentication filter established and put in the
     * {@code CallContext} (3.4.1, zero-trust P0). For {@code /api/v1/cmis/*} that filter is
     * {@code rest.AuthenticationFilter} — {@code web.xml} maps this path tree to it, and the Jersey-side
     * {@code ApiAuthenticationFilter} then skips because the context is already set.
     *
     * <p>This used to read the raw {@code Authorization} header instead, taking the name out of a {@code Basic} value
     * <em>without checking its password</em>. The filter authenticates a request by its {@code AUTH_TOKEN} / API key
     * <em>before</em> it looks at {@code Authorization}, so a caller holding any valid token of their own could add
     * {@code Authorization: Basic admin:anything} and be read here as {@code admin} — {@code /token/refresh} then minted
     * an admin token (measured on a development server: a non-admin's token plus a bogus admin header returned a token
     * for {@code admin}). The name a caller asserts is not the name they authenticated as.
     *
     * <p>A container-authenticated {@code UserPrincipal} is honoured as a second arm. Nothing in this product sets one
     * (no {@code request.login} and no request wrapper overrides it), so it is dormant here; it is kept so a deployment
     * that puts container authentication in front of the application is not refused.
     */
    private String getAuthenticatedUsername() {
        Object callContextObj = httpRequest != null ? httpRequest.getAttribute("CallContext") : null;
        if (callContextObj instanceof org.apache.chemistry.opencmis.commons.server.CallContext callContext
                && StringUtils.isNotBlank(callContext.getUsername())) {
            return callContext.getUsername();
        }

        // Only a principal the container itself authenticated; never a name the request merely asserts.
        // Blank is not an identity here either — the check below used to apply to the CallContext arm alone,
        // so a container handing back an empty name would have been acted on (round 2, Codex P3).
        if (httpRequest != null && httpRequest.getUserPrincipal() != null
                && StringUtils.isNotBlank(httpRequest.getUserPrincipal().getName())) {
            return httpRequest.getUserPrincipal().getName();
        }

        return null;
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
     * Get PropertyManager from Spring context.
     */
    private jp.aegif.nemaki.util.PropertyManager getPropertyManager() {
        try {
            org.springframework.context.ApplicationContext ctx =
                    jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
            if (ctx != null) {
                return ctx.getBean("propertyManager", jp.aegif.nemaki.util.PropertyManager.class);
            }
        } catch (Exception e) {
            logger.warning("Failed to get PropertyManager: " + e.getMessage());
        }
        return null;
    }


    /** A user this creates is linked to the OIDC subject given, if any, and records the route that made it. */
    private UserItem getOrCreateUser(String repositoryId, String userName, String oidcSubject) {
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

            // Set allowedAuthMethods to "cloud" for SSO-provisioned users, and record WHICH ROUTE made the
            // account (R168): «cloud is listed» is not «an administrator allowed SSO» when the login paths
            // write it themselves, and the mark is what tells the two apart.
            List<jp.aegif.nemaki.model.Property> subTypeProperties = new java.util.ArrayList<>();
            subTypeProperties.add(new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "cloud"));
            subTypeProperties.add(new jp.aegif.nemaki.model.Property(
                    jp.aegif.nemaki.rest.SsoLogin.ROUTE_KEY, jp.aegif.nemaki.rest.SsoLogin.OIDC));
            if (oidcSubject != null) {
                subTypeProperties.add(new jp.aegif.nemaki.model.Property(jp.aegif.nemaki.rest.OidcLogin.SUBJECT_KEY, oidcSubject));
            }
            newUser.setSubTypeProperties(subTypeProperties);
            
            // The created instance, as the REST door's creator answers. The caller reads the account back from the STORE,
            // strictly (R211). Reading it again here through the ordinary lookup turned a read that could not be made —
            // an undeployed view while provisioning, a store that did not answer — into null, which the caller reports
            // as «Failed to create or find user» (closing batch 6 review, Codex P2).
            return contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, newUser);
            
        } catch (Exception e) {
            logger.severe("Failed to get or create user: " + userName + " - " + e.getMessage());
            return null;
        }
    }
    
    // Consolidated into ContentService.getOrCreateSystemSubFolder. This caller
    // historically returned null (rather than throwing) when the system folder is
    // unavailable, and the call site at getOrCreateSsoUser handles null — so the
    // delegate is wrapped to preserve that graceful contract.
    private Folder getOrCreateSystemSubFolder(String repositoryId, String name) {
        try {
            return contentService.getOrCreateSystemSubFolder(repositoryId, name);
        } catch (RuntimeException e) {
            logger.severe("Failed to get or create system sub-folder '" + name + "': " + e.getMessage());
            return null;
        }
    }
}
