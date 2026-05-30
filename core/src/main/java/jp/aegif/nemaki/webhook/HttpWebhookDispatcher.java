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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.impl.WebhookServiceImpl.WebhookDispatcher;

/**
 * HTTP implementation of WebhookDispatcher.
 * Sends webhook payloads to configured URLs via HTTP POST.
 * 
 * Security notes:
 * - SSRF protection blocks localhost, private networks, and cloud metadata endpoints
 * - DNS resolution is performed once at validation time and the resolved IP is reused
 *   for the actual connection, preventing DNS rebinding attacks.
 */
public class HttpWebhookDispatcher implements WebhookDispatcher {
    
    private static final Log log = LogFactory.getLog(HttpWebhookDispatcher.class);
    
    private static final int DEFAULT_CONNECT_TIMEOUT = 10000; // 10 seconds
    private static final int DEFAULT_READ_TIMEOUT = 30000; // 30 seconds
    private static final int MAX_RESPONSE_BODY_LENGTH = 1000; // Truncate response body for logging
    
    /**
     * Blocked hostnames for SSRF protection.
     * Includes localhost variants and cloud metadata endpoints.
     */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "[::1]",
        "169.254.169.254",  // AWS/GCP metadata endpoint
        "metadata.google.internal",  // GCP metadata
        "metadata.google.com"
    );

    /**
     * Header names that admins must NOT be able to override via the
     * webhook configuration's custom-headers map. Allowing any of these
     * would let an attacker (or compromised admin token) smuggle a
     * second request, point Host at a different vhost behind the
     * resolved IP (defeating the SSRF mitigation), exfiltrate stored
     * credentials by setting Authorization to a different value, or
     * confuse upstream proxies via Connection / Proxy-* manipulation.
     * Compared case-insensitively.
     */
    private static final Set<String> FORBIDDEN_CUSTOM_HEADERS = Set.of(
        "host",
        "content-length",
        "content-type",
        "transfer-encoding",
        "connection",
        "upgrade",
        "expect",
        "te",
        "trailer",
        "proxy-connection",
        "proxy-authorization",
        "proxy-authenticate",
        "authorization"
    );

    /**
     * RFC 7230 token characters for header field-name validation.
     * Reject anything else (including CR/LF/space/control chars).
     */
    private static boolean isValidHeaderName(String name) {
        if (name == null || name.isEmpty() || name.length() > 256) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // RFC 7230: token = 1*tchar
            // tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
            //         "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Reject CR/LF/NUL anywhere in the header value (header smuggling). */
    private static boolean isValidHeaderValue(String value) {
        if (value == null || value.length() > 8192) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return false;
            }
        }
        return true;
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() > 64 ? s.substring(0, 64) + "..." : s;
    }
    
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;
    
    @Override
    public void dispatch(String url, String payload, Map<String, String> headers, WebhookConfig config) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, payload, headers);

            // Get response
            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                log.info("Webhook delivered successfully to " + url + " (HTTP " + responseCode + ")");
                if (log.isDebugEnabled() && responseBody != null && !responseBody.isEmpty()) {
                    log.debug("Response body: " + responseBody);
                }
            } else {
                log.warn("Webhook delivery failed to " + url + " (HTTP " + responseCode + ")" + 
                        (responseBody != null && !responseBody.isEmpty() ? " - Response: " + responseBody : ""));
            }

        } catch (IllegalArgumentException e) {
            log.warn("Webhook dispatch skipped: " + e.getMessage());
        } catch (MalformedURLException e) {
            log.error("Webhook dispatch failed: malformed URL " + url, e);
        } catch (IOException e) {
            log.error("Webhook dispatch failed: I/O error for " + url + " - " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    

    /**
     * Build an HTTP POST connection, validate the URL (SSRF protection), set headers,
     * and write the payload. Returns the opened connection ready for reading the response.
     *
     * <p>SSRF protection strategy differs by protocol:
     * <ul>
     *   <li><b>HTTP</b>: Connect via the pre-resolved IP address to prevent DNS rebinding attacks.
     *       The Host header is set to the original hostname for virtual hosting.</li>
     *   <li><b>HTTPS</b>: Validate the resolved IP (block private/internal addresses), then connect
     *       using the original hostname URL. TLS certificate verification inherently prevents DNS
     *       rebinding because the attacker would need a valid certificate for the target hostname.
     *       This avoids Java's internal hostname-vs-certificate mismatch when the URL uses an IP.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if url/payload is invalid or blocked by SSRF protection
     * @throws MalformedURLException if the URL is malformed
     * @throws IOException if an I/O error occurs during connection or payload write
     */
    private HttpURLConnection openConnection(String url, String payload, Map<String, String> headers)
            throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL is null or empty");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }

        URL targetUrl = new URL(url);

        // Validate URL protocol (only HTTP/HTTPS allowed)
        String protocol = targetUrl.getProtocol().toLowerCase();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("unsupported protocol " + protocol);
        }

        // SSRF protection: resolve and validate hostname (blocks private/internal IPs)
        InetAddress resolvedAddress = resolveAndValidateUrl(targetUrl);
        if (resolvedAddress == null) {
            throw new IllegalArgumentException("URL blocked for security reasons (SSRF protection) - " + url);
        }

        HttpURLConnection connection;

        if (protocol.equals("https")) {
            // HTTPS: Connect using the original hostname URL.
            // TLS certificate validation prevents DNS rebinding (attacker cannot present
            // a valid cert for the target hostname on an internal server).
            connection = (HttpURLConnection) targetUrl.openConnection();
        } else {
            // HTTP: Connect via resolved IP to prevent DNS rebinding attacks.
            int port = targetUrl.getPort() != -1 ? targetUrl.getPort() : targetUrl.getDefaultPort();
            URL resolvedUrl = new URL(protocol, resolvedAddress.getHostAddress(), port,
                    targetUrl.getFile());
            connection = (HttpURLConnection) resolvedUrl.openConnection();

            // Set Host header to original hostname (required for virtual hosting)
            connection.setRequestProperty("Host", targetUrl.getHost() +
                    (targetUrl.getPort() != -1 ? ":" + targetUrl.getPort() : ""));
        }

        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);

        // SSRF protection: Disable automatic redirect following
        connection.setInstanceFollowRedirects(false);

        // Set default headers
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "NemakiWare-Webhook/1.0");

        // Set custom headers — validated to prevent CRLF injection / header
        // smuggling and to forbid overriding security-critical headers that
        // the dispatcher itself controls (Host already pinned to the
        // resolved-IP URL above, Authorization could exfiltrate stored
        // tokens to an unintended recipient, etc.).
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                String value = header.getValue();
                if (name == null || value == null) {
                    continue;
                }
                if (!isValidHeaderName(name)) {
                    log.warn("Webhook delivery: rejecting custom header with invalid name (CRLF / non-token chars): " + abbreviate(name));
                    continue;
                }
                if (!isValidHeaderValue(value)) {
                    log.warn("Webhook delivery: rejecting custom header '" + name + "' — value contains CR/LF/NUL (header smuggling attempt?)");
                    continue;
                }
                if (FORBIDDEN_CUSTOM_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    log.warn("Webhook delivery: ignoring attempt to override security-critical header via custom-headers: " + name);
                    continue;
                }
                connection.setRequestProperty(name, value);
            }
        }

        // Write payload
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(payloadBytes.length));

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payloadBytes);
            os.flush();
        }

        return connection;
    }

    /**
     * Read response body from connection with truncation.
     * Uses error stream for non-2xx responses, input stream for success.
     * Truncates to MAX_RESPONSE_BODY_LENGTH and appends marker if truncated.
     */
    private String readResponseBody(HttpURLConnection connection, int responseCode) {
        InputStream inputStream = null;
        try {
            if (responseCode >= 200 && responseCode < 300) {
                inputStream = connection.getInputStream();
            } else {
                inputStream = connection.getErrorStream();
            }
            
            if (inputStream == null) {
                return null;
            }
            
            StringBuilder response = new StringBuilder();
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                    // Stop reading if we've exceeded max length
                    if (response.length() > MAX_RESPONSE_BODY_LENGTH) {
                        truncated = true;
                        break;
                    }
                }
            }
            
            // Truncate and add marker if needed
            if (truncated || response.length() > MAX_RESPONSE_BODY_LENGTH) {
                return response.substring(0, Math.min(response.length(), MAX_RESPONSE_BODY_LENGTH)) 
                       + "...(truncated)";
            }
            return response.toString();
        } catch (IOException e) {
            log.debug("Failed to read response body: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Resolve and validate URL for SSRF protection.
     * Returns the first safe resolved InetAddress, or null if the URL is blocked.
     * 
     * The returned address should be used for the actual connection to prevent
     * DNS rebinding attacks (where DNS resolves to a different IP between validation
     * and connection time).
     */
    InetAddress resolveAndValidateUrl(URL url) {
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        
        String hostLower = host.toLowerCase();
        
        // Check against blocked hostnames
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            log.debug("SSRF protection: blocked hostname " + host);
            return null;
        }
        
        // Resolve hostname and check ALL resolved IP addresses
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            
            for (InetAddress address : addresses) {
                if (!isAddressSafe(address, host)) {
                    return null;
                }
            }
            
            // Return the first address for connection use
            return addresses.length > 0 ? addresses[0] : null;
            
        } catch (UnknownHostException e) {
            log.warn("SSRF protection: could not resolve hostname " + host + ", blocking for security");
            return null;
        }
    }
    
    /**
     * Check if a single IP address is safe to access.
     * Blocks loopback, link-local, site-local, any-local, multicast,
     * and IPv6 ULA (fc00::/7) addresses.
     */
    private boolean isAddressSafe(InetAddress address, String host) {
        if (address.isLoopbackAddress()) {
            log.debug("SSRF protection: blocked loopback address " + host + " -> " + address.getHostAddress());
            return false;
        }
        
        if (address.isLinkLocalAddress()) {
            log.debug("SSRF protection: blocked link-local address " + host + " -> " + address.getHostAddress());
            return false;
        }
        
        if (address.isSiteLocalAddress()) {
            log.debug("SSRF protection: blocked site-local (private) address " + host + " -> " + address.getHostAddress());
            return false;
        }
        
        if (address.isAnyLocalAddress()) {
            log.debug("SSRF protection: blocked any-local address " + host + " -> " + address.getHostAddress());
            return false;
        }
        
        if (address.isMulticastAddress()) {
            log.debug("SSRF protection: blocked multicast address " + host + " -> " + address.getHostAddress());
            return false;
        }
        
        byte[] addrBytes = address.getAddress();
        
        // Check for IPv4 private ranges that might not be caught by isSiteLocalAddress
        if (addrBytes.length == 4) {
            int firstOctet = addrBytes[0] & 0xFF;
            int secondOctet = addrBytes[1] & 0xFF;
            
            // 10.0.0.0/8
            if (firstOctet == 10) {
                log.debug("SSRF protection: blocked 10.x.x.x private address " + host);
                return false;
            }
            
            // 172.16.0.0/12
            if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) {
                log.debug("SSRF protection: blocked 172.16-31.x.x private address " + host);
                return false;
            }
            
            // 192.168.0.0/16
            if (firstOctet == 192 && secondOctet == 168) {
                log.debug("SSRF protection: blocked 192.168.x.x private address " + host);
                return false;
            }
            
            // 169.254.0.0/16 (link-local, includes AWS metadata)
            if (firstOctet == 169 && secondOctet == 254) {
                log.debug("SSRF protection: blocked 169.254.x.x link-local address " + host);
                return false;
            }

            // 0.0.0.0/8 ("this" network) and limited broadcast
            if (firstOctet == 0 || (firstOctet == 255 && secondOctet == 255
                    && (addrBytes[2] & 0xFF) == 255 && (addrBytes[3] & 0xFF) == 255)) {
                log.debug("SSRF protection: blocked non-routable IPv4 address " + host);
                return false;
            }

            // 100.64.0.0/10 (carrier-grade NAT / shared address space)
            if (firstOctet == 100 && secondOctet >= 64 && secondOctet <= 127) {
                log.debug("SSRF protection: blocked CGNAT/shared IPv4 address " + host);
                return false;
            }

            // 192.0.0.0/24 (IETF protocol assignments, includes 192.0.0.8/32)
            if (firstOctet == 192 && secondOctet == 0 && (addrBytes[2] & 0xFF) == 0) {
                log.debug("SSRF protection: blocked IETF protocol-assignment IPv4 address " + host);
                return false;
            }

            // 198.18.0.0/15 (benchmarking/interconnect test networks)
            if (firstOctet == 198 && (secondOctet == 18 || secondOctet == 19)) {
                log.debug("SSRF protection: blocked benchmarking IPv4 address " + host);
                return false;
            }

            // 240.0.0.0/4 (reserved for future use)
            if (firstOctet >= 240) {
                log.debug("SSRF protection: blocked reserved IPv4 address " + host);
                return false;
            }
        }
        
        // Check for IPv6 ULA (Unique Local Address) fc00::/7
        // This covers fc00::/8 and fd00::/8
        if (addrBytes.length == 16) {
            int firstByte = addrBytes[0] & 0xFF;
            // fc00::/7 means first 7 bits are 1111110, so first byte is 0xFC or 0xFD
            if (firstByte == 0xFC || firstByte == 0xFD) {
                log.debug("SSRF protection: blocked IPv6 ULA address " + host + " -> " + address.getHostAddress());
                return false;
            }

            // IPv6 transition addresses: unwrap any embedded IPv4 and
            // re-classify. Without this an attacker can encode an internal
            // IPv4 destination (loopback, RFC 1918, link-local incl.
            // 169.254.169.254 metadata) as a NAT64 (64:ff9b::/96 +
            // 64:ff9b:1::/48), 6to4 (2002::/16), Teredo
            // (2001::/32), or IPv4-compatible (::a.b.c.d) literal — the
            // IPv4-range checks above only fire for 4-byte InetAddress, and
            // InetAddress.is{Loopback,LinkLocal,SiteLocal,...} do NOT
            // classify those transition formats as local (the prefixes are
            // globally routable in the JDK's view).
            // Dual-stack / NAT64 networks route the literal to the embedded
            // IPv4, reaching the internal target.
            //
            // Reported via GitHub security advisory (RC6.5 fix). PoC:
            //   64:ff9b::7f00:1     -> 127.0.0.1 (loopback)
            //   64:ff9b::a9fe:a9fe  -> 169.254.169.254 (cloud metadata)
            //   2002:7f00:1::       -> 127.0.0.1 (6to4-wrapped loopback)
            InetAddress embedded = extractEmbeddedIpv4(address);
            if (embedded != null && !isAddressSafe(embedded, host)) {
                log.warn("SSRF protection: blocked IPv6 transition address "
                        + host + " -> " + address.getHostAddress()
                        + " (embeds blocked IPv4 " + embedded.getHostAddress() + ")");
                return false;
            }
        }

        return true;
    }

    /**
     * If {@code address} is an IPv6 transition format that embeds an IPv4
     * address, extract the embedded IPv4 as an {@link Inet4Address}. Returns
     * {@code null} if the address is not a recognized transition format.
     *
     * <p>Recognized formats:
     * <ul>
     *   <li>{@code ::ffff:0:0/96} — IPv4-mapped IPv6 (RFC 4291 §2.5.5.2).
     *       Bytes 12-15. The JDK usually returns these as {@code Inet4Address}
     *       already, so this branch is defensive.</li>
     *   <li>{@code ::a.b.c.d} (IPv4-compatible, deprecated by RFC 4291
     *       §2.5.5.1 but still parseable). Bytes 12-15.</li>
     *   <li>{@code 64:ff9b::/96} — NAT64 well-known prefix (RFC 6052 §2.1).
     *       Bytes 12-15.</li>
     *   <li>{@code 64:ff9b:1::/48} — NAT64 local-use prefix (RFC 8215).
     *       Supports the RFC 6052 /48 layout and the common /96-style PLR
     *       layout seen in operational examples.</li>
     *   <li>{@code 2002::/16} — 6to4 (RFC 3056 §2). Bytes 2-5.</li>
     *   <li>{@code 2001::/32} — Teredo (RFC 4380 §4). Bytes 12-15 contain
     *       the obfuscated client IPv4 address.</li>
     * </ul>
     */
    private static InetAddress extractEmbeddedIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return null;
        }
        byte[] embedded = null;

        // IPv4-mapped ::ffff:0:0/96  (bytes 0-9 = 0, bytes 10-11 = 0xFF)
        boolean mappedPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) { mappedPrefix = false; break; }
        }
        if (mappedPrefix && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            embedded = new byte[]{b[12], b[13], b[14], b[15]};
        }

        // IPv4-compatible ::a.b.c.d  (bytes 0-11 = 0). Skip ::0 (any-local)
        // and ::1 (loopback) — those are caught by the predicate checks
        // above and don't carry an "embedded" IPv4 in the SSRF sense.
        if (embedded == null) {
            boolean compatPrefix = true;
            for (int i = 0; i < 12; i++) {
                if (b[i] != 0) { compatPrefix = false; break; }
            }
            boolean trivialLow = b[12] == 0 && b[13] == 0 && b[14] == 0
                    && (b[15] == 0 || b[15] == 1);
            if (compatPrefix && !trivialLow) {
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // NAT64 64:ff9b::/96  (bytes 0-3 = 00:64:FF:9B, bytes 4-11 = 0)
        if (embedded == null
                && (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            boolean nat64WellKnown = true;
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) { nat64WellKnown = false; break; }
            }
            if (nat64WellKnown) {
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // NAT64 local-use 64:ff9b:1::/48  (RFC 8215).
        // RFC 6052 /48 layout:
        //   prefix bytes 0-5, IPv4[0..1] in bytes 6-7, byte 8 is the
        //   reserved "u" octet, IPv4[2..3] in bytes 9-10.
        if (embedded == null
                && (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && (b[4] & 0xFF) == 0x00 && (b[5] & 0xFF) == 0x01) {
            boolean rfc6052SuffixClear = true;
            for (int i = 11; i < 16; i++) {
                if (b[i] != 0) { rfc6052SuffixClear = false; break; }
            }
            if (rfc6052SuffixClear) {
                embedded = new byte[]{b[6], b[7], b[9], b[10]};
            } else {
                // Also support a conservative /96-style PLR under the local-use
                // prefix. Re-classification below decides whether it is blocked.
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // 6to4 2002::/16  (bytes 0-1 = 20:02, bytes 2-5 = embedded IPv4)
        if (embedded == null && (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            embedded = new byte[]{b[2], b[3], b[4], b[5]};
        }

        // Teredo 2001::/32. Bytes 12-15 are the one's-complement of the
        // client IPv4 address.
        if (embedded == null
                && (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
                && b[2] == 0 && b[3] == 0) {
            embedded = new byte[]{
                    (byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]
            };
        }

        if (embedded == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(embedded);
        } catch (UnknownHostException e) {
            // Unreachable — getByAddress(byte[4]) always returns an Inet4Address.
            return null;
        }
    }
    
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
    
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
    
    /**
     * Synchronous dispatch for testing webhooks.
     * Returns the delivery result including actual status code and response body.
     */
    @Override
    public WebhookDeliveryLog dispatchSync(String url, String payload, Map<String, String> headers, WebhookConfig config) {
        WebhookDeliveryLog result = new WebhookDeliveryLog();
        result.setDeliveryId(java.util.UUID.randomUUID().toString());
        result.setWebhookUrl(url);
        result.setEventType("TEST");
        result.setAttemptNumber(1);
        result.setTimestamp(new java.util.GregorianCalendar());

        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, payload, headers);

            // Get response
            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection, responseCode);

            result.setStatusCode(responseCode);
            result.setResponseBody(responseBody);
            result.setSuccess(responseCode >= 200 && responseCode < 300);

            if (result.isSuccess()) {
                log.info("Test webhook delivered successfully to " + url + " (HTTP " + responseCode + ")");
            } else {
                log.warn("Test webhook delivery failed to " + url + " (HTTP " + responseCode + ")");
            }

        } catch (IllegalArgumentException e) {
            result.setSuccess(false);
            result.setStatusCode(0);
            result.setResponseBody("Error: " + e.getMessage());
        } catch (MalformedURLException e) {
            result.setSuccess(false);
            result.setStatusCode(0);
            result.setResponseBody("Error: malformed URL - " + e.getMessage());
        } catch (IOException e) {
            result.setSuccess(false);
            result.setStatusCode(0);
            result.setResponseBody("Error: I/O error - " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return result;
    }
}
