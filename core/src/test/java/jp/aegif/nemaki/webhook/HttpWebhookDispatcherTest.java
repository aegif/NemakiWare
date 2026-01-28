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

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;

import org.junit.Before;
import org.junit.Test;

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
    private Method isUrlSafeMethod;
    private Method isAddressSafeMethod;
    
    @Before
    public void setUp() throws Exception {
        dispatcher = new HttpWebhookDispatcher();
        
        // Use reflection to access private methods for testing
        isUrlSafeMethod = HttpWebhookDispatcher.class.getDeclaredMethod("isUrlSafe", URL.class);
        isUrlSafeMethod.setAccessible(true);
        
        isAddressSafeMethod = HttpWebhookDispatcher.class.getDeclaredMethod("isAddressSafe", InetAddress.class, String.class);
        isAddressSafeMethod.setAccessible(true);
    }
    
    // ========================================
    // Blocked Hostname Tests
    // ========================================
    
    @Test
    public void testBlocksLocalhost() throws Exception {
        URL url = new URL("http://localhost/webhook");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("localhost should be blocked", result);
    }
    
    @Test
    public void testBlocksLocalhostUppercase() throws Exception {
        URL url = new URL("http://LOCALHOST/webhook");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("LOCALHOST (uppercase) should be blocked", result);
    }
    
    @Test
    public void testBlocks127001() throws Exception {
        URL url = new URL("http://127.0.0.1/webhook");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("127.0.0.1 should be blocked", result);
    }
    
    @Test
    public void testBlocks0000() throws Exception {
        URL url = new URL("http://0.0.0.0/webhook");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("0.0.0.0 should be blocked", result);
    }
    
    @Test
    public void testBlocksIPv6Loopback() throws Exception {
        // Note: URL with IPv6 requires brackets
        URL url = new URL("http://[::1]/webhook");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("::1 (IPv6 loopback) should be blocked", result);
    }
    
    // ========================================
    // Cloud Metadata Endpoint Tests
    // ========================================
    
    @Test
    public void testBlocksAwsMetadataEndpoint() throws Exception {
        URL url = new URL("http://169.254.169.254/latest/meta-data/");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("AWS metadata endpoint should be blocked", result);
    }
    
    @Test
    public void testBlocksGcpMetadataInternal() throws Exception {
        URL url = new URL("http://metadata.google.internal/computeMetadata/v1/");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("GCP metadata.google.internal should be blocked", result);
    }
    
    @Test
    public void testBlocksGcpMetadataCom() throws Exception {
        URL url = new URL("http://metadata.google.com/computeMetadata/v1/");
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("metadata.google.com should be blocked", result);
    }
    
    // ========================================
    // Private Network Range Tests (isAddressSafe)
    // ========================================
    
    @Test
    public void testBlocks10Network() throws Exception {
        InetAddress addr = InetAddress.getByName("10.0.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("10.x.x.x private network should be blocked", result);
    }
    
    @Test
    public void testBlocks10NetworkMax() throws Exception {
        InetAddress addr = InetAddress.getByName("10.255.255.255");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("10.255.255.255 should be blocked", result);
    }
    
    @Test
    public void testBlocks172_16Network() throws Exception {
        InetAddress addr = InetAddress.getByName("172.16.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("172.16.x.x private network should be blocked", result);
    }
    
    @Test
    public void testBlocks172_31Network() throws Exception {
        InetAddress addr = InetAddress.getByName("172.31.255.255");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("172.31.x.x private network should be blocked", result);
    }
    
    @Test
    public void testAllows172_15Network() throws Exception {
        // 172.15.x.x is NOT in the private range (172.16-31)
        InetAddress addr = InetAddress.getByName("172.15.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue("172.15.x.x should be allowed (not in private range)", result);
    }
    
    @Test
    public void testAllows172_32Network() throws Exception {
        // 172.32.x.x is NOT in the private range (172.16-31)
        InetAddress addr = InetAddress.getByName("172.32.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue("172.32.x.x should be allowed (not in private range)", result);
    }
    
    @Test
    public void testBlocks192_168Network() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("192.168.x.x private network should be blocked", result);
    }
    
    @Test
    public void testBlocks169_254Network() throws Exception {
        InetAddress addr = InetAddress.getByName("169.254.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("169.254.x.x link-local should be blocked", result);
    }
    
    // ========================================
    // IPv6 ULA Tests (fc00::/7)
    // ========================================
    
    @Test
    public void testBlocksIPv6ULA_FC00() throws Exception {
        InetAddress addr = InetAddress.getByName("fc00::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("fc00::1 (IPv6 ULA) should be blocked", result);
    }
    
    @Test
    public void testBlocksIPv6ULA_FD00() throws Exception {
        InetAddress addr = InetAddress.getByName("fd00::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("fd00::1 (IPv6 ULA) should be blocked", result);
    }
    
    @Test
    public void testBlocksIPv6ULA_FDXX() throws Exception {
        InetAddress addr = InetAddress.getByName("fd12:3456:789a::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("fd12:3456:789a::1 (IPv6 ULA) should be blocked", result);
    }
    
    // ========================================
    // Loopback Address Tests
    // ========================================
    
    @Test
    public void testBlocksLoopbackAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("127.0.0.1 loopback should be blocked", result);
    }
    
    @Test
    public void testBlocksLoopbackAddressRange() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.2");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("127.0.0.2 loopback should be blocked", result);
    }
    
    @Test
    public void testBlocksIPv6LoopbackAddress() throws Exception {
        InetAddress addr = InetAddress.getByName("::1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertFalse("::1 IPv6 loopback should be blocked", result);
    }
    
    // ========================================
    // Safe URL Tests
    // ========================================
    
    @Test
    public void testAllowsPublicIP() throws Exception {
        InetAddress addr = InetAddress.getByName("8.8.8.8");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue("8.8.8.8 (Google DNS) should be allowed", result);
    }
    
    @Test
    public void testAllowsPublicIP2() throws Exception {
        InetAddress addr = InetAddress.getByName("1.1.1.1");
        boolean result = (boolean) isAddressSafeMethod.invoke(dispatcher, addr, "test-host");
        assertTrue("1.1.1.1 (Cloudflare DNS) should be allowed", result);
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
        boolean result = (boolean) isUrlSafeMethod.invoke(dispatcher, url);
        assertFalse("URL with empty host should be blocked", result);
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
}
