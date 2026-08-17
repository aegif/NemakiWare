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
 * CORS filter for all API endpoints (/rest/*, /api/*, /odata/*, /saml/*, /services/*, /mcp/*
 * — the full list is the web.xml corsFilter mappings, which are the source of truth).
 *
 * <p>Runs as the first filter in the chain (before authentication) to handle
 * preflight OPTIONS requests. Reads allowed origins from PropertyManager
 * via the key {@code api.cors.allowedOrigins}.
 *
 * <p><b>Default since 3.3.0: NO cross-origin access.</b> It used to be {@code *}, which meant a
 * deployment that never heard of the key handed every origin on the internet a readable response
 * from every API path this filter covers. The shipped React UI is served from the same origin
 * ({@code /core/ui/}) and never needed CORS, so the permissive default bought nothing the product
 * itself used. With no configuration the filter now emits no CORS headers at all and the browser
 * applies same-origin policy.
 *
 * <p>To allow specific browser origins, set them explicitly — comma-separated:
 * {@code api.cors.allowedOrigins=https://ecm.example.com,https://admin.example.com}.
 * {@code *} is still accepted as an explicit choice.
 *
 * <p>Non-browser clients (CMIS clients, scripts, the MCP server) are unaffected: CORS is enforced
 * by browsers, not by the server.
 *
 * <p>This bean is wired by Spring (serviceContext.xml) and exposed to the
 * servlet container via {@code DelegatingFilterProxy} in web.xml, giving
 * it access to PropertyManager for configuration.
 */
public class SimpleCorsFilter implements Filter {

    private static final Log log = LogFactory.getLog(SimpleCorsFilter.class);
    private static final String PROP_KEY = "api.cors.allowedOrigins";

    /** No origin. Not {@code *} — see the class javadoc. */
    private static final String NONE = "";

    private PropertyManager propertyManager;
    private String allowedOrigins = NONE;

    /**
     * Whether {@link #resolveOrigins()} has run. Previously the code used {@code "*"} as the
     * "not resolved yet" sentinel, which stops working the moment the default is not {@code "*"}.
     */
    private boolean resolved = false;

    /** Injected by Spring via serviceContext.xml. */
    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // When used via DelegatingFilterProxy (targetFilterLifecycle=false),
        // this method is NOT called. Origins are resolved by the Spring
        // init-method instead. This serves as a fallback for direct instantiation.
        if (!resolved) {
            resolveOrigins();
        }
        log.info("SimpleCorsFilter initialized (allowedOrigins="
                + (NONE.equals(allowedOrigins) ? "<none — cross-origin browser access disabled>" : allowedOrigins)
                + ")");
    }

    /**
     * Read the configured origins from PropertyManager.
     * Called as Spring init-method (serviceContext.xml) to ensure PropertyManager
     * is available, since DelegatingFilterProxy does not call Filter.init() by default.
     */
    public void resolveOrigins() {
        resolved = true;
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
            return;
        }
        allowedOrigins = NONE;
        log.info(PROP_KEY + " is not set — cross-origin browser access is disabled (3.3.0 changed "
                + "this default from '*'). Set it to your UI origin, or to '*' to restore the old "
                + "behaviour.");
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
            if (!"*".equals(allowOrigin)) {
                // The response now depends on the request's Origin. Without Vary a shared cache
                // may serve one origin's Allow-Origin echo to a different origin.
                httpResponse.addHeader("Vary", "Origin");
            }
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
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return null; // no configuration = no cross-origin access (3.3.0 default)
        }
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
