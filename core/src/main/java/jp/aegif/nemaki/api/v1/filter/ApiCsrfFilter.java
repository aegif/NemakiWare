package jp.aegif.nemaki.api.v1.filter;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.rest.CsrfValidator;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * CSRF protection for the JAX-RS API v1 (CMIS) application
 * ({@code /api/v1/cmis/*}, served by Jersey via {@link
 * jp.aegif.nemaki.api.v1.ApiV1Application}).
 *
 * <p>The Spring MVC side of the API ({@code /api/v1/admin/*}) is
 * already guarded by the {@code CsrfInterceptor} HandlerInterceptor,
 * but that interceptor only attaches to the Spring DispatcherServlet —
 * it never runs for the Jersey-served {@code /api/v1/cmis/*} resources.
 * Those resources accept the {@code nemaki_auth_token} HttpOnly cookie
 * and HTTP Basic auth, both of which browsers attach as ambient
 * credentials, so state-changing requests there were reachable via CSRF.
 *
 * <p>This filter closes that gap by running the SAME {@link
 * CsrfValidator} logic the Spring MVC interceptor uses, on the same
 * unsafe methods (POST/PUT/DELETE/PATCH). Requests that carry a
 * non-ambient credential (Bearer / AUTH_TOKEN / X-API-Key) or a valid
 * Origin/Referer/X-Requested-With header pass through unchanged, so
 * legitimate SPA and API-client traffic is unaffected.
 *
 * <p>Runs before {@link ApiAuthenticationFilter} (lower priority value)
 * so a forged cross-site request is rejected before any credential is
 * processed.
 */
@Provider
@Priority(900) // before ApiAuthenticationFilter (Priorities.AUTHENTICATION = 1000)
public class ApiCsrfFilter implements ContainerRequestFilter {

    private static final Logger logger = Logger.getLogger(ApiCsrfFilter.class.getName());
    private static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";

    @Context
    private HttpServletRequest httpServletRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String method = requestContext.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method) || "TRACE".equals(method)) {
            return;
        }

        // Defensive: if the servlet request is somehow unavailable, fail
        // closed for unsafe methods rather than silently skipping CSRF.
        if (httpServletRequest == null) {
            abortForbidden(requestContext, "CSRF validation unavailable");
            return;
        }

        String error = CsrfValidator.validate(httpServletRequest);
        if (error != null) {
            logger.warning("API v1 CSRF rejection: " + error + " for "
                    + method + " " + requestContext.getUriInfo().getPath());
            abortForbidden(requestContext, "CSRF validation failed: " + error);
        }
    }

    private void abortForbidden(ContainerRequestContext requestContext, String message) {
        ProblemDetail problemDetail = ProblemDetail.permissionDenied(
                message, requestContext.getUriInfo().getPath());
        requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                        .type(PROBLEM_JSON_MEDIA_TYPE)
                        .entity(problemDetail)
                        .build()
        );
    }
}
