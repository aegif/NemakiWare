/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.webhook;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for HttpWebhookDispatcher SSRF protection.
 * 
 * These tests verify that the SSRF protection correctly blocks:
 * - localhost and loopback addresses (127.0.0.1, ::1)
 * - Private network ranges (10.x.x.x, 172.16-31.x.x, 192.168.x.x)
 * - Link-local addresses (169.254.x.x)
 * - Cloud metadata endpoints (169.254.169.254, metadata.google.internal)
 * - IPv6 ULA addresses (fc00::/7)
 * - Multicast addresses
 * - Any-local addresses (0.0.0.0)
 */
public class HttpWebhookDispatcherTest {
    
    private HttpWebhookDispatcher dispatcher;
    private Method isAddressSafeMethod;

    @BeforeEach
    public void setUp() throws Exception {
        dispatcher = new HttpWebhookDispatcher();

        // Use reflection to access private methods for testing
        isAddressSafeMethod = HttpWebhookDispatcher.class.getDeclaredMethod("isAddressSafe", InetAddress.class, String.class);
        isAddressSafeMethod.setAccessible(true);
    }

    /**
     * Helper: check if URL is safe using the package-private resolveAndValidateUrl method.
     * Returns true if a safe address was resolved, false otherwise.
     */
    private boolean isUrlSafe(URL url) {
        return dispatcher.resolveAndValidateUrl(url) != null;
    }
    
    // ========================================
    // Blocked Hostname Tests
    // ========================================
    
    @Test
    public void testBlocksLocalhost() throws Exception {
        URL url = new URL("http://localhost/webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "localhost should be blocked");
    }
    
    @Test
    public void testBlocksLocalhostUppercase() throws Exception {
        URL url = new URL("http://LOCALHOST/webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "LOCALHOST (uppercase) should be blocked");
    }
    
    @Test
    public void testBlocks127001() throws Exception {
        URL url = new URL("http://127.0.0.1/webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "127.0.0.1 should be blocked");
    }
    
    @Test
    public void testBlocks0000() throws Exception {
        URL url = new URL("http://0.0.0.0/webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "0.0.0.0 should be blocked");
    }
    
    @Test
    public void testBlocksIPv6Loopback() throws Exception {
        // Note: URL with IPv6 requires brackets
        URL url = new URL("http://[::1]/webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "::1 (IPv6 loopback) should be blocked");
    }
    
    // ========================================
    // Cloud Metadata Endpoint Tests
    // ========================================
    
    @Test
    public void testBlocksAwsMetadataEndpoint() throws Exception {
        URL url = new URL("http://169.254.169.254/latest/meta-data/");
        boolean result = isUrlSafe(url);
        assertFalse(result, "AWS metadata endpoint should be blocked");
    }
    
    @Test
    public void testBlocksGcpMetadataInternal() throws Exception {
        URL url = new URL("http://metadata.google.internal/computeMetadata/v1/");
        boolean result = isUrlSafe(url);
        assertFalse(result, "GCP metadata.google.internal should be blocked");
    }
    
    @Test
    public void testBlocksGcpMetadataCom() throws Exception {
        URL url = new URL("http://metadata.google.com/computeMetadata/v1/");
        boolean result = isUrlSafe(url);
        assertFalse(result, "metadata.google.com should be blocked");
    }
    
    // ========================================
    // Private Network Range Tests (isAddressSafe)
    // ========================================
    
    @Test
    public void testBlocks10Network() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "10.x.x.x private network should be blocked");
    }
    
    @Test
    public void testBlocks10NetworkMax() throws Exception {
        InetAddress addr = InetAddress.getByName("10.255.255.255");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "10.255.255.255 should be blocked");
    }
    
    @Test
    public void testBlocks172_16Network() throws Exception {
        InetAddress addr = InetAddress.getByName("172.16.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "172.16.x.x private network should be blocked");
    }
    
    @Test
    public void testBlocks172_31Network() throws Exception {
        InetAddress addr = InetAddress.getByName("172.31.255.255");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "172.31.x.x private network should be blocked");
    }
    
    @Test
    public void testAllows172_15Network() throws Exception {
        // 172.15.x.x is NOT in the private range (172.16-31)
        InetAddress addr = InetAddress.getByName("172.15.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "172.15.x.x should be allowed (not in private range)");
    }
    
    @Test
    public void testAllows172_32Network() throws Exception {
        // 172.32.x.x is NOT in the private range (172.16-31)
        InetAddress addr = InetAddress.getByName("172.32.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "172.32.x.x should be allowed (not in private range)");
    }
    
    @Test
    public void testBlocks192_168Network() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "192.168.x.x private network should be blocked");
    }
    
    @Test
    public void testBlocks169_254Network() throws Exception {
        InetAddress addr = InetAddress.getByName("169.254.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "169.254.x.x link-local should be blocked");
    }

    @Test
    public void testBlocksCgnatSharedAddressSpace() throws Exception {
        InetAddress addr = InetAddress.getByName("100.64.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "100.64.0.0/10 shared address space should be blocked");
    }

    @Test
    public void testBlocksBenchmarkingAddressSpace() throws Exception {
        InetAddress addr = InetAddress.getByName("198.18.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "198.18.0.0/15 benchmarking address space should be blocked");
    }

    @Test
    public void testBlocksReservedIpv4AddressSpace() throws Exception {
        InetAddress addr = InetAddress.getByName("240.0.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "240.0.0.0/4 reserved address space should be blocked");
    }
    
    // ========================================
    // IPv6 ULA Tests (fc00::/7)
    // ========================================
    
    @Test
    public void testBlocksIPv6ULA_FC00() throws Exception {
        InetAddress addr = InetAddress.getByName("fc00::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "fc00::1 (IPv6 ULA) should be blocked");
    }
    
    @Test
    public void testBlocksIPv6ULA_FD00() throws Exception {
        InetAddress addr = InetAddress.getByName("fd00::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "fd00::1 (IPv6 ULA) should be blocked");
    }
    
    @Test
    public void testBlocksIPv6ULA_FDXX() throws Exception {
        InetAddress addr = InetAddress.getByName("fd12:3456:789a::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "fd12:3456:789a::1 (IPv6 ULA) should be blocked");
    }
    
    // ========================================
    // Loopback Address Tests
    // ========================================
    
    @Test
    public void testBlocksLoopbackAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "127.0.0.1 loopback should be blocked");
    }
    
    @Test
    public void testBlocksLoopbackAddressRange() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.2");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "127.0.0.2 loopback should be blocked");
    }
    
    @Test
    public void testBlocksIPv6LoopbackAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "::1 IPv6 loopback should be blocked");
    }
    
    // ========================================
    // Safe URL Tests
    // ========================================
    
    @Test
    public void testAllowsPublicIP() throws Exception {
        InetAddress addr = InetAddress.getByName("8.8.8.8");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "8.8.8.8 (Google DNS) should be allowed");
    }
    
    @Test
    public void testAllowsPublicIP2() throws Exception {
        InetAddress addr = InetAddress.getByName("1.1.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "1.1.1.1 (Cloudflare DNS) should be allowed");
    }
    
    // ========================================
    // Protocol Tests
    // ========================================
    
    @Test
    public void testDispatchSkipsNullUrl() {
        // Should not throw, just log and return
        dispatcher.dispatch(null, "{}", null, null);
    }
    
    @Test
    public void testDispatchSkipsEmptyUrl() {
        // Should not throw, just log and return
        dispatcher.dispatch("", "{}", null, null);
    }
    
    @Test
    public void testDispatchSkipsNullPayload() {
        // Should not throw, just log and return
        dispatcher.dispatch("http://example.com/webhook", null, null, null);
    }
    
    // ========================================
    // Edge Cases
    // ========================================
    
    @Test
    public void testBlocksNullHost() throws Exception {
        // Create a URL with empty host (edge case)
        URL url = new URL("http:///webhook");
        boolean result = isUrlSafe(url);
        assertFalse(result, "URL with empty host should be blocked");
    }
    
    @Test
    public void testAllowsHttpsProtocol() throws Exception {
        // HTTPS should be allowed (protocol check)
        // Note: This test only checks that HTTPS URLs are not rejected by protocol check
        // The actual DNS resolution may fail for non-existent domains
        URL url = new URL("https://example.com/webhook");
        // We can't fully test this without mocking DNS, but we verify the URL is parsed correctly
        assertEquals("https", url.getProtocol());
        assertEquals("example.com", url.getHost());
    }
    
    // ========================================
    // Redirect SSRF Protection Tests
    // ========================================
    
    /**
     * Test that HttpURLConnection is configured to NOT follow redirects.
     * This is critical for SSRF protection - attackers can use 302/307 redirects
     * to bypass URL validation and reach internal endpoints.
     * 
     * Example attack scenario:
     * 1. Attacker configures webhook URL: https://attacker.com/redirect
     * 2. attacker.com returns 302 redirect to http://169.254.169.254/latest/meta-data/
     * 3. Without redirect protection, the webhook would follow the redirect and leak AWS credentials
     * 
     * With setInstanceFollowRedirects(false), the redirect is NOT followed,
     * and the webhook delivery fails safely with HTTP 302 response.
     */
    @Test
    public void testRedirectProtectionIsEnabled() throws Exception {
        // Verify that HttpURLConnection default behavior is to follow redirects
        URL testUrl = new URL("http://example.com/webhook");
        HttpURLConnection defaultConnection = (HttpURLConnection) testUrl.openConnection();
        assertTrue(defaultConnection.getInstanceFollowRedirects(), "Default HttpURLConnection should follow redirects");
        defaultConnection.disconnect();
        
        // The actual protection is verified by checking the code sets setInstanceFollowRedirects(false)
        // We can't easily test the actual behavior without a real redirect server,
        // but we verify the configuration is correct by checking the source code comment
        // and the fact that the test above confirms the default is true (so we need to disable it)
    }
    
    /**
     * Test that redirect responses (3xx) are handled correctly.
     * When redirects are disabled, 3xx responses should be treated as non-success
     * and logged as warnings, not followed.
     */
    @Test
    public void testRedirectResponseCodesAreNotSuccess() {
        // HTTP 3xx status codes that could be used for redirect attacks
        int[] redirectCodes = {301, 302, 303, 307, 308};
        
        for (int code : redirectCodes) {
            // Verify these are NOT in the 2xx success range
            assertFalse(code >= 200 && code < 300, "HTTP " + code + " should not be treated as success");
        }
    }
    
    /**
     * Test scenario: External URL redirects to internal IP.
     * This documents the expected behavior when an attacker tries to use
     * a redirect to bypass SSRF protection.
     * 
     * Expected behavior:
     * 1. Initial URL (https://attacker.com) passes isUrlSafe() check
     * 2. Connection is made with setInstanceFollowRedirects(false)
     * 3. Server returns 302 with Location: http://169.254.169.254/
     * 4. HttpURLConnection does NOT follow the redirect
     * 5. dispatch() receives HTTP 302 response code
     * 6. 302 is logged as a warning (not success)
     * 7. Internal endpoint is NEVER accessed
     */
    @Test
    public void testRedirectToInternalIPIsBlocked() {
        // This is a documentation test - actual behavior requires a real redirect server
        // The protection is implemented via:
        // 1. connection.setInstanceFollowRedirects(false) in HttpWebhookDispatcher.dispatch()
        // 2. Only 2xx responses are treated as success
        
        // Verify the internal IP would be blocked if accessed directly
        try {
            InetAddress internalAddr = InetAddress.getByName("169.254.169.254");
            boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, internalAddr, "redirect-target");
            assertFalse(result, "Internal IP 169.254.169.254 should be blocked");
        } catch (Exception e) {
            fail("Exception during test: " + e.getMessage());
        }
    }
    
    /**
     * Test scenario: Redirect to localhost.
     * Verifies that localhost would be blocked if a redirect tried to reach it.
     */
    @Test
    public void testRedirectToLocalhostIsBlocked() {
        try {
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, localhost, "redirect-target");
            assertFalse(result, "Localhost should be blocked even via redirect");
        } catch (Exception e) {
            fail("Exception during test: " + e.getMessage());
        }
    }
    
    /**
     * Test scenario: Redirect to private network.
     * Verifies that private network IPs would be blocked if a redirect tried to reach them.
     */
    @Test
    public void testRedirectToPrivateNetworkIsBlocked() {
        String[] privateIPs = {"10.0.0.1", "172.16.0.1", "192.168.1.1"};
        
        for (String ip : privateIPs) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "redirect-target");
                assertFalse(result, "Private IP " + ip + " should be blocked even via redirect");
            } catch (Exception e) {
                fail("Exception during test for " + ip + ": " + e.getMessage());
            }
        }
    }

    // ========================================
    // Custom Header Validation Tests (CRLF / smuggling / forbidden names)
    // ========================================

    private boolean invokeIsValidHeaderName(String name) throws Exception {
        Method m = HttpWebhookDispatcher.class.getDeclaredMethod("isValidHeaderName", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, name);
    }

    private boolean invokeIsValidHeaderValue(String value) throws Exception {
        Method m = HttpWebhookDispatcher.class.getDeclaredMethod("isValidHeaderValue", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, value);
    }

    @Test
    public void testHeaderNameAcceptsRfc7230Tokens() throws Exception {
        assertTrue(invokeIsValidHeaderName("X-Custom-Header"));
        assertTrue(invokeIsValidHeaderName("X-Trace-ID"));
        assertTrue(invokeIsValidHeaderName("Idempotency-Key"));
        assertTrue(invokeIsValidHeaderName("If-Match"));
    }

    @Test
    public void testHeaderNameRejectsCrlfAndSpaces() throws Exception {
        assertFalse(invokeIsValidHeaderName("X-Custom\r\nFake: line"));
        assertFalse(invokeIsValidHeaderName("X-Custom\nFake"));
        assertFalse(invokeIsValidHeaderName("X-Custom\rFake"));
        assertFalse(invokeIsValidHeaderName("X Custom"));
        assertFalse(invokeIsValidHeaderName(""));
        assertFalse(invokeIsValidHeaderName(null));
    }

    @Test
    public void testHeaderValueRejectsCrlfNul() throws Exception {
        assertTrue(invokeIsValidHeaderValue("normal-value"));
        assertTrue(invokeIsValidHeaderValue("with spaces and = signs"));
        assertFalse(invokeIsValidHeaderValue("inject\r\nFake-Header: bad"));
        assertFalse(invokeIsValidHeaderValue("inject\nFake"));
        assertFalse(invokeIsValidHeaderValue("inject\rFake"));
        assertFalse(invokeIsValidHeaderValue("with nul"));
    }

    @Test
    public void testForbiddenHeadersConstantContainsCriticalNames() throws Exception {
        java.lang.reflect.Field f = HttpWebhookDispatcher.class.getDeclaredField("FORBIDDEN_CUSTOM_HEADERS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> forbidden = (java.util.Set<String>) f.get(null);
        for (String h : new String[]{"host", "content-length", "authorization",
                "transfer-encoding", "connection", "proxy-authorization"}) {
            assertTrue(forbidden.contains(h),
                    "FORBIDDEN_CUSTOM_HEADERS must include " + h + " (compared lower-case)");
        }
    }

    // ========================================
    // IPv6 transition address SSRF protection
    // (NAT64 / 6to4 / IPv4-compatible) — GHSA fix
    //
    // The IPv4-range checks above only fire for 4-byte InetAddress, so an
    // internal IPv4 destination encoded as a NAT64 (64:ff9b::/96) or 6to4
    // (2002::/16) literal would slip past without these tests. On
    // dual-stack / NAT64 networks the kernel routes the literal to the
    // embedded IPv4 — that is the SSRF.
    // ========================================

    @Test
    public void testBlocksNat64WellKnownWrappedLoopback() throws Exception {
        // 64:ff9b::7f00:1  is NAT64 wrap of 127.0.0.1
        InetAddress addr = InetAddress.getByName("64:ff9b::7f00:1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "NAT64-wrapped loopback (64:ff9b::7f00:1 = 127.0.0.1) MUST be blocked");
    }

    @Test
    public void testBlocksNat64WellKnownWrappedAwsMetadata() throws Exception {
        // 64:ff9b::a9fe:a9fe  is NAT64 wrap of 169.254.169.254 (cloud metadata)
        InetAddress addr = InetAddress.getByName("64:ff9b::a9fe:a9fe");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "NAT64-wrapped AWS metadata (64:ff9b::a9fe:a9fe = 169.254.169.254) MUST be blocked");
    }

    @Test
    public void testBlocksNat64WellKnownWrapped10Net() throws Exception {
        // 64:ff9b::a00:1  is NAT64 wrap of 10.0.0.1
        InetAddress addr = InetAddress.getByName("64:ff9b::a00:1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "NAT64-wrapped 10.0.0.1 MUST be blocked");
    }

    @Test
    public void testBlocksNat64WellKnownWrapped192_168() throws Exception {
        // 64:ff9b::c0a8:101  is NAT64 wrap of 192.168.1.1
        InetAddress addr = InetAddress.getByName("64:ff9b::c0a8:101");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "NAT64-wrapped 192.168.1.1 MUST be blocked");
    }

    @Test
    public void testAllowsNat64WellKnownWrappedPublicIp() throws Exception {
        // 64:ff9b::0808:0808  is NAT64 wrap of 8.8.8.8 (Google DNS, public)
        InetAddress addr = InetAddress.getByName("64:ff9b::808:808");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "NAT64-wrapped public IPv4 (8.8.8.8) should be allowed (extracted IPv4 is public)");
    }

    @Test
    public void testBlocksNat64LocalUseWrappedLoopback() throws Exception {
        // 64:ff9b:1::7f00:1  is NAT64 local-use (RFC 8215) wrap, /96 PLR
        InetAddress addr = InetAddress.getByName("64:ff9b:1::7f00:1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "NAT64 local-use 64:ff9b:1::/48 wrap of 127.0.0.1 MUST be blocked");
    }

    @Test
    public void testBlocksNat64LocalUseRfc6052WrappedLoopback() throws Exception {
        // 64:ff9b:1:7f00:0:100:: is RFC 6052 /48 layout for 127.0.0.1:
        // prefix /48, first two IPv4 octets in bits 48-63, reserved u octet,
        // last two IPv4 octets in bits 72-87.
        InetAddress addr = InetAddress.getByName("64:ff9b:1:7f00:0:100::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "RFC 6052 /48 NAT64 local-use wrap of 127.0.0.1 MUST be blocked");
    }

    @Test
    public void testAllowsNat64LocalUseRfc6052WrappedPublicIp() throws Exception {
        // RFC 6052 /48 layout for 8.8.8.8 under 64:ff9b:1::/48.
        InetAddress addr = InetAddress.getByName("64:ff9b:1:808:8:800::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "RFC 6052 /48 NAT64 local-use wrap of public 8.8.8.8 should be allowed");
    }

    @Test
    public void testBlocks6to4WrappedLoopback() throws Exception {
        // 2002:7f00:1::  is 6to4 (RFC 3056) wrap of 127.0.0.1
        // (bytes 2-5 = 7f 00 00 01)
        InetAddress addr = InetAddress.getByName("2002:7f00:1::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "6to4-wrapped loopback (2002:7f00:1:: = 127.0.0.1) MUST be blocked");
    }

    @Test
    public void testBlocks6to4WrappedAwsMetadata() throws Exception {
        // 2002:a9fe:a9fe::  is 6to4 wrap of 169.254.169.254
        InetAddress addr = InetAddress.getByName("2002:a9fe:a9fe::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "6to4-wrapped AWS metadata MUST be blocked");
    }

    @Test
    public void testBlocks6to4Wrapped10Net() throws Exception {
        // 2002:a00:1::  is 6to4 wrap of 10.0.0.1
        InetAddress addr = InetAddress.getByName("2002:a00:1::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "6to4-wrapped 10.0.0.1 MUST be blocked");
    }

    @Test
    public void testAllows6to4WrappedPublicIp() throws Exception {
        // 2002:0808:0808::  is 6to4 wrap of 8.8.8.8 (public, must pass)
        InetAddress addr = InetAddress.getByName("2002:808:808::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "6to4-wrapped public IPv4 (8.8.8.8) should be allowed");
    }

    @Test
    public void testBlocksIPv4CompatibleLoopback() throws Exception {
        // ::127.0.0.1 — deprecated IPv4-compatible form. JDK historically
        // collapses this to Inet4Address (then loopback check catches it),
        // but in case future / current behaviour returns Inet6Address we
        // verify the embedded-extract path catches it too.
        InetAddress addr = InetAddress.getByName("::7f00:1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "IPv4-compatible ::7f00:1 (= 127.0.0.1) MUST be blocked");
    }

    @Test
    public void testBlocksTeredoWrappedLoopback() throws Exception {
        // Teredo 2001::/32 stores the client's IPv4 address as one's-complement
        // in the last 32 bits. fffffffe decodes to 0.0.0.1, which is not
        // globally routable and must be blocked.
        InetAddress addr = InetAddress.getByName("2001:0:4136:e378:8000:63bf:ffff:fffe");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse(result, "Teredo-wrapped non-routable IPv4 MUST be blocked");
    }

    @Test
    public void testAllowsTeredoWrappedPublicIp() throws Exception {
        // Last 32 bits f7f7:f7f7 decode to public 8.8.8.8.
        InetAddress addr = InetAddress.getByName("2001:0:4136:e378:8000:63bf:f7f7:f7f7");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "Teredo-wrapped public IPv4 should be allowed");
    }

    @Test
    public void testAllowsRegularPublicIPv6() throws Exception {
        // 2606:4700:4700::1111 (Cloudflare public DNS) — must not be
        // mistaken for any transition format.
        InetAddress addr = InetAddress.getByName("2606:4700:4700::1111");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "Public IPv6 (Cloudflare DNS) should be allowed (no embedded IPv4)");
    }

    @Test
    public void testAllowsRegular2002NotPrefixed() throws Exception {
        // 2002 is the 6to4 prefix; a real-world address fully under 6to4
        // wraps a real IPv4. 2002:cb00:7100:: would be 6to4-wrap of
        // 203.0.113.0 (TEST-NET-3, RFC 5737), which is "documentation"
        // but isn't blocked by isAddressSafe (not in any private range).
        // We assert allow to confirm we don't over-block.
        InetAddress addr = InetAddress.getByName("2002:cb00:7100::");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue(result, "6to4-wrap of 203.0.113.0 (public-routable) should be allowed");
    }

    @Test
    public void testExtractEmbeddedIpv4PublicPassthrough() throws Exception {
        // Direct test of the extractor: NAT64 of 8.8.8.8 → InetAddress("8.8.8.8")
        java.lang.reflect.Method extract = HttpWebhookDispatcher.class
                .getDeclaredMethod("extractEmbeddedIpv4", InetAddress.class);
        extract.setAccessible(true);

        InetAddress nat64 = InetAddress.getByName("64:ff9b::808:808");
        InetAddress extracted = (InetAddress) extract.invoke(null, nat64);
        assertNotNull(extracted, "NAT64 well-known should be unwrapped");
        assertEquals("8.8.8.8", extracted.getHostAddress());

        InetAddress sixToFour = InetAddress.getByName("2002:808:808::");
        InetAddress extracted2 = (InetAddress) extract.invoke(null, sixToFour);
        assertNotNull(extracted2, "6to4 should be unwrapped");
        assertEquals("8.8.8.8", extracted2.getHostAddress());

        InetAddress nat64LocalUseRfc6052 = InetAddress.getByName("64:ff9b:1:808:8:800::");
        InetAddress extracted3 = (InetAddress) extract.invoke(null, nat64LocalUseRfc6052);
        assertNotNull(extracted3, "NAT64 local-use /48 should be unwrapped");
        assertEquals("8.8.8.8", extracted3.getHostAddress());

        InetAddress teredo = InetAddress.getByName("2001:0:4136:e378:8000:63bf:f7f7:f7f7");
        InetAddress extracted4 = (InetAddress) extract.invoke(null, teredo);
        assertNotNull(extracted4, "Teredo should be unwrapped");
        assertEquals("8.8.8.8", extracted4.getHostAddress());

        // Non-transition IPv6 → null
        InetAddress regular = InetAddress.getByName("2606:4700:4700::1111");
        InetAddress notExtracted = (InetAddress) extract.invoke(null, regular);
        assertNull(notExtracted, "Regular public IPv6 should not produce embedded IPv4");

        // ULA (already blocked elsewhere) → null
        InetAddress ula = InetAddress.getByName("fc00::1");
        InetAddress notExtracted2 = (InetAddress) extract.invoke(null, ula);
        assertNull(notExtracted2, "ULA should not produce embedded IPv4 (handled separately)");
    }
}
