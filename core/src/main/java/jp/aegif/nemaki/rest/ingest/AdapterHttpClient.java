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
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

    /**
     * Enable the JDK {@link HttpRequest.Builder} to set the {@code Host}
     * header explicitly. RC6.9 P3 fix: {@link #pinRequestToValidatedAddress}
     * needs to override {@code Host} to the original hostname when
     * rewriting the HTTP URI to the validated IP literal, so shared-vhost
     * deployments (one reverse proxy serving multiple hostnames on the
     * same IP) reach the correct vhost.
     *
     * <p>The JDK's {@code jdk.internal.net.http.common.Utils} reads
     * {@code jdk.httpclient.allowRestrictedHeaders} once at class load
     * time. By setting the property in this static initializer BEFORE
     * the {@link #SHARED} field below triggers HttpClient class loading,
     * the property is honoured for the JVM lifetime of this WAR.
     *
     * <p>The setter is additive — any existing value (e.g. an operator
     * passing other restricted headers via {@code -D...=connection,host})
     * is preserved. JVM-wide effect: other code in the same JVM that
     * uses {@code HttpRequest.Builder.header("Host", ...)} will now
     * succeed where it previously threw {@code IllegalArgumentException};
     * this is intentional and matches the documented JDK escape hatch.
     */
    static {
        String key = "jdk.httpclient.allowRestrictedHeaders";
        String existing = System.getProperty(key, "");
        java.util.Set<String> values = new java.util.TreeSet<>();
        for (String v : existing.split(",")) {
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) values.add(t);
        }
        if (values.add("host")) {
            System.setProperty(key, String.join(",", values));
        }
    }

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
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
     *
     * <p>Each hop resolves the {@code Location} header against the
     * <em>current</em> request URI (not the original), so multi-hop relative
     * redirects (e.g. hop 1 jumps to a different host, hop 2 returns
     * {@code Location: /file}) resolve correctly against that intermediate
     * host. This is also a correctness fix: prior to RC6.8, all relative
     * Location values resolved against the original URI, which could
     * silently misroute a multi-hop chain.
     *
     * <p>Each hop also goes through {@link #pinRequestToValidatedAddress}
     * before send, so DNS rebinding between the {@code validateExternalUrl}
     * call above and the actual {@code NO_REDIRECT.send} call cannot
     * succeed.
     */
    public static <T> HttpResponse<T> sendWithRedirectValidation(
            HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, int maxRedirects)
            throws java.io.IOException, InterruptedException {
        HttpRequest currentRequest = request;
        HttpResponse<T> response = NO_REDIRECT.send(pinRequestToValidatedAddress(currentRequest), bodyHandler);
        for (int i = 0; i < maxRedirects; i++) {
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null) return response;
            // Resolve relative Location against the CURRENT request URI,
            // not the original. RC6.8 P3 fix.
            URI redirectUri = currentRequest.uri().resolve(location);
            // Validate redirect target (config-time semantics; pinRequestToValidatedAddress
            // below will re-validate at send time as belt-and-suspenders).
            validateExternalUrl(redirectUri.toString());
            // Build new request WITHOUT auth headers (dropped on all redirects for safety)
            currentRequest = HttpRequest.newBuilder()
                    .uri(redirectUri)
                    .timeout(currentRequest.timeout().orElse(Duration.ofSeconds(60)))
                    .GET()
                    .build();
            response = NO_REDIRECT.send(pinRequestToValidatedAddress(currentRequest), bodyHandler);
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
            // where public + private IPs are mixed in the same A/AAAA record set.
            if (!isLocalhostAllowed()) {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    if (!isAddressSafe(addr)) {
                        throw new SecurityException("URL must not target private/loopback/special-use addresses");
                    }
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("Invalid URL: " + e.getMessage());
        }
    }

    private static boolean isAddressSafe(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            int third = b[2] & 0xFF;
            int fourth = b[3] & 0xFF;

            if (first == 10) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 169 && second == 254) return false;
            if (first == 0) return false;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 192 && second == 0 && third == 0) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            if (first >= 240) return false;
            if (first == 255 && second == 255 && third == 255 && fourth == 255) return false;
        } else if (b.length == 16) {
            int first = b[0] & 0xFF;
            if (first == 0xFC || first == 0xFD) {
                return false;
            }
            InetAddress embedded = extractEmbeddedIpv4(address);
            return embedded == null || isAddressSafe(embedded);
        }
        return true;
    }

    private static InetAddress extractEmbeddedIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return null;
        }
        byte[] embedded = null;

        boolean mappedPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) { mappedPrefix = false; break; }
        }
        if (mappedPrefix && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            embedded = new byte[]{b[12], b[13], b[14], b[15]};
        }

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
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        if (embedded == null && (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            embedded = new byte[]{b[2], b[3], b[4], b[5]};
        }

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
            return null;
        }
    }

    /**
     * Re-resolve the request URI at send time, validate every resolved
     * address against {@link #isAddressSafe}, and for HTTP rewrite the
     * URI to use the validated IP literal while preserving the original
     * {@code Host} header.
     *
     * <p>This closes a DNS rebinding gap: {@link #validateExternalUrl(String)}
     * called at config time (e.g. when an admin saves a connector endpoint)
     * resolves the host once and validates the result; an attacker
     * controlling the DNS for that host could return a public IP at the
     * config-time resolve and a private / loopback / metadata IP at the
     * actual {@code HttpClient.send} time.
     *
     * <p><strong>HTTP — DNS rebinding closed at the network layer</strong>:
     * the returned request has its URI rewritten to use the validated IP
     * literal (in {@code [...]} brackets for IPv6) AND its {@code Host}
     * header set to the original {@code hostname[:port]}. The IP-pin
     * means no TCP connection to a rebound IP is possible after this
     * method returns; the preserved {@code Host} header means name-based
     * virtual-host deployments (one reverse proxy serving multiple
     * hostnames on the same IP) reach the correct vhost. The {@code Host}
     * override requires the {@code jdk.httpclient.allowRestrictedHeaders=host}
     * JVM property which is set by the static initializer at the top of
     * this class.
     *
     * <p><strong>HTTPS — TLS-bounded, NOT fully closed</strong>: the
     * original request is returned unchanged. The send-time re-validation
     * above catches rebound IPs <em>if</em> the rebound resolve happens
     * before the JDK's own resolve inside {@code HttpClient.send}. <strong>A
     * microsecond race window remains</strong>: a DNS attacker rebinding
     * within that window can still cause the JDK to TCP-connect to the
     * internal IP. The TLS handshake then fails against the original
     * hostname's cert, so:
     * <ul>
     *   <li><strong>Data-exchange SSRF is closed</strong> (no body read,
     *       no token leak, no internal API call succeeds).</li>
     *   <li><strong>TCP-connect SSRF is NOT closed</strong>: an attacker
     *       can still port-scan internal hosts, time-fingerprint internal
     *       services, and trigger inbound-TCP / TLS-handshake side
     *       effects on internal services.</li>
     * </ul>
     * Fully closing the HTTPS path requires a custom {@code SocketFactory}
     * (or a switch to {@code HttpURLConnection} like
     * {@code HttpWebhookDispatcher} uses for HTTP) that pins the
     * resolved IP at TCP-connect time while keeping SNI / hostname
     * verification on the original hostname. Tracked as Medium residual
     * risk in {@code REVIEW_PACKET.md §6}.
     *
     * <p>If the host cannot be resolved at all, a {@link SecurityException}
     * is thrown — this is a behaviour change from "let HttpClient try and
     * fail with a network error" to "fail fast with a security-flavoured
     * error", which is the right side of the trade-off for outbound
     * connector requests.
     */
    static HttpRequest pinRequestToValidatedAddress(HttpRequest request) {
        URI uri = request.uri();
        if (uri == null) {
            return request;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return request;
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return request;
        }
        if (isLocalhostAllowed()) {
            // Test mode (WireMock): bypass pinning + validation. The
            // -Dnemaki.ingest.allowLocalhost=true property is documented
            // as test-only; never set in production.
            return request;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SecurityException("Cannot resolve host at send time: " + host);
        }
        if (addrs.length == 0) {
            throw new SecurityException("Host resolved to no addresses at send time: " + host);
        }
        for (InetAddress addr : addrs) {
            if (!isAddressSafe(addr)) {
                throw new SecurityException(
                        "URL must not target private/loopback/special-use addresses "
                        + "(DNS rebinding check at send time): "
                        + host + " -> " + addr.getHostAddress());
            }
        }
        if (scheme.equalsIgnoreCase("https")) {
            // TLS certificate verification handles rebinding for HTTPS.
            return request;
        }
        // HTTP: pin URI to the first validated address.
        InetAddress picked = addrs[0];
        String hostLiteral = picked.getHostAddress();
        if (picked.getAddress().length == 16) {
            hostLiteral = "[" + hostLiteral + "]";
        }
        int port = uri.getPort();
        String authority = port == -1 ? hostLiteral : hostLiteral + ":" + port;
        String pathQuery = (uri.getRawPath() == null ? "" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        URI pinnedUri;
        try {
            pinnedUri = new URI(scheme + "://" + authority + pathQuery);
        } catch (Exception e) {
            // Unrebuildable URI (e.g. exotic chars). Fall back to original;
            // the re-validation above still closes the rebinding window
            // for any meaningful exploit.
            return request;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(pinnedUri);
        // Copy headers from original except restricted ones JDK forbids
        // (we set Host explicitly below using the JDK escape hatch
        // enabled by the static initializer at the top of this class).
        request.headers().map().forEach((name, vals) -> {
            if (!isRestrictedHeaderForJdkHttpClient(name)) {
                vals.forEach(v -> b.header(name, v));
            }
        });
        // RC6.9 P3 fix: preserve original Host header so name-based
        // virtual-host servers route to the correct vhost. Without
        // this, the JDK would default Host to the pinned IP literal
        // and shared-vhost reverse proxies would misroute / 404.
        String hostHeader = port == -1 ? host : host + ":" + port;
        b.header("Host", hostHeader);
        request.timeout().ifPresent(b::timeout);
        b.method(request.method(),
                request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        return b.build();
    }

    /**
     * Headers the JDK {@link HttpRequest.Builder} rejects by default
     * (without {@code -Djdk.httpclient.allowRestrictedHeaders=...}).
     * We strip these when copying headers from the original request
     * into a pinned-IP rebuild — the JDK will set safe defaults
     * (Host, Connection, Content-Length, etc.) on the new request.
     */
    private static boolean isRestrictedHeaderForJdkHttpClient(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "connection": case "content-length": case "expect":
            case "host": case "http2-settings": case "keep-alive":
            case "origin": case "upgrade": case "via":
                return true;
            default:
                return false;
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
        // RC6.8 P1: re-validate and IP-pin at send time to defeat DNS
        // rebinding between an earlier validateExternalUrl call (config-time
        // or constructor-time) and the actual HttpClient.send below.
        // pinRequestToValidatedAddress throws SecurityException if the host
        // now resolves to a blocked address. For HTTP it rewrites the URI
        // to use the validated IP literal; for HTTPS it returns the original
        // request (TLS cert verification handles rebinding against the
        // declared hostname).
        HttpRequest sendable = pinRequestToValidatedAddress(request);
        HttpResponse<T> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            response = client.send(sendable, bodyHandler);
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
