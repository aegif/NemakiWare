package jp.aegif.nemaki.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CsrfValidator#validateBrowserBindingCsrf}, the
 * compatibility-preserving CSRF check applied to CMIS Browser Binding POSTs:
 * reject an explicit cross-site fetch and a cross-origin Origin, but allow
 * header-less non-browser CMIS clients.
 */
public class CsrfValidatorBrowserBindingTest {

    private HttpServletRequest req(String origin, String secFetchSite,
            String scheme, String host, int port) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("Origin")).thenReturn(origin);
        when(r.getHeader("Sec-Fetch-Site")).thenReturn(secFetchSite);
        when(r.getScheme()).thenReturn(scheme);
        when(r.getServerName()).thenReturn(host);
        when(r.getServerPort()).thenReturn(port);
        return r;
    }

    @Test
    public void headerlessNonBrowserClientIsAllowed() {
        // cmislib / TCK / curl send neither Origin nor Sec-Fetch-Site.
        assertNull(CsrfValidator.validateBrowserBindingCsrf(
                req(null, null, "http", "localhost", 8080)));
    }

    @Test
    public void crossSiteFetchIsRejected() {
        assertEquals("cross-site request", CsrfValidator.validateBrowserBindingCsrf(
                req(null, "cross-site", "http", "localhost", 8080)));
    }

    @Test
    public void sameOriginSecFetchIsAllowed() {
        assertNull(CsrfValidator.validateBrowserBindingCsrf(
                req(null, "same-origin", "http", "localhost", 8080)));
        assertNull(CsrfValidator.validateBrowserBindingCsrf(
                req(null, "none", "http", "localhost", 8080)));
    }

    @Test
    public void sameOriginHeaderIsAllowed() {
        assertNull(CsrfValidator.validateBrowserBindingCsrf(
                req("http://localhost:8080", "same-origin", "http", "localhost", 8080)));
    }

    @Test
    public void crossOriginHeaderIsRejected() {
        assertEquals("invalid origin", CsrfValidator.validateBrowserBindingCsrf(
                req("http://evil.example.com", null, "http", "localhost", 8080)));
    }

    @Test
    public void opaqueNullOriginIsRejected() {
        // Browsers send "Origin: null" for opaque origins (sandboxed iframes,
        // some cross-site redirects) — treat as untrusted, not absent.
        assertEquals("invalid origin", CsrfValidator.validateBrowserBindingCsrf(
                req("null", null, "http", "localhost", 8080)));
    }

    @Test
    public void crossSiteWinsOverAValidOrigin() {
        // Sec-Fetch-Site is checked first; a cross-site fetch is rejected even if
        // the Origin somehow matched.
        assertEquals("cross-site request", CsrfValidator.validateBrowserBindingCsrf(
                req("http://localhost:8080", "cross-site", "http", "localhost", 8080)));
    }

    @Test
    public void crossOriginOnHttpsDefaultPort() {
        // Origin on a different host, default HTTPS port on both sides.
        assertEquals("invalid origin", CsrfValidator.validateBrowserBindingCsrf(
                req("https://attacker.test", null, "https", "app.example.com", 443)));
        assertNull(CsrfValidator.validateBrowserBindingCsrf(
                req("https://app.example.com", "same-origin", "https", "app.example.com", 443)));
    }
}
