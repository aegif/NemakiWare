package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

/**
 * Spring MVC interceptor that enforces the same CSRF protection as
 * {@code ResourceBase.validateCsrfProtection()} used by Jersey resources.
 *
 * <p>Applied to all state-changing methods (POST, PUT, DELETE, PATCH) on
 * {@code /api/*} Spring MVC endpoints. GET, HEAD, OPTIONS are exempt.
 *
 * <p>Bypass conditions (any one is sufficient):
 * <ul>
 *   <li>Non-Basic {@code Authorization} header (Bearer, etc.)</li>
 *   <li>{@code AUTH_TOKEN} / {@code AUTH_TOKEN_APP} / {@code X-API-Key} header</li>
 *   <li>{@code Origin} header matching the server</li>
 *   <li>{@code Referer} header matching the server</li>
 *   <li>{@code X-Requested-With: XMLHttpRequest} header</li>
 * </ul>
 *
 * <p>Basic auth is NOT a bypass — browsers auto-attach it, making it
 * an ambient credential like cookies.
 */
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(CsrfInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        // Safe methods are exempt from CSRF
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        // Webhook receiver endpoints use HMAC signature verification, not CSRF.
        // But /subscribe admin mutations MUST keep CSRF protection.
        if (isWebhookReceiverPath(request)) {
            return true;
        }

        String error = validateCsrf(request);
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
     * Matches /v1/ingest-webhook/{connectorId} but NOT /v1/ingest-webhook/{connectorId}/subscribe.
     */
    private static boolean isWebhookReceiverPath(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
        // DispatcherServlet strips the /api prefix, so path is /v1/ingest-webhook/...
        if (!path.startsWith("/v1/ingest-webhook/")) return false;
        // /subscribe sub-path is an admin mutation — keep CSRF
        return !path.contains("/subscribe");
    }

    /**
     * Validate CSRF protection. Returns null if valid, error string if rejected.
     * Logic mirrors ResourceBase.validateCsrfProtection().
     */
    static String validateCsrf(HttpServletRequest request) {
        // 1. Explicit auth headers bypass (non-ambient credentials)
        if (hasExplicitAuthHeaders(request)) {
            return null;
        }

        // 2. Origin header check
        String scheme = request.getScheme();
        String serverHost = request.getServerName();
        int serverPort = request.getServerPort();
        if (scheme != null) scheme = scheme.toLowerCase(Locale.ROOT);
        if (serverHost != null) serverHost = serverHost.toLowerCase(Locale.ROOT);

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            if (isOriginValid(origin, scheme, serverHost, serverPort)) {
                return null;
            }
            return "invalid origin";
        }

        // 3. Referer header fallback
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty()) {
            if (scheme == null || serverHost == null) return "invalid referer";
            try {
                java.net.URI refererUri = new java.net.URI(referer);
                String rScheme = refererUri.getScheme();
                String rHost = refererUri.getHost();
                int rPort = refererUri.getPort();
                int normReferer = normalizePort(rScheme, rPort);
                int normServer = normalizePort(scheme, serverPort);

                if (scheme.equals(rScheme) && serverHost.equals(rHost) && normServer == normReferer) {
                    return null;
                }
                if (isLocalhostEquiv(serverHost, rHost) && normServer == normReferer) {
                    return null;
                }
            } catch (Exception ignored) {}
            return "invalid referer";
        }

        // 4. X-Requested-With
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return null;
        }
        return "missing origin verification headers";
    }

    private static boolean hasExplicitAuthHeaders(HttpServletRequest request) {
        // Non-Basic Authorization (Bearer, etc.)
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isEmpty() && !auth.trim().regionMatches(true, 0, "Basic ", 0, 6)) {
            return true;
        }
        // AUTH_TOKEN / AUTH_TOKEN_APP / X-API-Key
        return nonEmpty(request, "AUTH_TOKEN")
                || nonEmpty(request, "AUTH_TOKEN_APP")
                || nonEmpty(request, "X-API-Key");
    }

    private static boolean nonEmpty(HttpServletRequest request, String header) {
        String v = request.getHeader(header);
        return v != null && !v.isEmpty();
    }

    private static boolean isOriginValid(String origin, String expectedScheme, String expectedHost, int expectedPort) {
        try {
            java.net.URI uri = new java.net.URI(origin);
            String oScheme = uri.getScheme();
            String oHost = uri.getHost();
            if (oScheme == null || oHost == null || expectedScheme == null || expectedHost == null) return false;
            oScheme = oScheme.toLowerCase(Locale.ROOT);
            oHost = oHost.toLowerCase(Locale.ROOT);
            int normOrigin = normalizePort(oScheme, uri.getPort());
            int normExpected = normalizePort(expectedScheme, expectedPort);
            if (!expectedScheme.equals(oScheme)) return false;
            if (!expectedHost.equals(oHost) && !isLocalhostEquiv(expectedHost, oHost)) return false;
            return normExpected == normOrigin;
        } catch (Exception e) {
            return false;
        }
    }

    private static int normalizePort(String scheme, int port) {
        if ("https".equalsIgnoreCase(scheme)) return (port == -1 || port == 443) ? 443 : port;
        if ("http".equalsIgnoreCase(scheme)) return (port == -1 || port == 80) ? 80 : port;
        return port;
    }

    private static boolean isLocalhostEquiv(String h1, String h2) {
        return ("localhost".equals(h1) || "127.0.0.1".equals(h1))
                && ("localhost".equals(h2) || "127.0.0.1".equals(h2));
    }
}
