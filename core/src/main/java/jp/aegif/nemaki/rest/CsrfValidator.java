package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;

import java.util.Locale;

/**
 * Shared CSRF validation logic used by both Jersey (ResourceBase) and
 * Spring MVC (CsrfInterceptor).
 *
 * <p>Returns null when the request is safe, or an error string when rejected.
 *
 * <p>Bypass conditions (any one is sufficient):
 * <ul>
 *   <li>Non-Basic {@code Authorization} header (Bearer, etc.)</li>
 *   <li>{@code AUTH_TOKEN} / {@code nemaki_auth_token} header</li>
 *   <li>{@code AUTH_TOKEN_APP} / {@code nemaki_auth_token_app} header</li>
 *   <li>{@code X-API-Key} header</li>
 *   <li>{@code Origin} header matching the server</li>
 *   <li>{@code Referer} header matching the server</li>
 *   <li>{@code X-Requested-With: XMLHttpRequest}</li>
 * </ul>
 *
 * <p>Basic auth is NOT a bypass — browsers auto-attach it per realm.
 */
public final class CsrfValidator {

    private CsrfValidator() {}

    /**
     * Validate CSRF protection.
     * @return null if valid, error description string if rejected
     */
    public static String validate(HttpServletRequest request) {
        // 1. Explicit (non-ambient) auth headers bypass
        if (hasExplicitAuthHeaders(request)) {
            return null;
        }

        // 2. Origin header
        String scheme = norm(request.getScheme());
        String serverHost = norm(request.getServerName());
        int serverPort = request.getServerPort();

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            return isOriginValid(origin, scheme, serverHost, serverPort) ? null : "invalid origin";
        }

        // 3. Referer fallback
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isEmpty()) {
            if (scheme == null || serverHost == null) return "invalid referer";
            try {
                java.net.URI uri = new java.net.URI(referer);
                String rScheme = norm(uri.getScheme());
                String rHost = norm(uri.getHost());
                int normRef = normalizePort(rScheme, uri.getPort());
                int normSrv = normalizePort(scheme, serverPort);
                if (scheme.equals(rScheme) && serverHost.equals(rHost) && normSrv == normRef) return null;
                if (isLocalhostEquiv(serverHost, rHost) && normSrv == normRef) return null;
            } catch (Exception ignored) {}
            return "invalid referer";
        }

        // 4. X-Requested-With
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return null;
        }
        return "missing origin verification headers";
    }

    // ── Header checks ──

    static boolean hasExplicitAuthHeaders(HttpServletRequest request) {
        // Non-Basic Authorization (Bearer, etc.)
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String trimmed = auth.trim();
            if (!trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
                return true;
            }
        }
        // AUTH_TOKEN (modern + legacy header names)
        return nonEmpty(request, "AUTH_TOKEN")
                || nonEmpty(request, CallContextKey.AUTH_TOKEN)   // nemaki_auth_token
                || nonEmpty(request, "AUTH_TOKEN_APP")
                || nonEmpty(request, CallContextKey.AUTH_TOKEN_APP) // nemaki_auth_token_app
                || nonEmpty(request, "X-API-Key");
    }

    // ── Origin / Referer validation ──

    static boolean isOriginValid(String origin, String expectedScheme, String expectedHost, int expectedPort) {
        try {
            java.net.URI uri = new java.net.URI(origin);
            String oScheme = norm(uri.getScheme());
            String oHost = norm(uri.getHost());
            if (oScheme == null || oHost == null || expectedScheme == null || expectedHost == null) return false;
            int normOrigin = normalizePort(oScheme, uri.getPort());
            int normExpected = normalizePort(expectedScheme, expectedPort);
            if (!expectedScheme.equals(oScheme)) return false;
            if (!expectedHost.equals(oHost) && !isLocalhostEquiv(expectedHost, oHost)) return false;
            return normExpected == normOrigin;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Utilities ──

    static int normalizePort(String scheme, int port) {
        if ("https".equalsIgnoreCase(scheme)) return (port == -1 || port == 443) ? 443 : port;
        if ("http".equalsIgnoreCase(scheme)) return (port == -1 || port == 80) ? 80 : port;
        return port;
    }

    static boolean isLocalhostEquiv(String h1, String h2) {
        return ("localhost".equals(h1) || "127.0.0.1".equals(h1))
                && ("localhost".equals(h2) || "127.0.0.1".equals(h2));
    }

    private static boolean nonEmpty(HttpServletRequest request, String header) {
        String v = request.getHeader(header);
        return v != null && !v.isEmpty();
    }

    private static String norm(String s) {
        return s != null ? s.trim().toLowerCase(Locale.ROOT) : null;
    }
}
