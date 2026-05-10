package jp.aegif.nemaki.util;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared trusted-proxy logic.
 *
 * <p>The proxy-header-based authentication flow in
 * {@code AuthenticationServiceImpl} and the audit-log client IP capture in
 * {@code AuditLogger} both need to know whether a request came through a
 * trusted reverse proxy before honouring forwarded-for headers. Spreading
 * the check across two implementations would let the audit path drift open
 * (the bug fixed here was that the audit path always trusted X-Forwarded-For,
 * so an arbitrary client could spoof the source IP recorded in the audit
 * trail). Centralise the policy here so a future change to
 * {@code external.authentication.trustedProxies} updates every consumer.
 */
public final class TrustedProxyResolver {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyResolver.class);

    private TrustedProxyResolver() {}

    /**
     * @return true if {@code remoteAddr} is listed in the configured
     *         {@code external.authentication.trustedProxies} CSV (with
     *         loopback equivalence handled).
     */
    public static boolean isTrusted(String remoteAddr, PropertyManager propertyManager) {
        if (remoteAddr == null || propertyManager == null) {
            return false;
        }
        String configured = propertyManager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_TRUSTED_PROXIES);
        return isTrusted(remoteAddr, configured);
    }

    /** Pure-string overload, useful for tests. */
    public static boolean isTrusted(String remoteAddr, String trustedProxies) {
        if (StringUtils.isBlank(remoteAddr) || StringUtils.isBlank(trustedProxies)) {
            return false;
        }
        for (String raw : trustedProxies.split(",")) {
            String trusted = raw.trim();
            if (trusted.isEmpty()) {
                continue;
            }
            if (trusted.equals(remoteAddr)) {
                return true;
            }
            if (("127.0.0.1".equals(trusted) || "localhost".equals(trusted))
                    && ("127.0.0.1".equals(remoteAddr)
                        || "::1".equals(remoteAddr)
                        || "0:0:0:0:0:0:0:1".equals(remoteAddr))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve the effective client IP, honouring forwarded headers ONLY when
     * the immediate caller is a trusted proxy. Strips CR/LF to prevent log
     * injection. Falls back to {@link HttpServletRequest#getRemoteAddr()}
     * when the chain cannot be trusted.
     */
    public static String resolveClientIp(HttpServletRequest request, PropertyManager propertyManager) {
        if (request == null) {
            return null;
        }
        String remoteAddr = sanitize(request.getRemoteAddr());
        if (!isTrusted(remoteAddr, propertyManager)) {
            return remoteAddr;
        }
        // Trusted proxy: prefer the leftmost (original client) entry of
        // X-Forwarded-For, falling back to X-Real-IP.
        String forwarded = sanitize(request.getHeader("X-Forwarded-For"));
        if (forwarded != null && !"unknown".equalsIgnoreCase(forwarded)) {
            int comma = forwarded.indexOf(',');
            String client = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!client.isEmpty()) {
                return client;
            }
        }
        String real = sanitize(request.getHeader("X-Real-IP"));
        if (real != null && !real.isEmpty() && !"unknown".equalsIgnoreCase(real)) {
            return real;
        }
        if (log.isDebugEnabled()) {
            log.debug("Trusted proxy {} sent neither X-Forwarded-For nor X-Real-IP; recording proxy address", remoteAddr);
        }
        return remoteAddr;
    }

    /**
     * Property key consumed by {@link #shouldFlagCookiesSecure(HttpServletRequest, PropertyManager)}.
     * Values:
     * <ul>
     *   <li>{@code auto} — default. Trust {@link HttpServletRequest#isSecure()},
     *       which Tomcat's {@code RemoteIpValve} rewrites for requests from
     *       trusted proxies that set {@code X-Forwarded-Proto: https}.</li>
     *   <li>{@code https} — operator declares the public URL is HTTPS.
     *       Cookies are ALWAYS flagged Secure regardless of what the
     *       inbound servlet request reports, so a broken proxy hop cannot
     *       silently downgrade cookie security. Mismatches between the
     *       declared scheme and the actual request are surfaced via
     *       {@link #isPublicSchemeMisconfigured(HttpServletRequest, PropertyManager)}
     *       and logged loudly so operators notice the proxy bug.</li>
     *   <li>{@code http} — explicit HTTP-only deployment. Development only.</li>
     * </ul>
     */
    public static final String PUBLIC_SCHEME_KEY = "nemakiware.public.scheme";

    private static final String MODE_AUTO  = "auto";
    private static final String MODE_HTTPS = "https";
    private static final String MODE_HTTP  = "http";

    /**
     * Decide whether to flag cookies issued for this request with the
     * {@code Secure} attribute.
     *
     * <p><b>Important asymmetry.</b> Under {@code mode=https} this method
     * returns true unconditionally — even when {@link HttpServletRequest#isSecure()}
     * reports false, which can happen behind a misconfigured proxy that
     * strips {@code X-Forwarded-Proto} or whose IP isn't in
     * {@code RemoteIpValve.internalProxies}. Returning false in that
     * scenario would mean we omit the {@code Secure} attribute on cookies
     * served via an HTTPS-terminated public URL, and the browser would
     * happily accept (and later replay over HTTP) a non-Secure cookie —
     * the exact silent downgrade we are trying to prevent. The operator
     * has declared the public URL is HTTPS, so cookies must always be
     * Secure-flagged.
     *
     * <p>The companion {@link #isPublicSchemeMisconfigured} surfaces the
     * misconfig itself (proxy not propagating the scheme correctly) so
     * operators can see and fix it loudly, without paying for that
     * detection by weakening cookie security in the meantime.
     */
    public static boolean shouldFlagCookiesSecure(HttpServletRequest request, PropertyManager propertyManager) {
        String mode = readMode(propertyManager);
        switch (mode) {
            case MODE_HTTPS:
                return true;            // operator-declared HTTPS — always Secure
            case MODE_HTTP:
                return false;           // explicit dev-only HTTP
            case MODE_AUTO:
            default:
                return request != null && request.isSecure();
        }
    }

    /**
     * True when the operator declared the public URL is HTTPS but the
     * actual request does not look secure — i.e. a proxy hop is dropping
     * the {@code X-Forwarded-Proto} signal or {@code RemoteIpValve} has
     * not been configured to trust the proxy's IP.
     *
     * <p>Use to log a warning, raise a health-check alert, or fail-close
     * a particularly sensitive operation. Always returns false outside
     * {@code mode=https} so that {@code auto} / {@code http} deployments
     * are never spuriously flagged.
     */
    public static boolean isPublicSchemeMisconfigured(HttpServletRequest request, PropertyManager propertyManager) {
        if (request == null) {
            return false;
        }
        if (!MODE_HTTPS.equals(readMode(propertyManager))) {
            return false;
        }
        if (request.isSecure()) {
            return false; // RemoteIpValve already corrected the scheme
        }
        // Honour an X-Forwarded-Proto: https only when it came from a trusted
        // proxy. If neither isSecure nor a trusted forwarded header, the
        // proxy chain is misconfigured.
        String forwarded = sanitize(request.getHeader("X-Forwarded-Proto"));
        if (forwarded != null && "https".equalsIgnoreCase(forwarded.trim())
                && isTrusted(sanitize(request.getRemoteAddr()), propertyManager)) {
            return false;
        }
        return true;
    }

    private static String readMode(PropertyManager propertyManager) {
        String configured = propertyManager == null ? null : propertyManager.readValue(PUBLIC_SCHEME_KEY);
        String mode = configured == null ? MODE_AUTO : configured.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.isEmpty() ? MODE_AUTO : mode;
    }

    /**
     * Strip CR/LF (and any control character that would let an attacker break
     * audit log lines) from a header value. Keeps null/blank semantics.
     */
    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        // Strip CR, LF, and other ASCII control chars; bound length defensively.
        String stripped = value.replaceAll("[\\x00-\\x1f\\x7f]", "");
        if (stripped.length() > 256) {
            stripped = stripped.substring(0, 256);
        }
        return stripped;
    }
}
