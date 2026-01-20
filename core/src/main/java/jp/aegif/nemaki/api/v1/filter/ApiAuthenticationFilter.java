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

import java.io.IOException;
import java.util.Base64;
import java.util.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiAuthenticationFilter implements ContainerRequestFilter {
    
    private static final Logger logger = Logger.getLogger(ApiAuthenticationFilter.class.getName());
    private static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
    
    @Context
    private HttpServletRequest httpServletRequest;
    
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        logger.info("ApiAuthenticationFilter: checking path = " + path);

        // Allow access to OpenAPI spec without authentication
        if (path.endsWith("/openapi.json") || path.endsWith("/openapi.yaml")) {
            logger.info("ApiAuthenticationFilter: allowing OpenAPI access");
            return;
        }

        // Allow access to auth endpoints without authentication
        // Note: Login endpoint is at /auth/repositories/{repositoryId}/login
        // The path at this point is relative to the application path (/api/v1/cmis)
        // So it will be something like "auth/repositories/bedroom/login"
        if (path.startsWith("auth/") && (path.endsWith("/login") || path.contains("/saml") || path.contains("/oidc"))) {
            logger.info("ApiAuthenticationFilter: allowing auth endpoint access");
            return;
        }
        
        // Check if CallContext is already set by the existing authentication filter
        Object callContext = httpServletRequest.getAttribute("CallContext");
        if (callContext != null) {
            // Already authenticated by existing filter chain
            return;
        }
        
        // Check for Authorization header
        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            abortWithUnauthorized(requestContext, "Authentication required. Please provide credentials.");
            return;
        }
        
        // The actual authentication is handled by the existing AuthenticationFilter
        // This filter just ensures the request has proper authentication headers
        // and provides RFC 7807 compliant error responses
        
        if (authHeader.startsWith("Basic ")) {
            // Basic auth - validate format
            try {
                String credentials = authHeader.substring(6);
                byte[] decoded = Base64.getDecoder().decode(credentials);
                String decodedStr = new String(decoded);
                if (!decodedStr.contains(":")) {
                    abortWithUnauthorized(requestContext, "Invalid Basic authentication format");
                    return;
                }
            } catch (IllegalArgumentException e) {
                abortWithUnauthorized(requestContext, "Invalid Basic authentication encoding");
                return;
            }
        } else if (authHeader.startsWith("Bearer ")) {
            // Bearer token - validate format
            String token = authHeader.substring(7);
            if (token.isEmpty()) {
                abortWithUnauthorized(requestContext, "Empty Bearer token");
                return;
            }
        } else {
            abortWithUnauthorized(requestContext, "Unsupported authentication scheme. Use Basic or Bearer.");
            return;
        }
        
        // Authentication will be completed by the existing filter chain
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
