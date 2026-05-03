package jp.aegif.nemaki.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CsrfInterceptor.validateCsrf().
 */
class CsrfInterceptorTest {

    private MockHttpServletRequest post(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(8080);
        return req;
    }

    // ── Bypass: explicit auth headers ──

    @Test
    void bearerToken_bypassesCsrf() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Authorization", "Bearer abc123");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void authToken_bypassesCsrf() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("AUTH_TOKEN", "token123");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void apiKey_bypassesCsrf() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("X-API-Key", "key123");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void legacyAuthToken_bypassesCsrf() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("nemaki_auth_token", "token123");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void legacyAuthTokenApp_bypassesCsrf() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("nemaki_auth_token_app", "app123");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void basicAuth_doesNotBypass() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Authorization", "Basic YWRtaW46YWRtaW4=");
        // Basic auth is ambient credential — should NOT bypass
        String error = CsrfInterceptor.validateCsrf(req);
        assertNotNull(error, "Basic auth must not bypass CSRF");
    }

    // ── Bypass: Origin header ──

    @Test
    void matchingOrigin_passes() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Origin", "http://localhost:8080");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void mismatchedOrigin_rejected() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Origin", "http://evil.com");
        assertEquals("invalid origin", CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void originWithDefaultPort_passes() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.setServerPort(80);
        req.addHeader("Origin", "http://localhost");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    // ── Bypass: Referer header ──

    @Test
    void matchingReferer_passes() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Referer", "http://localhost:8080/core/ui/");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    @Test
    void mismatchedReferer_rejected() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("Referer", "http://evil.com/page");
        assertEquals("invalid referer", CsrfInterceptor.validateCsrf(req));
    }

    // ── Bypass: X-Requested-With ──

    @Test
    void xRequestedWith_passes() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.addHeader("X-Requested-With", "XMLHttpRequest");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    // ── No bypass headers ──

    @Test
    void noHeaders_rejected() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        String error = CsrfInterceptor.validateCsrf(req);
        assertEquals("missing origin verification headers", error);
    }

    // ── Localhost equivalence ──

    @Test
    void localhost127_equivalence() {
        MockHttpServletRequest req = post("/api/v1/admin/connectors");
        req.setServerName("127.0.0.1");
        req.addHeader("Origin", "http://localhost:8080");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }

    // ── Webhook path exemption ──

    @Test
    void webhookReceiver_exemptFromCsrf_servletPathStyle() throws Exception {
        // Test environment: full path in servletPath, no pathInfo
        MockHttpServletRequest req = post("/v1/ingest-webhook/slack-1");
        req.setServletPath("/v1/ingest-webhook/slack-1");
        var interceptor = new CsrfInterceptor();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, response, null));
    }

    @Test
    void webhookReceiver_exemptFromCsrf_dispatcherServletStyle() throws Exception {
        // Real DispatcherServlet: servletPath=/api, pathInfo=/v1/...
        MockHttpServletRequest req = post("/api/v1/ingest-webhook/slack-1");
        req.setServletPath("/api");
        req.setPathInfo("/v1/ingest-webhook/slack-1");
        var interceptor = new CsrfInterceptor();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, response, null));
    }

    @Test
    void webhookSubscribe_requiresCsrf() throws Exception {
        MockHttpServletRequest req = post("/api/v1/ingest-webhook/slack-1/subscribe");
        req.setServletPath("/api");
        req.setPathInfo("/v1/ingest-webhook/slack-1/subscribe");
        var interceptor = new CsrfInterceptor();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void webhookSubscribe_withXRequestedWith_passes() throws Exception {
        MockHttpServletRequest req = post("/api/v1/ingest-webhook/slack-1/subscribe");
        req.setServletPath("/api");
        req.setPathInfo("/v1/ingest-webhook/slack-1/subscribe");
        req.addHeader("X-Requested-With", "XMLHttpRequest");
        var interceptor = new CsrfInterceptor();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, response, null));
    }

    // ── HTTPS port normalization ──

    @Test
    void httpsDefaultPort_passes() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/connectors");
        req.setScheme("https");
        req.setServerName("example.com");
        req.setServerPort(443);
        req.addHeader("Origin", "https://example.com");
        assertNull(CsrfInterceptor.validateCsrf(req));
    }
}
