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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
                    log.debug("Response body: " + truncateForLogging(responseBody));
                }
            } else {
                String truncatedBody = truncateForLogging(responseBody);
                log.warn("Webhook delivery failed to " + url + " (HTTP " + responseCode + ")" + 
                        (truncatedBody != null && !truncatedBody.isEmpty() ? " - Response: " + truncatedBody : ""));
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
     * Read response body from connection.
     * Uses error stream for non-2xx responses, input stream for success.
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                    // Stop reading if we've exceeded max length
                    if (response.length() > MAX_RESPONSE_BODY_LENGTH) {
                        break;
                    }
                }
            }
            return response.toString();
        } catch (IOException e) {
            log.debug("Failed to read response body: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Truncate response body for logging to avoid excessive log output.
     */
    private String truncateForLogging(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_RESPONSE_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_RESPONSE_BODY_LENGTH) + "...(truncated)";
    }
    
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
    
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }
}
