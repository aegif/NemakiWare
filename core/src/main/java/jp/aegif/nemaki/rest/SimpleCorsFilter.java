package jp.aegif.nemaki.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * CORS filter for all API endpoints (/rest/*, /api/*, /odata/*, /saml/*).
 *
 * <p>Runs as the first filter in the chain (before authentication) to handle
 * preflight OPTIONS requests. Reads allowed origins from the system property
 * {@code api.cors.allowedOrigins} (default: {@code *}).
 *
 * <p>In production, set {@code -Dapi.cors.allowedOrigins=https://ecm.example.com}
 * or configure via nemakiware.properties to restrict cross-origin access.
 */
public class SimpleCorsFilter implements Filter {

    private static final Log log = LogFactory.getLog(SimpleCorsFilter.class);
    private static final String PROP_KEY = "api.cors.allowedOrigins";

    private String allowedOrigins = "*";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Read from system property (set via -D or nemakiware.properties → System.setProperty)
        String configured = System.getProperty(PROP_KEY);
        if (configured != null && !configured.isBlank()) {
            allowedOrigins = configured.trim();
        }
        log.info("SimpleCorsFilter initialized (allowedOrigins=" + allowedOrigins + ")");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Determine the correct Access-Control-Allow-Origin value
        String originHeader = httpRequest.getHeader("Origin");
        String allowOrigin = resolveAllowOrigin(originHeader);

        if (allowOrigin != null) {
            httpResponse.setHeader("Access-Control-Allow-Origin", allowOrigin);
            httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            httpResponse.setHeader("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, AUTH_TOKEN, nemaki_auth_token, AUTH_TOKEN_APP, nemaki_auth_token_app, X-API-Key, X-Requested-With");
            httpResponse.setHeader("Access-Control-Max-Age", "3600");
        }

        // Handle preflight OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Resolve the Access-Control-Allow-Origin value based on the request's Origin header.
     * Returns null if the origin is not allowed (no CORS headers will be sent).
     */
    private String resolveAllowOrigin(String originHeader) {
        if ("*".equals(allowedOrigins)) {
            return "*";
        }
        if (originHeader == null || originHeader.isBlank()) {
            return null; // No Origin header → no CORS headers needed
        }
        // Check against comma-separated allowed origins
        for (String allowed : allowedOrigins.split("\\s*,\\s*")) {
            if (allowed.equalsIgnoreCase(originHeader)) {
                return originHeader; // Echo back the matched origin
            }
        }
        return null; // Origin not allowed
    }

    @Override
    public void destroy() {
        // no-op
    }
}
