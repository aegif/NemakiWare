package jp.aegif.nemaki.rest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimpleCorsFilter origin resolution and CORS header behavior.
 */
class SimpleCorsFilterTest {

    // ── Default (wildcard) ──

    @Test
    void defaultOrigins_returnsWildcard() throws Exception {
        var filter = new SimpleCorsFilter();
        filter.resolveOrigins(); // no PropertyManager → stays *

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "http://evil.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertEquals("*", res.getHeader("Access-Control-Allow-Origin"));
    }

    // ── Restricted origin ──

    @Test
    void restrictedOrigin_matchReturnsEcho() throws Exception {
        var filter = createFilterWithOrigins("https://ecm.example.com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "https://ecm.example.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertEquals("https://ecm.example.com", res.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void restrictedOrigin_mismatchReturnsNoHeader() throws Exception {
        var filter = createFilterWithOrigins("https://ecm.example.com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "http://evil.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertNull(res.getHeader("Access-Control-Allow-Origin"),
                "Mismatched origin should not get CORS headers");
    }

    // ── Multiple origins ──

    @Test
    void multipleOrigins_secondMatches() throws Exception {
        var filter = createFilterWithOrigins("https://app1.com, https://app2.com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "https://app2.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertEquals("https://app2.com", res.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void multipleOrigins_noneMatches() throws Exception {
        var filter = createFilterWithOrigins("https://app1.com,https://app2.com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "https://evil.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertNull(res.getHeader("Access-Control-Allow-Origin"));
    }

    // ── Case insensitive ──

    @Test
    void originMatch_caseInsensitive() throws Exception {
        var filter = createFilterWithOrigins("https://ECM.Example.Com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        req.addHeader("Origin", "https://ecm.example.com");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertEquals("https://ecm.example.com", res.getHeader("Access-Control-Allow-Origin"));
    }

    // ── OPTIONS preflight ──

    @Test
    void optionsPreflight_returns200() throws Exception {
        var filter = new SimpleCorsFilter();
        filter.resolveOrigins();

        var req = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/connectors");
        req.addHeader("Origin", "http://localhost:5173");
        req.addHeader("Access-Control-Request-Method", "POST");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertEquals("*", res.getHeader("Access-Control-Allow-Origin"));
        // Chain should NOT have been called (preflight short-circuit)
        assertNull(chain.getRequest(), "OPTIONS should not reach the servlet");
    }

    // ── No Origin header ──

    @Test
    void noOriginHeader_restrictedMode_noCorsHeaders() throws Exception {
        var filter = createFilterWithOrigins("https://ecm.example.com");

        var req = new MockHttpServletRequest("GET", "/api/v1/admin/connectors");
        // No Origin header
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertNull(res.getHeader("Access-Control-Allow-Origin"),
                "No Origin → no CORS headers needed");
    }

    // ── Allowed headers include CSRF bypass headers ──

    @Test
    void allowedHeaders_includesCsrfBypassHeaders() throws Exception {
        var filter = new SimpleCorsFilter();
        filter.resolveOrigins();

        var req = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/connectors");
        req.addHeader("Origin", "http://localhost:5173");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        String headers = res.getHeader("Access-Control-Allow-Headers");
        assertTrue(headers.contains("X-Requested-With"), "Must allow X-Requested-With for CSRF bypass");
        assertTrue(headers.contains("X-API-Key"), "Must allow X-API-Key for CSRF bypass");
        assertTrue(headers.contains("AUTH_TOKEN"), "Must allow AUTH_TOKEN");
    }

    // ── Helper ──

    private SimpleCorsFilter createFilterWithOrigins(String origins) {
        var filter = new SimpleCorsFilter();
        // Simulate PropertyManager by using System property fallback
        System.setProperty("api.cors.allowedOrigins", origins);
        try {
            filter.resolveOrigins();
        } finally {
            System.clearProperty("api.cors.allowedOrigins");
        }
        return filter;
    }
}
