package jp.aegif.nemaki.api.setup.filter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Table-driven tests for UrlValidator SSRF prevention.
 *
 * Matrix:
 *   - Strict mode  (allowPrivateNetworks=false): blocks private + cloud metadata, no port restriction
 *   - Setup mode   (allowPrivateNetworks=true):  allows private, blocks cloud metadata, port restricted
 *   - Common rules: scheme, host, null/empty — same in both modes
 */
public class UrlValidatorTest {

    // ================================================================
    // Common rules — blocked in BOTH modes
    // ================================================================

    @Test
    public void testNullUrlBlocked() {
        assertNotNull(UrlValidator.validate(null));
        assertNotNull(UrlValidator.validate(null, true));
    }

    @Test
    public void testEmptyUrlBlocked() {
        assertNotNull(UrlValidator.validate(""));
        assertNotNull(UrlValidator.validate("", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com",
            "file:///etc/passwd",
            "gopher://example.com",
            "javascript:alert(1)",
    })
    public void testNonHttpSchemeBlockedInBothModes(String url) {
        assertNotNull(UrlValidator.validate(url), "Strict: " + url);
        assertNotNull(UrlValidator.validate(url, true), "Setup: " + url);
    }

    @Test
    public void testNoHostBlocked() {
        assertNotNull(UrlValidator.validate("http://"));
        assertNotNull(UrlValidator.validate("http://", true));
    }

    @Test
    public void testInvalidSyntaxBlocked() {
        assertNotNull(UrlValidator.validate("://not-valid"));
        assertNotNull(UrlValidator.validate("://not-valid", true));
    }

    // Cloud metadata — ALWAYS blocked (both modes)
    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.169.254/",
            "http://metadata.google.internal/computeMetadata/v1/",
    })
    public void testCloudMetadataBlockedInBothModes(String url) {
        String errStrict = UrlValidator.validate(url);
        assertNotNull(errStrict, "Strict should block cloud metadata: " + url);
        assertTrue(errStrict.contains("metadata"), errStrict);

        String errSetup = UrlValidator.validate(url, true);
        assertNotNull(errSetup, "Setup should block cloud metadata: " + url);
        assertTrue(errSetup.contains("metadata"), errSetup);
    }

    // ================================================================
    // Public addresses — allowed in BOTH modes (on allowed ports)
    // ================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com:5984",
            "https://couch.example.com:6984",
            "http://my-couchdb.company.com:8080",
            "https://example.com",        // default port 443
            "http://example.com",         // default port 80
    })
    public void testPublicUrlAllowedInBothModes(String url) {
        assertNull(UrlValidator.validate(url), "Strict should allow public: " + url);
        assertNull(UrlValidator.validate(url, true), "Setup should allow public: " + url);
    }

    // Unresolvable Docker hostnames — allowed in both modes (on allowed ports)
    @ParameterizedTest
    @ValueSource(strings = {
            "http://couchdb:5984",
            "http://my-service.internal:8080",
    })
    public void testUnresolvableHostAllowedInBothModes(String url) {
        assertNull(UrlValidator.validate(url), "Strict should allow unresolvable: " + url);
        assertNull(UrlValidator.validate(url, true), "Setup should allow unresolvable: " + url);
    }

    // ================================================================
    // Private/loopback addresses — blocked in STRICT, allowed in SETUP
    // ================================================================

    @Nested
    class PrivateAddressMatrix {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1:5984",
                "http://localhost:5984",
        })
        public void testLoopbackBlockedInStrict(String url) {
            String err = UrlValidator.validate(url);
            assertNotNull(err, "Strict should block loopback: " + url);
            assertTrue(err.contains("private") || err.contains("blocked"), err);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1:5984",
                "http://localhost:5984",
                "http://localhost:8080",
        })
        public void testLoopbackAllowedInSetupOnAllowedPort(String url) {
            assertNull(UrlValidator.validate(url, true),
                    "Setup mode should allow loopback on service port: " + url);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://10.0.0.1:5984",
                "http://172.16.0.1:5984",
                "http://172.31.255.255:5984",
                "http://192.168.1.1:5984",
        })
        public void testRfc1918BlockedInStrict(String url) {
            String err = UrlValidator.validate(url);
            assertNotNull(err, "Strict should block RFC1918: " + url);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://10.0.0.1:5984",
                "http://172.16.0.1:5984",
                "http://172.31.255.255:5984",
                "http://192.168.1.1:5984",
                "http://172.17.0.2:5984",   // Docker bridge
        })
        public void testRfc1918AllowedInSetupOnAllowedPort(String url) {
            assertNull(UrlValidator.validate(url, true),
                    "Setup mode should allow RFC1918 on service port: " + url);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://169.254.1.1:5984",
                "http://169.254.100.50:5984",
        })
        public void testLinkLocalBlockedInStrict(String url) {
            String err = UrlValidator.validate(url);
            assertNotNull(err, "Strict should block link-local: " + url);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://169.254.1.1:5984",
                "http://169.254.100.50:5984",
        })
        public void testLinkLocalAllowedInSetupOnAllowedPort(String url) {
            assertNull(UrlValidator.validate(url, true),
                    "Setup mode should allow link-local on service port: " + url);
        }
    }

    // ================================================================
    // Port restriction — Setup mode only
    // ================================================================

    @Nested
    class SetupPortRestriction {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://couchdb:5984",
                "https://couchdb:6984",
                "http://tei-server:8080",
                "https://oidc-provider:443",
                "https://oidc-provider:8443",
                "http://example.com:80",
                "http://example.com",           // default port 80
                "https://example.com",          // default port 443
        })
        public void testAllowedPortsPassInSetup(String url) {
            assertNull(UrlValidator.validate(url, true),
                    "Setup should allow service port: " + url);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://internal-host:22",       // SSH
                "http://internal-host:3306",     // MySQL
                "http://internal-host:5432",     // PostgreSQL
                "http://internal-host:6379",     // Redis
                "http://internal-host:9200",     // Elasticsearch
                "http://internal-host:27017",    // MongoDB
                "http://internal-host:11211",    // Memcached
                "http://internal-host:3389",     // RDP
                "http://internal-host:25",       // SMTP
                "http://internal-host:1234",     // arbitrary
        })
        public void testDisallowedPortsBlockedInSetup(String url) {
            String err = UrlValidator.validate(url, true);
            assertNotNull(err, "Setup should block non-service port: " + url);
            assertTrue(err.contains("Port") || err.contains("not allowed"), err);
        }

        // Strict mode has NO port restriction
        @Test
        public void testStrictModeHasNoPortRestriction() {
            // Port 22 on public host: allowed in strict mode (only private IP is blocked)
            assertNull(UrlValidator.validate("http://example.com:22"),
                    "Strict mode should not restrict ports");
            assertNull(UrlValidator.validate("http://example.com:3306"),
                    "Strict mode should not restrict ports");
        }
    }

    // ================================================================
    // Sanitised error messages — should NOT leak internal details
    // ================================================================

    @Test
    public void testErrorMessageDoesNotLeakIpAddress() {
        // In strict mode, the error should not include the resolved IP
        String err = UrlValidator.validate("http://127.0.0.1:5984");
        assertNotNull(err);
        // The error should say "blocked" but not echo back the exact address in detail
        assertFalse(err.contains("127.0.0.1"),
                "Error should not echo back the IP address: " + err);
    }

    @Test
    public void testInvalidUrlErrorDoesNotLeakParseDetails() {
        String err = UrlValidator.validate("http://[invalid");
        assertNotNull(err);
        // Should not include Java exception details
        assertFalse(err.contains("URISyntaxException"), err);
    }

    // ================================================================
    // Convenience: validate(url) == validate(url, false)
    // ================================================================

    @Test
    public void testDefaultModeIsStrict() {
        String err1 = UrlValidator.validate("http://192.168.1.1:5984");
        String err2 = UrlValidator.validate("http://192.168.1.1:5984", false);
        assertEquals(err1, err2, "Default mode should equal strict mode");

        assertNull(UrlValidator.validate("http://example.com"));
        assertNull(UrlValidator.validate("http://example.com", false));
    }

    // ================================================================
    // SETUP_ALLOWED_PORTS constant visibility
    // ================================================================

    @Test
    public void testSetupAllowedPortsContainsExpectedPorts() {
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(80));
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(443));
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(5984));
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(6984));
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(8080));
        assertTrue(UrlValidator.SETUP_ALLOWED_PORTS.contains(8443));
        assertEquals(6, UrlValidator.SETUP_ALLOWED_PORTS.size());
    }
}
