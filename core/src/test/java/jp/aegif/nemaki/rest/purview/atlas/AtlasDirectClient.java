package jp.aegif.nemaki.rest.purview.atlas;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin HTTP helper for direct Atlas REST API calls using Basic Auth.
 * This is test-only code; production code uses the Purview OAuth2 clients.
 */
public class AtlasDirectClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final String endpoint;
    private final String authHeader;

    public AtlasDirectClient(String endpoint, String authHeader) {
        this.httpClient = HttpClient.newBuilder().build();
        this.endpoint = endpoint;
        this.authHeader = authHeader;
    }

    /**
     * Probes Atlas connectivity.
     * @return HTTP status code for GET /api/atlas/v2/types/typedefs/headers
     */
    public int probeConnection() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/atlas/v2/types/typedefs/headers"))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /**
     * Applies a type definitions payload (entityDefs, relationshipDefs, etc.).
     * @return response as parsed Map
     */
    public AtlasResponse applySchema(Map<String, Object> payload) throws IOException, InterruptedException {
        return post("/api/atlas/v2/types/typedefs", payload);
    }

    /**
     * Bulk creates or updates entities.
     * @return response as parsed Map
     */
    public AtlasResponse bulkCreateEntities(Map<String, Object> payload) throws IOException, InterruptedException {
        return post("/api/atlas/v2/entity/bulk", payload);
    }

    /**
     * Reads one type definition by name (増分 B).
     *
     * <p>Used to establish that a type the schema payload declares actually exists in the
     * catalog afterwards. A schema apply that reports success can still have skipped a type,
     * and everything downstream would then fail as "entity not found" rather than as
     * "the type was never created".
     */
    public AtlasResponse getTypeDef(String typeName) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/atlas/v2/types/typedef/name/" + typeName))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new AtlasResponse(response.statusCode(), parseBody(response.body()));
    }

    /**
     * Gets an entity by qualifiedName.
     * @return response with status code and parsed body (null body if 404)
     */
    public AtlasResponse getEntityByQualifiedName(String typeName, String qualifiedName)
            throws IOException, InterruptedException {
        String encodedQn = URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
        String path = "/api/atlas/v2/entity/uniqueAttribute/type/" + typeName
                + "?attr:qualifiedName=" + encodedQn;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new AtlasResponse(response.statusCode(), parseBody(response.body()));
    }

    /**
     * Deletes an entity by qualifiedName.
     * @return response with status code
     */
    public AtlasResponse deleteEntityByQualifiedName(String typeName, String qualifiedName)
            throws IOException, InterruptedException {
        String encodedQn = URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
        String path = "/api/atlas/v2/entity/uniqueAttribute/type/" + typeName
                + "?attr:qualifiedName=" + encodedQn;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new AtlasResponse(response.statusCode(), parseBody(response.body()));
    }

    /**
     * Creates a relationship.
     * @return response with status code and parsed body
     */
    public AtlasResponse createRelationship(Map<String, Object> payload) throws IOException, InterruptedException {
        return post("/api/atlas/v2/relationship", payload);
    }

    /**
     * Deletes a relationship by GUID.
     */
    public AtlasResponse deleteRelationshipByGuid(String guid) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/atlas/v2/relationship/guid/" + guid))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new AtlasResponse(response.statusCode(), parseBody(response.body()));
    }

    private AtlasResponse post(String path, Map<String, Object> payload) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new AtlasResponse(response.statusCode(), parseBody(response.body()));
    }

    private Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(body, MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of("_rawBody", body);
        }
    }

    /**
     * Simple response wrapper holding status code and parsed JSON body.
     */
    public record AtlasResponse(int statusCode, Map<String, Object> body) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
