package jp.aegif.nemaki.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.aegif.nemaki.util.PropertyManager;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * CORS filter for all API endpoints (/rest/*, /api/*, /odata/*, /saml/*).
 *
 * <p>Runs as the first filter in the chain (before authentication) to handle
 * preflight OPTIONS requests. Reads allowed origins from PropertyManager
 * via the key {@code api.cors.allowedOrigins} (default: {@code *}).
 *
 * <p>This bean is wired by Spring (serviceContext.xml) and exposed to the
 * servlet container via {@code DelegatingFilterProxy} in web.xml, giving
 * it access to PropertyManager for configuration.
 *
 * <p>In production, set {@code api.cors.allowedOrigins=https://ecm.example.com}
 * in nemakiware.properties to restrict cross-origin access.
 * Multiple origins can be comma-separated.
 */
public class SimpleCorsFilter implements Filter {

    private static final Log log = LogFactory.getLog(SimpleCorsFilter.class);
    private static final String PROP_KEY = "api.cors.allowedOrigins";

    private PropertyManager propertyManager;
    private String allowedOrigins = "*";

    /** Injected by Spring via serviceContext.xml. */
    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // When used via DelegatingFilterProxy (targetFilterLifecycle=false),
        // this method is NOT called. Origins are resolved by the Spring
        // init-method instead. This serves as a fallback for direct instantiation.
        if ("*".equals(allowedOrigins)) {
            resolveOrigins();
        }
        log.info("SimpleCorsFilter initialized (allowedOrigins=" + allowedOrigins + ")");
    }

    /**
     * Read the configured origins from PropertyManager.
     * Called as Spring init-method (serviceContext.xml) to ensure PropertyManager
     * is available, since DelegatingFilterProxy does not call Filter.init() by default.
     */
    public void resolveOrigins() {
        if (propertyManager != null) {
            String configured = propertyManager.readValue(PROP_KEY);
            if (configured != null && !configured.isBlank()) {
                allowedOrigins = configured.trim();
                return;
            }
        }
        // Fallback: system property (for environments where PropertyManager is not yet available)
        String sysProp = System.getProperty(PROP_KEY);
        if (sysProp != null && !sysProp.isBlank()) {
            allowedOrigins = sysProp.trim();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String originHeader = httpRequest.getHeader("Origin");
        String allowOrigin = resolveAllowOrigin(originHeader);

        if (allowOrigin != null) {
            httpResponse.setHeader("Access-Control-Allow-Origin", allowOrigin);
            httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            httpResponse.setHeader("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, AUTH_TOKEN, nemaki_auth_token, AUTH_TOKEN_APP, nemaki_auth_token_app, X-API-Key, X-Requested-With");
            httpResponse.setHeader("Access-Control-Max-Age", "3600");
        }

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Resolve the Access-Control-Allow-Origin value.
     * Returns null if the origin is not allowed.
     */
    private String resolveAllowOrigin(String originHeader) {
        if ("*".equals(allowedOrigins)) {
            return "*";
        }
        if (originHeader == null || originHeader.isBlank()) {
            return null;
        }
        for (String allowed : allowedOrigins.split("\\s*,\\s*")) {
            if (allowed.equalsIgnoreCase(originHeader)) {
                return originHeader;
            }
        }
        return null;
    }

    @Override
    public void destroy() { }
}
