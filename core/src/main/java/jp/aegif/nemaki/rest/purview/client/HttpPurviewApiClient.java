package jp.aegif.nemaki.rest.purview.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpPurviewApiClient implements PurviewApiClient {

    private static final String TOKEN_SCOPE = "https://purview.azure.net/.default";
    private static final String TOKEN_URL_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String TYPEDEF_HEADERS_PATH = "types/typedefs/headers";
    private static final int MAX_BODY_EXCERPT_LENGTH = 200;

    private final HttpClient httpClient;
    private final PurviewTokenCache tokenCache;
    private final PurviewHttpRetryHandler retryHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpPurviewApiClient() {
        this(HttpClient.newBuilder().build(), new PurviewTokenCache(), new PurviewHttpRetryHandler());
    }

    public HttpPurviewApiClient(HttpClient httpClient) {
        this(httpClient, new PurviewTokenCache(), new PurviewHttpRetryHandler());
    }

    public HttpPurviewApiClient(HttpClient httpClient, PurviewTokenCache tokenCache) {
        this(httpClient, tokenCache, new PurviewHttpRetryHandler());
    }

    public HttpPurviewApiClient(HttpClient httpClient, PurviewTokenCache tokenCache, PurviewHttpRetryHandler retryHandler) {
        this.httpClient = httpClient;
        this.tokenCache = tokenCache;
        this.retryHandler = retryHandler;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public PurviewTokenCache getTokenCache() {
        return tokenCache;
    }

    @Override
    public PurviewProbeResult probeConnection(PurviewConnectionRequest request) throws PurviewClientException {
        HttpRequest probeRequest = HttpRequest.newBuilder(buildProbeUri(request))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = send(probeRequest);
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return PurviewProbeResult.success(statusCode, "Purview connection succeeded");
        }

        return PurviewProbeResult.failure(
                statusCode,
                "Purview probe returned HTTP " + statusCode + formatBodyExcerpt(response.body()));
    }

    String buildAuthorizationHeader(PurviewConnectionRequest request) throws PurviewClientException {
        if (request.isBasicAuth()) {
            String credentials = request.getBasicUsername() + ":" + request.getBasicPassword();
            return "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + fetchAccessToken(request);
    }

    private String fetchAccessToken(PurviewConnectionRequest request) throws PurviewClientException {
        String cached = tokenCache.get(request.getTenantId(), request.getClientId());
        if (cached != null) {
            return cached;
        }

        String formBody = "client_id=" + urlEncode(request.getClientId())
                + "&client_secret=" + urlEncode(request.getClientSecret())
                + "&scope=" + urlEncode(TOKEN_SCOPE)
                + "&grant_type=client_credentials";

        HttpRequest tokenRequest = HttpRequest.newBuilder(buildTokenUri(request))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = send(tokenRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PurviewClientException(
                    "Failed to acquire Purview access token: HTTP " + response.statusCode()
                            + formatBodyExcerpt(response.body()));
        }

        try {
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode accessToken = json.get("access_token");
            if (accessToken == null || accessToken.asText().isBlank()) {
                throw new PurviewClientException("Purview token response did not include access_token");
            }
            long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong() : 3600L;
            String token = accessToken.asText();
            tokenCache.put(request.getTenantId(), request.getClientId(), token, expiresIn);
            return token;
        } catch (IOException e) {
            throw new PurviewClientException("Failed to parse Purview token response", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws PurviewClientException {
        return retryHandler.sendWithRetry(
                () -> this.httpClient.send(request, HttpResponse.BodyHandlers.ofString()),
                tokenCache,
                () -> tokenCache.invalidateAll());
    }

    private URI buildTokenUri(PurviewConnectionRequest request) {
        return URI.create(String.format(TOKEN_URL_TEMPLATE, urlEncodePathSegment(request.getTenantId())));
    }

    private URI buildProbeUri(PurviewConnectionRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(trimTrailingSlash(request.getEndpoint()));
        builder.append("/");
        builder.append(trimSlashes(request.getAtlasBasePath()));
        builder.append("/");
        builder.append(TYPEDEF_HEADERS_PATH);
        return URI.create(builder.toString());
    }

    private String formatBodyExcerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_BODY_EXCERPT_LENGTH) {
            return ": " + normalized;
        }
        return ": " + normalized.substring(0, MAX_BODY_EXCERPT_LENGTH) + "...";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String urlEncodePathSegment(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
