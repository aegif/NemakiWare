package jp.aegif.nemaki.api.v1.filter;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiAuthenticationFilter implements ContainerRequestFilter {

    private static final Logger logger = Logger.getLogger(ApiAuthenticationFilter.class.getName());
    private static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";

    // Pattern to extract repositoryId from path like "repositories/{repositoryId}/..."
    private static final Pattern REPO_PATTERN = Pattern.compile("repositories/([^/]+)/?.*");

    @Context
    private HttpServletRequest httpServletRequest;

    @Autowired(required = false)
    private TokenService tokenService;

    @Autowired(required = false)
    private AuthenticationService authenticationService;

    @Autowired(required = false)
    private PrincipalService principalService;

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private RepositoryInfoMap repositoryInfoMap;

    // Global paths that don't require a repository in the URL (use default repository for auth)
    private static final String[] GLOBAL_PATHS = {
        "audit/metrics",
        "audit/",
        "health"
    };

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        logger.fine("ApiAuthenticationFilter: checking path = " + path);

        // Allow access to OpenAPI spec without authentication
        if (path.equals("openapi.json") || path.equals("openapi.yaml") ||
            path.endsWith("/openapi.json") || path.endsWith("/openapi.yaml")) {
            logger.fine("ApiAuthenticationFilter: allowing OpenAPI access for path: " + path);
            return;
        }

        // Allow access to auth endpoints without authentication
        if (path.startsWith("auth/") && (path.endsWith("/login") || path.contains("/saml") || path.contains("/oidc"))) {
            logger.fine("ApiAuthenticationFilter: allowing auth endpoint access");
            return;
        }

        // Check if CallContext is already set by another filter
        Object existingCallContext = httpServletRequest.getAttribute("CallContext");
        if (existingCallContext != null) {
            logger.fine("ApiAuthenticationFilter: CallContext already set, skipping");
            return;
        }

        // Extract repositoryId from path
        String repositoryId = extractRepositoryId(path);
        if (repositoryId == null) {
            abortWithUnauthorized(requestContext, "Could not determine repository from request path.");
            return;
        }

        // Create CallContext
        CallContextImpl callContext = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, null, null, null, null);

        // Check for AUTH_TOKEN header (NemakiWare token-based authentication)
        // Support both standard header name (AUTH_TOKEN) and legacy header name (nemaki_auth_token)
        String authToken = requestContext.getHeaderString("AUTH_TOKEN");
        if (authToken == null || authToken.isEmpty()) {
            // Fallback to legacy header name
            authToken = requestContext.getHeaderString(CallContextKey.AUTH_TOKEN);
        }
        String authTokenApp = requestContext.getHeaderString("AUTH_TOKEN_APP");
        if (authTokenApp == null || authTokenApp.isEmpty()) {
            authTokenApp = requestContext.getHeaderString(CallContextKey.AUTH_TOKEN_APP);
        }
        String app = (authTokenApp == null) ? "" : authTokenApp;

        if (authToken != null && !authToken.isEmpty()) {
            // Token-based authentication
            logger.fine("ApiAuthenticationFilter: AUTH_TOKEN header found, validating token");

            if (tokenService == null) {
                logger.warning("ApiAuthenticationFilter: TokenService not available");
                abortWithUnauthorized(requestContext, "Token validation service not available.");
                return;
            }

            String userName = tokenService.validateToken(app, repositoryId, authToken);
            if (userName == null) {
                logger.fine("ApiAuthenticationFilter: Invalid or expired AUTH_TOKEN");
                abortWithUnauthorized(requestContext, "Invalid or expired authentication token.");
                return;
            }

            logger.fine("ApiAuthenticationFilter: Token validated for user: " + userName);
            callContext.put(CallContext.USERNAME, userName);
            callContext.put(CallContextKey.AUTH_TOKEN, authToken);
            callContext.put(CallContextKey.AUTH_TOKEN_APP, authTokenApp);

        } else {
            // Check for Authorization header
            String authHeader = requestContext.getHeaderString("Authorization");
            if (authHeader == null || authHeader.isEmpty()) {
                abortWithUnauthorized(requestContext, "Authentication required. Please provide credentials.");
                return;
            }

            if (authHeader.startsWith("Basic ")) {
                // Basic auth
                try {
                    String credentials = authHeader.substring(6);
                    byte[] decoded = Base64.getDecoder().decode(credentials);
                    String decodedStr = new String(decoded);
                    int colonIndex = decodedStr.indexOf(':');
                    if (colonIndex < 0) {
                        abortWithUnauthorized(requestContext, "Invalid Basic authentication format");
                        return;
                    }
                    String userName = decodedStr.substring(0, colonIndex);
                    String password = decodedStr.substring(colonIndex + 1);
                    callContext.put(CallContext.USERNAME, userName);
                    callContext.put(CallContext.PASSWORD, password);
                    logger.fine("ApiAuthenticationFilter: Basic auth extracted for user: " + userName);
                } catch (IllegalArgumentException e) {
                    abortWithUnauthorized(requestContext, "Invalid Basic authentication encoding");
                    return;
                }
            } else if (authHeader.startsWith("Bearer ")) {
                // Bearer token - treat as AUTH_TOKEN
                String token = authHeader.substring(7);
                if (token.isEmpty()) {
                    abortWithUnauthorized(requestContext, "Empty Bearer token");
                    return;
                }

                if (tokenService == null) {
                    logger.warning("ApiAuthenticationFilter: TokenService not available for Bearer token");
                    abortWithUnauthorized(requestContext, "Token validation service not available.");
                    return;
                }

                String userName = tokenService.validateToken(app, repositoryId, token);
                if (userName == null) {
                    logger.fine("ApiAuthenticationFilter: Invalid or expired Bearer token");
                    abortWithUnauthorized(requestContext, "Invalid or expired Bearer token.");
                    return;
                }

                logger.fine("ApiAuthenticationFilter: Bearer token validated for user: " + userName);
                callContext.put(CallContext.USERNAME, userName);
                callContext.put(CallContextKey.AUTH_TOKEN, token);
            } else {
                abortWithUnauthorized(requestContext, "Unsupported authentication scheme. Use Basic, Bearer, or AUTH_TOKEN header.");
                return;
            }
        }

        // Validate credentials using AuthenticationService
        if (authenticationService != null) {
            try {
                boolean authenticated = authenticationService.login(callContext);
                if (!authenticated) {
                    logger.fine("ApiAuthenticationFilter: Authentication failed");
                    abortWithUnauthorized(requestContext, "Invalid credentials.");
                    return;
                }
                logger.fine("ApiAuthenticationFilter: Authentication successful");
            } catch (Exception e) {
                logger.warning("ApiAuthenticationFilter: Authentication error: " + e.getMessage());
                abortWithUnauthorized(requestContext, "Authentication failed: " + e.getMessage());
                return;
            }
        } else {
            logger.warning("ApiAuthenticationFilter: AuthenticationService not available");
        }

        // Check if user is admin
        if (principalService != null && callContext.getUsername() != null) {
            try {
                List<User> admins = principalService.getAdmins(repositoryId);
                boolean isAdmin = false;
                if (admins != null) {
                    for (User admin : admins) {
                        if (admin.getUserId() != null && admin.getUserId().equals(callContext.getUsername())) {
                            isAdmin = true;
                            break;
                        }
                    }
                }
                callContext.put(CallContextKey.IS_ADMIN, isAdmin);
                logger.fine("ApiAuthenticationFilter: User admin status: " + isAdmin);
            } catch (Exception e) {
                logger.warning("ApiAuthenticationFilter: Failed to check admin status: " + e.getMessage());
            }
        }

        // Set CallContext as request attribute
        httpServletRequest.setAttribute("CallContext", callContext);
        logger.fine("ApiAuthenticationFilter: CallContext set for user: " + callContext.getUsername());
    }

    private String extractRepositoryId(String path) {
        // First try to extract from path pattern
        Matcher matcher = REPO_PATTERN.matcher(path);
        if (matcher.matches()) {
            return matcher.group(1);
        }

        // Check if this is a global path that should use default repository
        for (String globalPath : GLOBAL_PATHS) {
            if (path.startsWith(globalPath) || path.contains("/" + globalPath)) {
                return getDefaultRepository();
            }
        }

        return null;
    }

    /**
     * Gets the default repository ID for global endpoints.
     * Uses RepositoryInfoMap.getDefaultRepositoryId() which respects cmis.server.default.repository property.
     */
    private String getDefaultRepository() {
        // Use RepositoryInfoMap which handles default repository logic
        if (repositoryInfoMap != null) {
            String defaultRepo = repositoryInfoMap.getDefaultRepositoryId();
            if (defaultRepo != null) {
                logger.fine("ApiAuthenticationFilter: Using default repository: " + defaultRepo);
                return defaultRepo;
            }
        }

        // Fall back to property manager directly
        if (propertyManager != null) {
            String defaultRepo = propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_REPOSITORY);
            if (defaultRepo != null && !defaultRepo.isEmpty()) {
                logger.fine("ApiAuthenticationFilter: Using default repository from property: " + defaultRepo);
                return defaultRepo;
            }
        }

        // Hard-coded fallback
        logger.warning("ApiAuthenticationFilter: No default repository found, using 'bedroom'");
        return "bedroom";
    }

    private void abortWithUnauthorized(ContainerRequestContext requestContext, String message) {
        ProblemDetail problemDetail = ProblemDetail.unauthorized(message, requestContext.getUriInfo().getPath());

        requestContext.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                        .header("WWW-Authenticate", "Basic realm=\"NemakiWare\", Bearer realm=\"NemakiWare\"")
                        .type(PROBLEM_JSON_MEDIA_TYPE)
                        .entity(problemDetail)
                        .build()
        );
    }
}
