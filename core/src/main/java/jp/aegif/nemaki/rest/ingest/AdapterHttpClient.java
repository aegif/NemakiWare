/*****************************************************************************
 Copyright (c) 2026 aegif.

 This file is part of NemakiWare.

 NemakiWare is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 *****************************************************************************/
package jp.aegif.nemaki.rest.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared {@link HttpClient} for ingest connector adapters.
 *
 * <p>The scheduler creates a fresh adapter instance on every fetch cycle
 * (e.g. {@code new SlackConnectorAdapter(token)} in
 * {@code IngestSchedulerService}).  If each adapter constructor allocated
 * its own {@link HttpClient}, every cycle would leak the client's
 * internal selector / I/O threads until the next GC.  Under sustained
 * scheduling this manifested as connection-pool exhaustion and rising
 * thread counts.</p>
 *
 * <p>Sharing a single static {@link HttpClient} across all adapters is
 * safe: {@link HttpClient} is documented as thread-safe and pools
 * connections per origin.  The 10s connect timeout matches the previous
 * per-adapter setting, and HTTP/2 is enabled by default.</p>
 *
 * <p>Tests that need a custom client (e.g. WireMock) should keep using
 * the constructor that accepts an {@link HttpClient}; only adapters that
 * previously instantiated their own client should switch to
 * {@link #shared()}.</p>
 */
public final class AdapterHttpClient {

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** HttpClient that does NOT follow redirects — for SSRF-safe file downloads. */
    private static final HttpClient NO_REDIRECT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Returns a no-redirect HttpClient for safe file downloads. */
    public static HttpClient noRedirectClient() { return NO_REDIRECT; }

    /**
     * Send an HTTP request following redirects manually, validating each
     * redirect target against {@link #validateExternalUrl} to prevent SSRF
     * via open redirects.
     */
    public static <T> HttpResponse<T> sendWithRedirectValidation(
            HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, int maxRedirects)
            throws java.io.IOException, InterruptedException {
        HttpResponse<T> response = NO_REDIRECT.send(request, bodyHandler);
        for (int i = 0; i < maxRedirects; i++) {
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) return response;
            // Validate redirect target
            validateExternalUrl(location);
            // Build new request WITHOUT auth headers (dropped on all redirects for safety)
            HttpRequest redirect = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(location))
                    .timeout(request.timeout().orElse(Duration.ofSeconds(60)))
                    .GET()
                    .build();
            response = NO_REDIRECT.send(redirect, bodyHandler);
        }
        return response;
    }

    private static final Logger log = LoggerFactory.getLogger(AdapterHttpClient.class);
    private static final int MAX_RETRIES = 3;
    // Note: request-level timeouts must be set by callers via HttpRequest.Builder.timeout().
    // There is no way to inject a default timeout into an already-built HttpRequest.

    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private AdapterHttpClient() { /* utility */ }

    /**
     * Encode a value for use as a URI path segment (RFC 3986).
     *
     * <p>Unlike {@link java.net.URLEncoder} (which is for query/form encoding
     * and converts spaces to {@code +}), this method produces percent-encoding
     * suitable for path segments where {@code +} is a literal plus.
     */
    public static String encodePathSegment(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /**
    /**
     * Validate that a URL is safe for server-side requests (SSRF prevention).
     * Rejects private IPs, loopback, link-local, and non-http(s) schemes.
     *
     * @throws SecurityException if the URL targets an unsafe destination
     */
    /**
     * System property to allow localhost URLs in SSRF validation.
     * Set {@code -Dnemaki.ingest.allowLocalhost=true} for WireMock tests.
     * Never enable in production.
     */
    private static final String ALLOW_LOCALHOST_PROP = "nemaki.ingest.allowLocalhost";

    /** Check if localhost URLs are allowed (read from System property). */
    static boolean isLocalhostAllowed() {
        return "true".equalsIgnoreCase(System.getProperty(ALLOW_LOCALHOST_PROP, "false"));
    }

    public static void validateExternalUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new SecurityException("URL is required");
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new SecurityException("Only http/https URLs are allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new SecurityException("URL must have a valid host");
            }
            // Check ALL resolved addresses to prevent DNS rebinding attacks
            // where public + private IPs are mixed in the same A/AAAA record set
            if (!isLocalhostAllowed()) {
                for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                            || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                        throw new SecurityException("URL must not target private/loopback addresses");
                    }
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("Invalid URL: " + e.getMessage());
        }
    }

    /**
     * Check HTTP response status and close the InputStream body on error.
     * Prevents connection pool exhaustion from undrained error responses.
     *
     * @throws RuntimeException with descriptive message if status != 200
     */
    public static InputStream requireOkOrClose(HttpResponse<InputStream> response, String context) throws java.io.IOException {
        if (response.statusCode() == 200) {
            return response.body();
        }
        // Close the error body to release the pooled connection
        try { response.body().close(); } catch (Exception ignored) {}
        throw new RuntimeException(context + " HTTP " + response.statusCode());
    }

    /**
     * Decode a URI path segment (RFC 3986).
     *
     * <p>Unlike {@link java.net.URLDecoder} which treats {@code +} as space
     * (form/query convention), this method preserves {@code +} as a literal
     * plus, only decoding {@code %XX} sequences.
     */
    public static String decodePathSegment(String value) {
        if (value == null) return null;
        // Protect literal '+' from URLDecoder by temporarily escaping them
        return java.net.URLDecoder.decode(
                value.replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Truncate a response body for safe inclusion in error messages.
     * Prevents large payloads or credentials from leaking into logs.
     */
    public static String truncateBody(String body) {
        if (body == null) return "(empty)";
        return body.length() <= MAX_ERROR_BODY_LENGTH ? body
                : body.substring(0, MAX_ERROR_BODY_LENGTH) + "... (truncated)";
    }

    /** Returns the shared HttpClient instance. */
    public static HttpClient shared() {
        return SHARED;
    }

    /**
     * Execute an HTTP request with automatic retry on 429 (Too Many Requests)
     * and 503 (Service Unavailable). Respects the {@code Retry-After} header
     * from the response (in seconds). Falls back to exponential backoff if the
     * header is absent.
     *
     * @param request   the request to send (should include a timeout via
     *                  {@link HttpRequest.Builder#timeout(Duration)} or the
     *                  default 60s will apply)
     * @param bodyHandler response body handler
     * @return the HTTP response
     * @throws IOException on unrecoverable network failure
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public static <T> HttpResponse<T> sendWithRetry(HttpRequest request,
                                                     HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return sendWithRetry(SHARED, request, bodyHandler);
    }

    /** Overload accepting a custom HttpClient (for tests). */
    public static <T> HttpResponse<T> sendWithRetry(HttpClient client,
                                                     HttpRequest request,
                                                     HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        HttpResponse<T> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            response = client.send(request, bodyHandler);
            int status = response.statusCode();
            if (status != 429 && status != 503) {
                return response;
            }
            if (attempt == MAX_RETRIES) {
                log.warn("HTTP {} after {} retries for {}", status, MAX_RETRIES, request.uri());
                return response; // Return the 429/503 response to the caller
            }
            // Drain/close the response body before retry to release pooled connections.
            // For InputStream bodies this prevents connection pinning.
            T body = response.body();
            if (body instanceof java.io.InputStream is) {
                try { is.close(); } catch (Exception ignored) {}
            }
            // Parse Retry-After header (seconds)
            long waitSeconds = (long) Math.pow(2, attempt + 1); // default exponential backoff: 2, 4, 8s
            var headers = response.headers();
            String retryAfter = headers.firstValue("Retry-After")
                    .orElseGet(() -> headers.firstValue("retry-after").orElse(null));
            if (retryAfter != null) {
                try { waitSeconds = Long.parseLong(retryAfter); }
                catch (NumberFormatException ignored) { /* keep default backoff */ }
            }
            waitSeconds = Math.min(waitSeconds, 120); // Cap at 2 minutes
            log.info("HTTP {} from {}, retrying in {}s (attempt {}/{})",
                    status, request.uri(), waitSeconds, attempt + 1, MAX_RETRIES);
            Thread.sleep(waitSeconds * 1000);
        }
        return response;
    }
}
