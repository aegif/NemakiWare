package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TrustedProxyResolver}.
 *
 * <p>The audit-IP spoofing fix in RC13 hinges on this resolver: forwarded
 * headers must only be honoured when the immediate caller is in
 * {@code external.authentication.trustedProxies}, otherwise an arbitrary
 * client could write a fake source IP into the audit trail.
 */
class TrustedProxyResolverTest {

    @Test
    void isTrusted_acceptsExactMatch() {
        assertTrue(TrustedProxyResolver.isTrusted("10.0.0.1", "10.0.0.1"));
        assertTrue(TrustedProxyResolver.isTrusted("10.0.0.1", " 10.0.0.1 "));
        assertTrue(TrustedProxyResolver.isTrusted("10.0.0.1", "192.168.1.1, 10.0.0.1, 172.16.0.1"));
    }

    @Test
    void isTrusted_rejectsUnknown() {
        assertFalse(TrustedProxyResolver.isTrusted("10.0.0.99", "10.0.0.1"));
        assertFalse(TrustedProxyResolver.isTrusted("10.0.0.1", ""));
        assertFalse(TrustedProxyResolver.isTrusted("", "10.0.0.1"));
        assertFalse(TrustedProxyResolver.isTrusted(null, "10.0.0.1"));
        assertFalse(TrustedProxyResolver.isTrusted("10.0.0.1", (String) null));
    }

    @Test
    void isTrusted_loopbackEquivalence() {
        for (String trusted : new String[]{"127.0.0.1", "localhost"}) {
            assertTrue(TrustedProxyResolver.isTrusted("127.0.0.1", trusted));
            assertTrue(TrustedProxyResolver.isTrusted("::1", trusted));
            assertTrue(TrustedProxyResolver.isTrusted("0:0:0:0:0:0:0:1", trusted));
        }
        assertFalse(TrustedProxyResolver.isTrusted("10.0.0.1", "127.0.0.1"));
    }

    @Test
    void resolveClientIp_untrustedClientCannotSpoof() {
        // Attacker sends X-Forwarded-For but is not a trusted proxy: ignore the header.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-For")).thenReturn("10.0.0.5");
        when(req.getHeader("X-Real-IP")).thenReturn("10.0.0.5");

        // No trustedProxies configured at all
        assertEquals("203.0.113.50", TrustedProxyResolver.resolveClientIp(req, propertyManager(null)));
        // trustedProxies set, but does not include the attacker's IP
        assertEquals("203.0.113.50", TrustedProxyResolver.resolveClientIp(req, propertyManager("10.0.0.99")));
    }

    @Test
    void resolveClientIp_trustedProxyForwardsLeftmostClient() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.50");
        assertEquals("198.51.100.7",
                TrustedProxyResolver.resolveClientIp(req, propertyManager("10.0.0.1")));
    }

    @Test
    void resolveClientIp_trustedProxyFallsBackToXRealIp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getHeader("X-Real-IP")).thenReturn("198.51.100.8");
        assertEquals("198.51.100.8",
                TrustedProxyResolver.resolveClientIp(req, propertyManager("10.0.0.1")));
    }

    @Test
    void resolveClientIp_trustedProxyWithUnknownHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("unknown");
        when(req.getHeader("X-Real-IP")).thenReturn("unknown");
        // No usable forwarded data → record the proxy address rather than ""
        assertEquals("10.0.0.1",
                TrustedProxyResolver.resolveClientIp(req, propertyManager("10.0.0.1")));
    }

    @Test
    void resolveClientIp_stripsCrLfFromForwardedHeader() {
        // Without sanitisation an attacker behind a trusted proxy could inject
        // newlines into the audit log line.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9\r\nFAKE: line");
        String resolved = TrustedProxyResolver.resolveClientIp(req, propertyManager("10.0.0.1"));
        assertNotNull(resolved);
        assertFalse(resolved.contains("\r"));
        assertFalse(resolved.contains("\n"));
        assertTrue(resolved.startsWith("198.51.100.9"));
    }

    @Test
    void sanitize_stripsControlCharsAndCaps() {
        assertNull(TrustedProxyResolver.sanitize(null));
        assertEquals("", TrustedProxyResolver.sanitize(""));
        assertEquals("198.51.100.9 line", TrustedProxyResolver.sanitize("198.51.100.9\r\n line"));
        // Length bound check
        String long1 = "a".repeat(300);
        assertEquals(256, TrustedProxyResolver.sanitize(long1).length());
    }

    private static PropertyManager propertyManager(String trustedProxiesValue) {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(jp.aegif.nemaki.util.constant.PropertyKey.EXTERNAL_AUTHENTICATION_TRUSTED_PROXIES))
                .thenReturn(trustedProxiesValue);
        return pm;
    }
}
