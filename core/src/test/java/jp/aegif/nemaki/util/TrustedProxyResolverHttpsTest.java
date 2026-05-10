package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Tests for the cookie-Secure / public-scheme split in
 * {@link TrustedProxyResolver}.
 *
 * <p>The decision was previously a single method that returned false for
 * insecure-looking requests under {@code mode=https}. Callers used that
 * value as the {@code Cookie.setSecure(...)} argument, which silently
 * downgraded cookie security in exactly the misconfigured-proxy
 * scenario we were trying to defend.
 *
 * <p>The corrected design splits the question:
 * <ul>
 *   <li>{@link TrustedProxyResolver#shouldFlagCookiesSecure} —
 *       whether to write the {@code Secure} cookie attribute. Under
 *       {@code mode=https} this is ALWAYS true so a misconfigured
 *       proxy cannot silently produce non-Secure cookies on an HTTPS
 *       public URL.</li>
 *   <li>{@link TrustedProxyResolver#isPublicSchemeMisconfigured} —
 *       whether the operator's declared scheme disagrees with the
 *       actual request, used to log warnings / drive health checks.</li>
 * </ul>
 */
class TrustedProxyResolverHttpsTest {

    // ── shouldFlagCookiesSecure ─────────────────────────────────────

    @Test
    void shouldFlagCookiesSecure_autoMode_followsRequestIsSecure() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("auto", null)));

        when(req.isSecure()).thenReturn(false);
        assertFalse(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("auto", null)));
    }

    @Test
    void shouldFlagCookiesSecure_autoMode_isDefault_whenPropertyMissing() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager(null, null)));
    }

    @Test
    void shouldFlagCookiesSecure_httpsMode_alwaysTrue_evenWhenRequestNotSecure() {
        // The whole point of the fix: a misconfigured proxy that loses
        // X-Forwarded-Proto must not silently produce non-Secure cookies.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn(null);
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void shouldFlagCookiesSecure_httpsMode_alwaysTrue_evenWithUntrustedForwardedHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https"); // attacker claim
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void shouldFlagCookiesSecure_httpMode_alwaysFalse() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertFalse(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("http", null)));
    }

    @Test
    void shouldFlagCookiesSecure_unknownModeFallsBackToAuto() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(req, propertyManager("nonsense", null)));
    }

    @Test
    void shouldFlagCookiesSecure_nullRequestInAutoMode() {
        assertFalse(TrustedProxyResolver.shouldFlagCookiesSecure(null, propertyManager("auto", null)));
    }

    @Test
    void shouldFlagCookiesSecure_nullRequestInHttpsMode_stillTrue() {
        // mode=https is an operator declaration about the public URL,
        // independent of any single request — even a null request still
        // returns true so background-issued cookies are correctly flagged.
        assertTrue(TrustedProxyResolver.shouldFlagCookiesSecure(null, propertyManager("https", null)));
    }

    // ── isPublicSchemeMisconfigured ──────────────────────────────────

    @Test
    void isPublicSchemeMisconfigured_falseInAutoAndHttpModes() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        assertFalse(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("auto", null)));
        assertFalse(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("http", null)));
    }

    @Test
    void isPublicSchemeMisconfigured_falseWhenRequestActuallySecure() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(true);
        assertFalse(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void isPublicSchemeMisconfigured_trueWhenHttpsButRequestIsNot() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn(null);
        assertTrue(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void isPublicSchemeMisconfigured_falseWhenTrustedProxyForwardsHttps() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        // Trusted-proxy-supplied scheme means the deployment IS HTTPS at
        // the public URL even though Tomcat sees plain HTTP. Not a misconfig.
        assertFalse(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void isPublicSchemeMisconfigured_trueWhenForwardedComesFromUntrustedSource() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("203.0.113.50");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        // Untrusted client pretending to be HTTPS — refuse to believe.
        assertTrue(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void isPublicSchemeMisconfigured_trueWhenForwardedSaysHttp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(false);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("http");
        // Trusted proxy explicitly says http — that contradicts the
        // operator declaration and is exactly the misconfig we want to surface.
        assertTrue(TrustedProxyResolver.isPublicSchemeMisconfigured(req, propertyManager("https", "10.0.0.1")));
    }

    @Test
    void isPublicSchemeMisconfigured_nullRequestIsNotMisconfig() {
        assertFalse(TrustedProxyResolver.isPublicSchemeMisconfigured(null, propertyManager("https", null)));
    }

    private static PropertyManager propertyManager(String publicScheme, String trustedProxies) {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(TrustedProxyResolver.PUBLIC_SCHEME_KEY)).thenReturn(publicScheme);
        when(pm.readValue(jp.aegif.nemaki.util.constant.PropertyKey.EXTERNAL_AUTHENTICATION_TRUSTED_PROXIES))
                .thenReturn(trustedProxies);
        return pm;
    }
}
