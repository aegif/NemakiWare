package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.aegif.nemaki.rest.CsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that enforces CSRF protection on state-changing
 * methods (POST, PUT, DELETE, PATCH) via {@link CsrfValidator} — the same
 * logic used by Jersey's {@code ResourceBase.validateCsrfProtection()}.
 *
 * <p>Webhook receiver paths ({@code /v1/ingest-webhook/{id}}, not {@code /subscribe})
 * are exempt because they use HMAC signature verification instead of CSRF.
 */
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(CsrfInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        // Webhook receiver endpoints use HMAC, not CSRF.
        // But /subscribe admin mutations keep CSRF.
        if (isWebhookReceiverPath(request)) {
            return true;
        }

        String error = CsrfValidator.validate(request);
        if (error == null) {
            return true;
        }

        logger.warn("CSRF rejected: {} {} — {} (remote={})",
                method, request.getRequestURI(), error, request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"CSRF validation failed: " + error + "\"}");
        return false;
    }

    /**
     * Check if this is a webhook receiver path (HMAC-verified, not CSRF-protected).
     * Matches /v1/ingest-webhook/{connectorId} but NOT /subscribe sub-path.
     */
    static boolean isWebhookReceiverPath(HttpServletRequest request) {
        // DispatcherServlet mapped to /api/* sets servletPath=/api, pathInfo=/v1/...
        String path = request.getPathInfo();
        if (path == null || path.isEmpty()) {
            path = request.getServletPath();
        }
        if (!path.startsWith("/v1/ingest-webhook/")) return false;
        return !path.contains("/subscribe");
    }
}
