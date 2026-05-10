package jp.aegif.nemaki.rest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

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
        cookie.setSecure(request.isSecure());
        cookie.setPath(buildContextPath(request));
        cookie.setMaxAge((int) (issued.getTtlMillis() / 1000L));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

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

    private static final class RateState {
        volatile long windowStart;
        final AtomicLong count = new AtomicLong(0);
        RateState(long windowStart) { this.windowStart = windowStart; }
    }
}
