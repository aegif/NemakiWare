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

    @Test
    void explicitAuthorizationHeaderBypassesCsrfOriginCheck() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-123");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void basicAuthorizationHeaderDoesNotBypassCsrfOriginCheck() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        when(request.getHeader(CallContextKey.AUTH_TOKEN)).thenReturn(null);
        when(request.getHeader("AUTH_TOKEN")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn(null);
        when(request.getHeader("X-Requested-With")).thenReturn(null);

        String result = resource.validate(request);

        assertEquals("missing origin verification headers", result);
    }

    /**
     * Browser Basic auth + explicit X-Requested-With (typical SPA / API test client pattern).
     */
    @Test
    void basicAuthorizationWithXmlHttpRequestPassesCsrfCheck() {
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

    @Test
    void acceptsOriginUsingForwardedHeadersBehindReverseProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("nemaki.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void rejectsMismatchedOriginEvenWithForwardedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("nemaki.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getHeader("Origin")).thenReturn("https://evil.example.com");

        String result = resource.validate(request);

        assertEquals("invalid origin", result);
    }

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

    @Test
    void acceptsOriginUsingForwardedHeaderSingleEntry() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Forwarded"))
                .thenReturn("for=192.0.2.60;proto=https;host=nemaki.example.com");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void acceptsOriginUsingForwardedHeaderWithQuotedHostAndPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Forwarded"))
                .thenReturn("for=192.0.2.60;proto=\"https\";host=\"nemaki.example.com:443\"");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void usesFirstForwardedEntryWhenMultipleArePresent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Forwarded"))
                .thenReturn("for=192.0.2.60;proto=https;host=nemaki.example.com, for=198.51.100.1;proto=http;host=evil.example.com");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void fallsBackToXForwardedHeadersWhenForwardedIsMalformed() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.60;host=");
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("nemaki.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void acceptsOriginWhenForwardedHostHasDifferentCase() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("Forwarded"))
                .thenReturn("for=192.0.2.60;proto=https;host=NEMAKI.EXAMPLE.COM");
        when(request.getHeader("Origin")).thenReturn("https://nemaki.example.com");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void acceptsRefererUsingXForwardedHeadersBehindReverseProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("nemaki.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://nemaki.example.com/core/ui/index.html");

        String result = resource.validate(request);

        assertNull(result);
    }

    @Test
    void rejectsMismatchedRefererUsingXForwardedHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("core-internal");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("nemaki.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://evil.example.com/core/ui/index.html");

        String result = resource.validate(request);

        assertEquals("invalid referer", result);
    }
}
