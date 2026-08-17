package jp.aegif.nemaki.rest.purview.journal;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Lightweight HTTP client wrapper using java.net.http.HttpClient.
 * Provides JSON request/response handling for Atlas and Dataplex APIs.
 * No external dependencies beyond JDK 21.
 *
 * <p>Instances created via {@link #withBasicAuth}, {@link #withBearerToken},
 * or {@link #withHeader} share the underlying {@link HttpClient} but have
 * independent authentication and header state (immutable copy pattern).
 * Only the root instance (created via the public constructor) owns the
 * underlying {@code HttpClient} and is responsible for closing it.
 */
public class SimpleHttpClient implements AutoCloseable {

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final String basicAuthHeader;
    private final String bearerToken;
    private final Map<String, String> customHeaders;
    private final boolean ownsClient;

    public SimpleHttpClient(Duration timeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        this.objectMapper = ObjectMapperFactory.createDefaultObjectMapper();
        this.timeout = timeout;
        this.basicAuthHeader = null;
        this.bearerToken = null;
        this.customHeaders = Map.of();
        this.ownsClient = true;
    }

    /** Copy constructor — shares the underlying HttpClient. */
    private SimpleHttpClient(HttpClient client, ObjectMapper objectMapper, Duration timeout,
                              String basicAuthHeader, String bearerToken,
                              Map<String, String> customHeaders) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.basicAuthHeader = basicAuthHeader;
        this.bearerToken = bearerToken;
        this.customHeaders = Map.copyOf(customHeaders);
        this.ownsClient = false;
    }

    /** Returns a new instance with Basic auth set (does not mutate this). */
    public SimpleHttpClient withBasicAuth(String username, String password) {
        String header = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
        return new SimpleHttpClient(client, objectMapper, timeout, header, null, customHeaders);
    }

    /** Returns a new instance with Bearer token set (does not mutate this). */
    public SimpleHttpClient withBearerToken(String token) {
        return new SimpleHttpClient(client, objectMapper, timeout, null, token, customHeaders);
    }

    /** Returns a new instance with an additional custom header (does not mutate this). */
    public SimpleHttpClient withHeader(String name, String value) {
        Map<String, String> newHeaders = new LinkedHashMap<>(customHeaders);
        newHeaders.put(name, value);
        return new SimpleHttpClient(client, objectMapper, timeout, basicAuthHeader, bearerToken, newHeaders);
    }

    public HttpResponse<String> postJson(String url, Object body) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        addAuthHeader(builder);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> getJson(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();

        addAuthHeader(builder);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseResponse(HttpResponse<String> response) throws Exception {
        return objectMapper.readValue(response.body(), Map.class);
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    private void addAuthHeader(HttpRequest.Builder builder) {
        if (basicAuthHeader != null) {
            builder.header("Authorization", basicAuthHeader);
        } else if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }
}
