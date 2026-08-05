package jp.aegif.nemaki.rest.purview.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpPurviewEntityRegistryClient implements PurviewEntityRegistryClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpPurviewEntityRegistryClient.class);

    private static final String TOKEN_SCOPE = "https://purview.azure.net/.default";
    private static final String TOKEN_URL_TEMPLATE = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String ENTITY_BULK_PATH = "entity/bulk";
    private static final String ENTITY_UNIQUE_ATTRIBUTE_PATH = "entity/uniqueAttribute/type";
    private static final String RELATIONSHIP_PATH = "relationship";
    private static final String RELATIONSHIP_GUID_PATH = "relationship/guid";
    private static final int MAX_BODY_EXCERPT_LENGTH = 200;

    private final HttpClient httpClient;
    private final PurviewTokenCache tokenCache;
    private final PurviewHttpRetryHandler retryHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpPurviewEntityRegistryClient() {
        this(HttpClient.newBuilder().build(), new PurviewTokenCache(), new PurviewHttpRetryHandler());
    }

    public HttpPurviewEntityRegistryClient(HttpClient httpClient) {
        this(httpClient, new PurviewTokenCache(), new PurviewHttpRetryHandler());
    }

    public HttpPurviewEntityRegistryClient(HttpClient httpClient, PurviewTokenCache tokenCache) {
        this(httpClient, tokenCache, new PurviewHttpRetryHandler());
    }

    public HttpPurviewEntityRegistryClient(HttpClient httpClient, PurviewTokenCache tokenCache, PurviewHttpRetryHandler retryHandler) {
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
    public PurviewEntityPublishResult bulkCreateOrUpdateEntities(PurviewConnectionRequest request, Map<String, Object> payload)
            throws PurviewClientException {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new PurviewClientException("Failed to serialize Purview entity payload", e);
        }

        HttpRequest entityRequest = HttpRequest.newBuilder(buildEntityBulkUri(request))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = send(entityRequest);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return parseBulkResponse(response.body(), countEntities(payload));
        }

        return PurviewEntityPublishResult.failure(
                "Purview entity publish returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    @Override
    public Map<String, Object> getEntityByUniqueAttribute(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) throws PurviewClientException {
        HttpRequest getRequest = HttpRequest.newBuilder(
                buildEntityUniqueAttributeUri(request, typeName, attributeName, attributeValue))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = send(getRequest);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            if (response.body() == null || response.body().isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            } catch (IOException e) {
                throw new PurviewClientException("Failed to parse Purview entity response", e);
            }
        }

        throw new PurviewClientException(
                "Purview entity read returned HTTP " + response.statusCode() + formatBodyExcerpt(response.body()));
    }

    @Override
    public PurviewEntityPublishResult deleteByUniqueAttribute(
            PurviewConnectionRequest request,
            String typeName,
            String attributeName,
            String attributeValue) throws PurviewClientException {
        HttpRequest deleteRequest = HttpRequest.newBuilder(
                buildEntityUniqueAttributeUri(request, typeName, attributeName, attributeValue))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .DELETE()
                .build();

        HttpResponse<String> response = send(deleteRequest);
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
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new PurviewClientException("Failed to serialize Purview relationship payload", e);
        }

        HttpRequest relationshipRequest = HttpRequest.newBuilder(buildRelationshipUri(request))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = send(relationshipRequest);
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
        HttpRequest deleteRequest = HttpRequest.newBuilder(buildRelationshipGuidUri(request, relationshipGuid))
                .header("Authorization", buildAuthorizationHeader(request))
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(request.getReadTimeoutMs()))
                .DELETE()
                .build();

        HttpResponse<String> response = send(deleteRequest);
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

    PurviewEntityPublishResult parseBulkResponse(String body, int requestedCount) {
        if (body == null || body.isBlank()) {
            logger.warn("Purview bulk publish returned empty response body for {} requested entities", requestedCount);
            return PurviewEntityPublishResult.success(0, "empty response body — success count unknown");
        }

        try {
            JsonNode json = objectMapper.readTree(body);

            List<PurviewEntityPublishResult.FailedItem> failedItems = new ArrayList<>();
            JsonNode failedOps = json.get("failedEntityOperations");
            if (failedOps != null && failedOps.isArray()) {
                for (JsonNode failedOp : failedOps) {
                    String qualifiedName = extractQualifiedNameFromFailedOp(failedOp);
                    String typeName = extractFieldText(failedOp, "typeName");
                    String errorMessage = extractFieldText(failedOp, "errorMessage");
                    if (errorMessage == null || errorMessage.isBlank()) {
                        errorMessage = extractFieldText(failedOp, "message");
                    }
                    failedItems.add(new PurviewEntityPublishResult.FailedItem(
                            qualifiedName, typeName, errorMessage));
                }
            }

            int mutatedCount = 0;
            Map<String, String> entityGuids = new LinkedHashMap<>();
            JsonNode mutatedEntities = json.get("mutatedEntities");
            if (mutatedEntities != null && mutatedEntities.isObject()) {
                var fields = mutatedEntities.fields();
                while (fields.hasNext()) {
                    JsonNode arr = fields.next().getValue();
                    if (arr != null && arr.isArray()) {
                        mutatedCount += arr.size();
                        for (JsonNode entry : arr) {
                            String guid = extractFieldText(entry, "guid");
                            if (guid == null) continue;

                            // 1. attributes.qualifiedName (Purview standard)
                            String qn = null;
                            JsonNode attrs = entry.get("attributes");
                            if (attrs != null) {
                                qn = extractFieldText(attrs, "qualifiedName");
                            }
                            // 2. Fallback: top-level qualifiedName (Atlas response format)
                            if (qn == null) {
                                qn = extractFieldText(entry, "qualifiedName");
                            }

                            if (qn != null) {
                                entityGuids.put(qn, guid);
                            }
                        }
                    }
                }
            }

            int unresolvedCount = mutatedCount - entityGuids.size();
            if (unresolvedCount > 0) {
                logger.debug("Purview bulk response: {} of {} mutated entities have no qualifiedName — "
                        + "GUID tracking unavailable, relationship creation will use uniqueAttributes fallback",
                        unresolvedCount, mutatedCount);
            }

            if (failedItems.isEmpty()) {
                return PurviewEntityPublishResult.success(mutatedCount, "entities published", entityGuids);
            }

            int successCount = mutatedCount;
            logger.warn("Purview bulk publish partial failure: {} succeeded, {} failed",
                    successCount, failedItems.size());
            return PurviewEntityPublishResult.partialSuccess(
                    successCount,
                    failedItems.size(),
                    failedItems,
                    "partial failure: " + failedItems.size() + " entities failed",
                    entityGuids);
        } catch (IOException e) {
            logger.warn("Could not parse Purview bulk response for {} requested entities", requestedCount, e);
            return PurviewEntityPublishResult.failure(
                    "Purview bulk response parse error: " + e.getMessage());
        }
    }

    private String extractQualifiedNameFromFailedOp(JsonNode failedOp) {
        JsonNode attributes = failedOp.get("attributes");
        if (attributes != null) {
            String qn = extractFieldText(attributes, "qualifiedName");
            if (qn != null && !qn.isBlank()) {
                return qn;
            }
        }
        return extractFieldText(failedOp, "qualifiedName");
    }

    private String extractFieldText(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field != null && !field.isNull()) {
            return field.asText();
        }
        return null;
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

    URI buildEntityBulkUri(PurviewConnectionRequest request) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + ENTITY_BULK_PATH;
        if (request.isPurviewDataMap()) {
            uri = PurviewDataMapApi.withApiVersion(uri, request);
            // Entity writes are collection-scoped in Purview, and a write that names no
            // collection lands in the root one. With a service principal holding Data Curator
            // on the configured collection only — the runbook's least-privilege setup — that
            // write is a 403; with root-level rights it silently files every entity somewhere
            // other than where configuration said. Naming the collection is the only shape in
            // which both the permission model and the configuration mean what they say.
            //
            // Only on entity create/update: the public API scopes collectionId to these
            // operations, and reads and relationship routes do not take it.
            if (request.hasCollection()) {
                uri = uri + "&collectionId=" + urlEncode(request.getCollectionId());
            }
        } else if (request.hasCollection()) {
            // The classic catalog/... surface has no collectionId parameter, so the configured
            // collection cannot be honoured there — entities will land in the root collection.
            // Said out loud on every write rather than once, because every one of these writes
            // is landing somewhere configuration says it should not.
            logger.warn("A Purview collection is configured but the classic '{}' surface cannot"
                    + " honour collection placement — entities will land in the root collection."
                    + " Use the datamap/ base path (purview.atlas.base-path)",
                    request.getAtlasBasePath());
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
        uri = PurviewDataMapApi.withApiVersion(uri, request);
        return URI.create(uri);
    }

    private URI buildRelationshipUri(PurviewConnectionRequest request) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + RELATIONSHIP_PATH;
        uri = PurviewDataMapApi.withApiVersion(uri, request);
        return URI.create(uri);
    }

    private URI buildRelationshipGuidUri(PurviewConnectionRequest request, String relationshipGuid) {
        String uri = trimTrailingSlash(request.getEndpoint()) + "/"
                + trimSlashes(request.getAtlasBasePath()) + "/" + RELATIONSHIP_GUID_PATH + "/"
                + urlEncodePathSegment(relationshipGuid);
        uri = PurviewDataMapApi.withApiVersion(uri, request);
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
