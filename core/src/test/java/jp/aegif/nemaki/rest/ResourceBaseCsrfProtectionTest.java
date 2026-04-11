package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceBaseCsrfProtectionTest {

    private static class TestableResourceBase extends ResourceBase {
        String validate(HttpServletRequest request) {
            return validateCsrfProtection(request);
        }
    }

    private final TestableResourceBase resource = new TestableResourceBase();

    // ── Auth-header bypass ──────────────────────────────────────────

    @Test
    void explicitAuthorizationHeaderBypassesCsrfOriginCheck() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-123");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void basicAuthorizationHeaderDoesNotBypassCsrfOriginCheck() {
        // Basic auth is an ambient credential (browsers cache and auto-attach it),
        // so it must NOT bypass CSRF validation.
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        when(request.getHeader(CallContextKey.AUTH_TOKEN)).thenReturn(null);
        when(request.getHeader("AUTH_TOKEN")).thenReturn(null);
        when(request.getHeader(CallContextKey.AUTH_TOKEN_APP)).thenReturn(null);
        when(request.getHeader("AUTH_TOKEN_APP")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn(null);
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        String result = resource.validate(request);

        assertEquals("missing origin verification headers", result);
    }

    @Test
    void basicAuthorizationWithXmlHttpRequestPassesCsrfCheck() {
        // Basic auth + explicit X-Requested-With (typical SPA / API test client pattern).
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        when(request.getHeader(CallContextKey.AUTH_TOKEN)).thenReturn(null);
        when(request.getHeader("AUTH_TOKEN")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn(null);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void explicitAuthTokenHeaderBypassesCsrfOriginCheck() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CallContextKey.AUTH_TOKEN)).thenReturn("token-abc");

        String result = resource.validate(request);

        assertNull(result);
    }

    // ── No credentials → reject ─────────────────────────────────────

    @Test
    void cookieStyleRequestWithoutOriginHeadersIsRejected() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader(CallContextKey.AUTH_TOKEN)).thenReturn(null);
        when(request.getHeader("AUTH_TOKEN")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn(null);
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        String result = resource.validate(request);

        assertEquals("missing origin verification headers", result);
    }

    // ── Origin matching (servlet values reflect RemoteIpValve output) ─

    @Test
    void acceptsOriginMatchingServletValues() {
        // RemoteIpValve rewrites servlet values when behind a trusted proxy,
        // so getScheme()/getServerName()/getServerPort() already reflect the
        // public-facing origin.
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void rejectsMismatchedOrigin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn("https://evil.example.com");

        String result = resource.validate(request);

        assertEquals("invalid origin", result);
    }

    @Test
    void acceptsOriginOnNonStandardPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(8443);
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com:8443");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void rejectsOriginWithPortMismatch() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com:8443");

        String result = resource.validate(request);

        assertEquals("invalid origin", result);
    }

    @Test
    void originCheckIsCaseInsensitive() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("HTTPS");
        when(request.getServerName()).thenReturn("NEMAKI.EXAMPLE.COM");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    // ── Referer matching ────────────────────────────────────────────

    @Test
    void acceptsRefererMatchingServletValues() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://nemaki.example.com/core/ui/index.html");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void rejectsMismatchedReferer() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("nemaki.example.com");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://evil.example.com/core/ui/index.html");

        String result = resource.validate(request);

        assertEquals("invalid referer", result);
    }

    // ── Localhost dev bypass ────────────────────────────────────────

    @Test
    void localhostDevBypassAcceptsLocalhostTo127() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Origin")).thenReturn("http://127.0.0.1:8080");

        String result = resource.validate(request);

        assertNull(result);
    }

    // ── Null scheme safety ──────────────────────────────────────────

    @Test
    void nullSchemeDoesNotCauseNpeOnRefererCheck() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn(null);
        when(request.getServerName()).thenReturn(null);
        when(request.getServerPort()).thenReturn(-1);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://example.com/foo");

        String result = resource.validate(request);

        assertEquals("invalid referer", result);
    }
}
