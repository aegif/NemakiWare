package jp.aegif.nemaki.rest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.TrustedProxyResolver;
import jp.aegif.nemaki.util.spring.SpringContext;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public endpoint that issues a SAML AuthnRequest ID + binding cookie
 * for the React UI.
 *
 * <p>Replaces the earlier {@code SamlRequestRegistrationServlet}, which
 * accepted any client-supplied ID and was therefore useless as a trust
 * boundary (an attacker who knew the InResponseTo value of a captured
 * Response could pre-register that exact ID and bypass strict mode).
 * Here the SP generates BOTH the AuthnRequest ID and a 256-bit opaque
 * binding token, returns the ID in the response body, and sets the
 * binding token in an HttpOnly + Secure + SameSite=Lax cookie. The
 * cookie value never reaches JavaScript and travels back automatically
 * with the SAML Response convert request, so only the browser that
 * initiated the SSO flow can satisfy the InResponseTo check.
 *
 * <p><b>Rate limiting.</b> The endpoint is unauthenticated and could
 * otherwise be used to exhaust the registry. A simple per-IP token
 * bucket caps each address to 30 issuances per minute (well above any
 * legitimate UI usage but far below what an attacker would need to
 * fill {@link SamlAuthnRequestRegistry#MAX_ENTRIES}).
 *
 * <p>Wire format:
 * <pre>
 * POST /core/rest/all/saml/initiate
 *
 * 200 OK
 * Set-Cookie: NEMAKI_SAML_BIND=...; HttpOnly; Secure; SameSite=Lax; Max-Age=900; Path=/core
 * Content-Type: application/json
 * { "authnRequestId": "_<uuid>" }
 *
 * 429 Too Many Requests          — rate limit exceeded
 * 503 Service Unavailable        — registry at capacity
 * </pre>
 */
public class SamlInitiateServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(SamlInitiateServlet.class);

    /** Per-IP rate limit: 30 issuances per minute. */
    static final int  RATE_LIMIT_PER_MINUTE = 30;
    static final long RATE_WINDOW_MILLIS    = 60_000L;

    /** Per-IP rolling counter of issuance times (millis). Bounded sweep. */
    private final Map<String, RateState> rateState = new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");

        // Cross-site protection. Even though this endpoint is unauthenticated
        // by design, we must not let a third-party page POST to it from a
        // victim's browser and overwrite their NEMAKI_SAML_BIND cookie
        // (which would disrupt an in-progress SAML login). CsrfValidator
        // accepts an Origin / Referer matching the server, OR an XHR
        // header, OR an explicit non-Basic auth header — none of which a
        // form-POST or img-POST attack can supply for a different origin.
        String csrfError = CsrfValidator.validate(request);
        if (csrfError != null) {
            log.warn("SAML initiate rejected: " + csrfError);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            try (PrintWriter out = response.getWriter()) {
                out.print("{\"status\":\"forbidden\",\"error\":\"" + jsonEscape(csrfError) + "\"}");
            }
            return;
        }

        String clientIp = clientAddress(request);
        if (!checkRateLimit(clientIp)) {
            log.warn("SAML initiate rate-limited for client " + safeForLog(clientIp));
            response.setStatus(429);
            try (PrintWriter out = response.getWriter()) {
                out.print("{\"status\":\"rate_limited\"}");
            }
            return;
        }

        SamlAuthnRequestRegistry.Issued issued = SamlAuthnRequestRegistry.getInstance().issue();
        if (issued == null) {
            response.setStatus(503);
            try (PrintWriter out = response.getWriter()) {
                out.print("{\"status\":\"capacity\"}");
            }
            return;
        }

        // SameSite=Lax is correct: the SAML Response from the IdP comes
        // back as a top-level navigation (POST or GET) which Lax permits
        // — Strict would drop the cookie and break the flow.
        Cookie cookie = new Cookie(SamlAuthnRequestRegistry.BINDING_COOKIE_NAME, issued.getBindingToken());
        cookie.setHttpOnly(true);
        // Cookie Secure flag: under nemakiware.public.scheme=https this is
        // ALWAYS true regardless of what request.isSecure() says, so a
        // misconfigured proxy that loses X-Forwarded-Proto cannot silently
        // serve a non-Secure binding cookie over an HTTPS public URL.
        // The misconfig itself surfaces via the warning below.
        PropertyManager pm = getPropertyManager();
        cookie.setSecure(TrustedProxyResolver.shouldFlagCookiesSecure(request, pm));
        cookie.setPath(buildContextPath(request));
        cookie.setMaxAge((int) (issued.getTtlMillis() / 1000L));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        warnIfPublicSchemeMisconfigured(request, pm);

        if (log.isDebugEnabled()) {
            log.debug("SAML initiate: issued AuthnRequest id (length=" + issued.getAuthnRequestId().length() + ")");
        }
        response.setStatus(HttpServletResponse.SC_OK);
        try (PrintWriter out = response.getWriter()) {
            out.print("{\"authnRequestId\":\"" + jsonEscape(issued.getAuthnRequestId()) + "\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /** Token bucket per client address. */
    private boolean checkRateLimit(String clientIp) {
        long now = System.currentTimeMillis();
        // Periodic eviction: when the table grows large, drop stale entries.
        if (rateState.size() > 4096) {
            rateState.entrySet().removeIf(e -> now - e.getValue().windowStart > RATE_WINDOW_MILLIS * 2);
        }
        RateState s = rateState.computeIfAbsent(clientIp, k -> new RateState(now));
        synchronized (s) {
            if (now - s.windowStart > RATE_WINDOW_MILLIS) {
                s.windowStart = now;
                s.count.set(0);
            }
            return s.count.incrementAndGet() <= RATE_LIMIT_PER_MINUTE;
        }
    }

    /**
     * Read the client address. We don't honour X-Forwarded-* here on
     * purpose — the rate limit is a coarse-grained DoS guard and any
     * proxy-based spoofing is the operator's problem; honouring untrusted
     * forwarded headers would let an attacker rotate "client IPs" trivially.
     * Audit IP capture (which DOES check trustedProxies) is unaffected.
     */
    private String clientAddress(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }

    private String buildContextPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return "/";
        }
        return contextPath;
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String safeForLog(String s) {
        if (s == null) return "null";
        String stripped = s.replaceAll("[\\x00-\\x1f\\x7f]", "");
        return stripped.length() > 64 ? stripped.substring(0, 64) + "..." : stripped;
    }

    /**
     * Log a single warning per minute when the operator has declared
     * {@code nemakiware.public.scheme=https} but the actual request does
     * not look secure (proxy chain dropped the scheme). The cookie is
     * still set Secure — see {@link TrustedProxyResolver#shouldFlagCookiesSecure} —
     * but the operator needs to know the proxy is misconfigured.
     */
    private final java.util.concurrent.atomic.AtomicLong lastMisconfigWarnAtMillis = new java.util.concurrent.atomic.AtomicLong(0);

    private void warnIfPublicSchemeMisconfigured(HttpServletRequest request, PropertyManager pm) {
        if (!TrustedProxyResolver.isPublicSchemeMisconfigured(request, pm)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastMisconfigWarnAtMillis.get();
        if (now - last < 60_000L) {
            return;
        }
        if (lastMisconfigWarnAtMillis.compareAndSet(last, now)) {
            log.warn("nemakiware.public.scheme=https but request.isSecure()=false on /saml/initiate "
                    + "(remote=" + request.getRemoteAddr() + "). The proxy in front of NemakiWare is "
                    + "not propagating the TLS scheme to Tomcat. Configure RemoteIpValve to trust the "
                    + "proxy IP, or set X-Forwarded-Proto from the proxy. SAML binding cookie was "
                    + "Secure-flagged anyway to avoid silent downgrade.");
        }
    }

    /**
     * Resolve PropertyManager from the Spring context. Returns null when
     * not yet wired so the cookie code falls back to {@code request.isSecure()}
     * via TrustedProxyResolver's "auto" mode. Cached per servlet instance.
     */
    private volatile PropertyManager cachedPropertyManager;

    private PropertyManager getPropertyManager() {
        PropertyManager pm = cachedPropertyManager;
        if (pm != null) {
            return pm;
        }
        try {
            // First try the per-servlet-context lookup (works when the
            // ContextLoaderListener finished before this servlet ran).
            WebApplicationContext ctx = WebApplicationContextUtils
                    .getWebApplicationContext(getServletContext());
            if (ctx != null && ctx.containsBean("propertyManager")) {
                pm = ctx.getBean("propertyManager", PropertyManager.class);
            }
            // Fall back to the static SpringContext bridge — Jersey-based
            // resources use this and it works even when the servlet was
            // initialised before the WebApplicationContext was published
            // to the ServletContext attribute.
            if (pm == null) {
                ApplicationContext sc = SpringContext.getApplicationContext();
                if (sc != null && sc.containsBean("propertyManager")) {
                    pm = sc.getBean("propertyManager", PropertyManager.class);
                }
            }
            if (pm != null) {
                cachedPropertyManager = pm;
            }
        } catch (Exception e) {
            log.debug("PropertyManager lookup failed: " + e.getMessage());
        }
        return pm;
    }

    private static final class RateState {
        volatile long windowStart;
        final AtomicLong count = new AtomicLong(0);
        RateState(long windowStart) { this.windowStart = windowStart; }
    }
}
