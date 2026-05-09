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
