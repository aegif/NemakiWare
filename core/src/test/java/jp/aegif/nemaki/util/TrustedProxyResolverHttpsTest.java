package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TrustedProxyResolver#isPublicRequestSecure}.
 *
 * <p>Cookie Secure flagging used to fall back to {@code request.isSecure()}
 * directly, which silently weakens behind a misconfigured proxy whose
 * {@code X-Forwarded-Proto} the local Tomcat valve does not honour.
 * The {@code nemakiware.public.scheme=https} mode lets operators force
 * fail-closed semantics so misconfigured proxies break loudly rather
 * than degrade quietly.
 */
class TrustedProxyResolverHttpsTest {

    @Test
    void autoMode_trustsRequestIsSecure() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("auto", null)));

        when(req.isSecure()).thenReturn(false);
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("auto", null)));
    }

    @Test
    void autoMode_isDefault_whenPropertyMissing() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager(null, null)));
    }

    @Test
    void httpsMode_failsClosed_whenRequestNotSecure() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        // Even with X-Forwarded-Proto: https, an untrusted proxy's claim
        // is ignored and the call returns false in https mode.
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void httpsMode_acceptsForwardedHttpsFromTrustedProxy() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true); // Tomcat already rewrote isSecure
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        assertTrue(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void httpsMode_rejectsWhenForwardedHeaderSaysHttp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("http");
        // Misconfigured proxy: TLS terminated locally but forwarded says
        // http. Force-fail mode catches it.
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void httpsMode_rejectsWhenForwardedComesFromUntrustedProxy() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        // Even though "https" is what we want, refusing to trust the
        // claim from an untrusted source is the correct conservative call.
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void httpsMode_acceptsWhenForwardedHeaderAbsent() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn(null);
        // Direct TLS to Tomcat with no proxy in front: isSecure is enough.
        assertTrue(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("https", null)));
    }

    @Test
    void httpMode_alwaysReturnsFalse() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("http", null)));
    }

    @Test
    void unknownModeFallsBackToAuto() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.isPublicRequestSecure(req, propertyManager("nonsense", null)));
    }

    @Test
    void nullRequestIsRejected() {
        assertFalse(TrustedProxyResolver.isPublicRequestSecure(null, propertyManager("https", null)));
    }

    private static PropertyManager propertyManager(String publicScheme, String trustedProxies) {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(TrustedProxyResolver.PUBLIC_SCHEME_KEY)).thenReturn(publicScheme);
        when(pm.readValue(jp.aegif.nemaki.util.constant.PropertyKey.EXTERNAL_AUTHENTICATION_TRUSTED_PROXIES))
                .thenReturn(trustedProxies);
        return pm;
    }
}
