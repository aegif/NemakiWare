package jp.aegif.nemaki.rest.purview;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpPurviewEntityRegistryClient implements PurviewEntityRegistryClient {

    private static final String TOKEN_SCOPE = "https://purview.azure.net/.default";
    private static final String TOKEN_URL_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String ENTITY_BULK_PATH = "entity/bulk";
    private static final String ENTITY_UNIQUE_ATTRIBUTE_PATH = "entity/uniqueAttribute/type";
    private static final String RELATIONSHIP_PATH = "relationship";
    private static final String RELATIONSHIP_GUID_PATH = "relationship/guid";
    private static final String DATAMAP_API_VERSION = "2023-09-01";
    private static final int MAX_BODY_EXCERPT_LENGTH = 200;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PurviewEntityPublishResult bulkCreateOrUpdateEntities(PurviewConnectionRequest request, Map<String, Object> payload)
            throws PurviewClientException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMs()))
                .build();

        String accessToken = fetchAccessToken(httpClient, request);
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new PurviewClientException("Failed to serialize Purview entity payload", e);
        }

        HttpRequest entityRequest = HttpRequest.newBuilder(buildEntityBulkUri(request))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = send(httpClient, entityRequest);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return PurviewEntityPublishResult.success(countEntities(payload), "entities published");
        }

        return PurviewEntityPublishResult.failure(
                "Purview entity publish returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    @Override
    public PurviewEntityPublishResult deleteByUniqueAttribute(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) throws PurviewClientException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMs()))
                .build();
        String accessToken = fetchAccessToken(httpClient, request);

        HttpRequest deleteRequest = HttpRequest.newBuilder(
                buildEntityUniqueAttributeUri(request, typeName, attributeName, attributeValue))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .DELETE()
                .build();

        HttpResponse<String> response = send(httpClient, deleteRequest);
        if (response.statusCode() == 404) {
            return PurviewEntityPublishResult.success(0, "entity already absent");
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return PurviewEntityPublishResult.success(1, "entity deleted");
        }

        return PurviewEntityPublishResult.failure(
                "Purview entity delete returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    @Override
    public PurviewEntityPublishResult createRelationship(
            PurviewConnectionRequest request,
            Map<String, Object> payload) throws PurviewClientException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMs()))
                .build();

        String accessToken = fetchAccessToken(httpClient, request);
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new PurviewClientException("Failed to serialize Purview relationship payload", e);
        }

        HttpRequest relationshipRequest = HttpRequest.newBuilder(buildRelationshipUri(request))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = send(httpClient, relationshipRequest);
        if (response.statusCode() == 409) {
            return PurviewEntityPublishResult.success(1, "relationship already exists", extractRelationshipGuid(response.body()));
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return PurviewEntityPublishResult.success(1, "relationship created", extractRelationshipGuid(response.body()));
        }

        return PurviewEntityPublishResult.failure(
                "Purview relationship create returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    @Override
    public PurviewEntityPublishResult deleteRelationshipByGuid(
            PurviewConnectionRequest request,
            String relationshipGuid) throws PurviewClientException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(request.getConnectTimeoutMs()))
                .build();
        String accessToken = fetchAccessToken(httpClient, request);

        HttpRequest deleteRequest = HttpRequest.newBuilder(buildRelationshipGuidUri(request, relationshipGuid))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .DELETE()
                .build();

        HttpResponse<String> response = send(httpClient, deleteRequest);
        if (response.statusCode() == 404) {
            return PurviewEntityPublishResult.success(0, "relationship already absent", relationshipGuid);
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return PurviewEntityPublishResult.success(1, "relationship deleted", relationshipGuid);
        }

        return PurviewEntityPublishResult.failure(
                "Purview relationship delete returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    private int countEntities(Map<String, Object> payload) {
        Object entities = payload.get("entities");
        if (entities instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private String fetchAccessToken(HttpClient httpClient, PurviewConnectionRequest request) throws PurviewClientException {
        String formBody = "client_id=" + urlEncode(request.getClientId())
                + "&client_secret=" + urlEncode(request.getClientSecret())
                + "&scope=" + urlEncode(TOKEN_SCOPE)
                + "&grant_type=client_credentials";

        HttpRequest tokenRequest = HttpRequest.newBuilder(buildTokenUri(request))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> response = send(httpClient, tokenRequest);
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
            return accessToken.asText();
        } catch (IOException e) {
            throw new PurviewClientException("Failed to parse Purview token response", e);
        }
    }

    private HttpResponse<String> send(HttpClient httpClient, HttpRequest request) throws PurviewClientException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PurviewClientException("Purview request was interrupted", e);
        } catch (IOException e) {
            throw new PurviewClientException("Purview request failed: " + e.getMessage(), e);
        }
    }

    private URI buildTokenUri(PurviewConnectionRequest request) {
        return URI.create(String.format(TOKEN_URL_TEMPLATE, urlEncodePathSegment(request.getTenantId())));
    }

    private URI buildEntityBulkUri(PurviewConnectionRequest request) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + ENTITY_BULK_PATH;
        if (trimSlashes(request.getAtlasBasePath()).startsWith("datamap/")) {
            uri = uri + "?api-version=" + DATAMAP_API_VERSION;
        }
        return URI.create(uri);
    }

    private URI buildEntityUniqueAttributeUri(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + ENTITY_UNIQUE_ATTRIBUTE_PATH + "/"
                + urlEncodePathSegment(typeName)
                + "?attr:" + attributeName + "=" + urlEncode(attributeValue);
        if (trimSlashes(request.getAtlasBasePath()).startsWith("datamap/")) {
            uri = uri + "&api-version=" + DATAMAP_API_VERSION;
        }
        return URI.create(uri);
    }

    private URI buildRelationshipUri(PurviewConnectionRequest request) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + RELATIONSHIP_PATH;
        if (trimSlashes(request.getAtlasBasePath()).startsWith("datamap/")) {
            uri = uri + "?api-version=" + DATAMAP_API_VERSION;
        }
        return URI.create(uri);
    }

    private URI buildRelationshipGuidUri(PurviewConnectionRequest request, String relationshipGuid) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + RELATIONSHIP_GUID_PATH + "/"
                + urlEncodePathSegment(relationshipGuid);
        if (trimSlashes(request.getAtlasBasePath()).startsWith("datamap/")) {
            uri = uri + "?api-version=" + DATAMAP_API_VERSION;
        }
        return URI.create(uri);
    }

    private String extractRelationshipGuid(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode directGuid = json.get("guid");
            if (directGuid != null && !directGuid.asText().isBlank()) {
                return directGuid.asText();
            }
            JsonNode relationship = json.get("relationship");
            if (relationship != null) {
                JsonNode nestedGuid = relationship.get("guid");
                if (nestedGuid != null && !nestedGuid.asText().isBlank()) {
                    return nestedGuid.asText();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
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
