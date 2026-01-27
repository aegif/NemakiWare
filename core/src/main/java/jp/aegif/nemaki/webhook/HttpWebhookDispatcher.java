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
    
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;
    
    @Override
    public void dispatch(String url, String payload, Map<String, String> headers, WebhookConfig config) {
        if (url == null || url.isEmpty()) {
            log.warn("Webhook dispatch skipped: URL is null or empty");
            return;
        }
        
        if (payload == null) {
            log.warn("Webhook dispatch skipped: payload is null");
            return;
        }
        
        HttpURLConnection connection = null;
        try {
            URL targetUrl = new URL(url);
            
            // Validate URL protocol (only HTTP/HTTPS allowed)
            String protocol = targetUrl.getProtocol().toLowerCase();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                log.warn("Webhook dispatch skipped: unsupported protocol " + protocol);
                return;
            }
            
            // SSRF protection: validate hostname is not blocked
            if (!isUrlSafe(targetUrl)) {
                log.warn("Webhook dispatch skipped: URL blocked for security reasons - " + url);
                return;
            }
            
            connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            
            // Set default headers
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "NemakiWare-Webhook/1.0");
            
            // Set custom headers from webhook config
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            
            // Write payload
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(payloadBytes.length));
            
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payloadBytes);
                os.flush();
            }
            
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
     * Check if URL is safe to access (SSRF protection).
     * Blocks requests to localhost, private networks, and cloud metadata endpoints.
     */
    private boolean isUrlSafe(URL url) {
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        
        String hostLower = host.toLowerCase();
        
        // Check against blocked hostnames
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            log.debug("SSRF protection: blocked hostname " + host);
            return false;
        }
        
        // Resolve hostname and check if it's a private/loopback address
        try {
            InetAddress address = InetAddress.getByName(host);
            
            if (address.isLoopbackAddress()) {
                log.debug("SSRF protection: blocked loopback address " + host);
                return false;
            }
            
            if (address.isLinkLocalAddress()) {
                log.debug("SSRF protection: blocked link-local address " + host);
                return false;
            }
            
            if (address.isSiteLocalAddress()) {
                log.debug("SSRF protection: blocked site-local (private) address " + host);
                return false;
            }
            
            // Check for IPv4 private ranges that might not be caught by isSiteLocalAddress
            byte[] addrBytes = address.getAddress();
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
            }
            
        } catch (UnknownHostException e) {
            // If we can't resolve the hostname, allow it (will fail at connection time)
            log.debug("SSRF protection: could not resolve hostname " + host + ", allowing");
        }
        
        return true;
    }
    
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
    
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
}
