package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class HttpPurviewEntityRegistryClientIntegrationTest {

    private MockWebServer apiServer;
    private HttpPurviewEntityRegistryClient client;
    private PurviewTokenCache tokenCache;

    @BeforeEach
    public void setUp() throws Exception {
        apiServer = new MockWebServer();
        apiServer.start();
        tokenCache = new PurviewTokenCache();
        PurviewHttpRetryHandler retryHandler = new TestableRetryHandler();
        client = new HttpPurviewEntityRegistryClient(
                java.net.http.HttpClient.newBuilder().build(),
                tokenCache,
                retryHandler);
    }

    @AfterEach
    public void tearDown() throws Exception {
        apiServer.shutdown();
    }

    @Test
    public void testBulkCreateSendsAuthorizationBearerHeader() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"mutatedEntities\":{\"CREATE\":[{\"guid\":\"g1\"}]}}"));

        PurviewConnectionRequest request = buildRequest();
        client.bulkCreateOrUpdateEntities(request, Map.of("entities", List.of(Map.of("typeName", "nemaki_document"))));

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertNotNull(apiRequest.getHeader("Authorization"));
        assertTrue(apiRequest.getHeader("Authorization").startsWith("Bearer "));
        assertEquals("application/json", apiRequest.getHeader("Content-Type"));
    }

    @Test
    public void testBulkCreateUsesDatamapApiVersionParameter() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        PurviewConnectionRequest request = buildRequestWithBasePath("datamap/api/atlas/v2");
        client.bulkCreateOrUpdateEntities(request, Map.of("entities", List.of()));

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertTrue(apiRequest.getPath().contains("api-version=2023-09-01"),
                "Expected api-version parameter but got: " + apiRequest.getPath());
    }

    @Test
    public void testBulkCreateOmitsApiVersionForCatalogBasePath() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        PurviewConnectionRequest request = buildRequestWithBasePath("catalog/api/atlas/v2");
        client.bulkCreateOrUpdateEntities(request, Map.of("entities", List.of()));

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertFalse(apiRequest.getPath().contains("api-version"),
                "Expected no api-version parameter but got: " + apiRequest.getPath());
    }

    @Test
    public void testBulkCreateParsesBulkPartialFailureResponse() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "mutatedEntities": {"CREATE": [{"guid": "g1"}]},
                            "failedEntityOperations": [
                                {
                                    "typeName": "nemaki_document",
                                    "attributes": {"qualifiedName": "nemaki://bedroom/objects/bad-doc"},
                                    "errorMessage": "Schema mismatch"
                                }
                            ]
                        }
                        """));

        PurviewEntityPublishResult result = client.bulkCreateOrUpdateEntities(
                buildRequest(),
                Map.of("entities", List.of(
                        Map.of("typeName", "nemaki_document"),
                        Map.of("typeName", "nemaki_document"))));

        assertTrue(result.isSuccess());
        assertTrue(result.hasFailures());
        assertEquals(1, result.getPublishedCount());
        assertEquals(1, result.getFailureCount());
        assertEquals("nemaki://bedroom/objects/bad-doc", result.getFailedItems().get(0).getQualifiedName());
    }

    @Test
    public void testBulkCreateReturnsFailureForHttpError() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"Bad request\"}"));

        PurviewEntityPublishResult result = client.bulkCreateOrUpdateEntities(
                buildRequest(),
                Map.of("entities", List.of(Map.of("typeName", "nemaki_document"))));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("400"));
    }

    @Test
    public void testGetEntityByUniqueAttributeReturnsNullFor404() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(404));

        Map<String, Object> entity = client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/missing");

        assertNull(entity);
    }

    @Test
    public void testGetEntityByUniqueAttributeEncodesSpecialCharacters() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"entity\":{\"guid\":\"g1\"}}"));

        client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc with spaces & special=chars");

        RecordedRequest apiRequest = apiServer.takeRequest();
        String path = apiRequest.getPath();
        assertFalse(path.contains(" "), "Expected spaces to be encoded: " + path);
        assertTrue(path.contains("nemaki%3A%2F%2Fbedroom%2Fobjects%2Fdoc+with+spaces+%26+special%3Dchars")
                || path.contains("nemaki%3A%2F%2Fbedroom%2Fobjects%2Fdoc%20with%20spaces"),
                "Expected special characters to be encoded: " + path);
    }

    @Test
    public void testGetEntityByUniqueAttributeThrowsOnServerError() throws Exception {
        enqueueTokenResponse();
        // Enqueue enough 500s for all retry attempts (initial + MAX_RETRIES=3)
        for (int i = 0; i < 4; i++) {
            apiServer.enqueue(new MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"));
        }

        assertThrows(PurviewClientException.class,
                () -> client.getEntityByUniqueAttribute(
                        buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-1"));
    }

    @Test
    public void testDeleteByUniqueAttributeReturnsSuccessFor404() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(404));

        PurviewEntityPublishResult result = client.deleteByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/missing");

        assertTrue(result.isSuccess());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testCreateRelationshipExtractsGuidFrom200() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"guid\":\"rel-guid-123\"}"));

        PurviewEntityPublishResult result = client.createRelationship(
                buildRequest(),
                Map.of("typeName", "nemaki_containment"));

        assertTrue(result.isSuccess());
        assertEquals("rel-guid-123", result.getResourceGuid());
    }

    @Test
    public void testCreateRelationshipExtractsGuidFrom409() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"relationship\":{\"guid\":\"existing-rel-guid\"}}"));

        PurviewEntityPublishResult result = client.createRelationship(
                buildRequest(),
                Map.of("typeName", "nemaki_containment"));

        assertTrue(result.isSuccess());
        assertEquals("existing-rel-guid", result.getResourceGuid());
    }

    @Test
    public void testDeleteRelationshipByGuidReturnsSuccessFor404() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(404));

        PurviewEntityPublishResult result = client.deleteRelationshipByGuid(buildRequest(), "rel-guid-123");

        assertTrue(result.isSuccess());
        assertEquals(0, result.getPublishedCount());
        assertEquals("rel-guid-123", result.getResourceGuid());
    }

    // --- getEntityByUniqueAttribute: success path ---

    @Test
    public void testGetEntityByUniqueAttributeReturnsEntityOn200() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                            "entity": {
                                "guid": "entity-guid-001",
                                "typeName": "nemaki_document",
                                "attributes": {
                                    "qualifiedName": "nemaki://bedroom/objects/doc-001",
                                    "name": "test-document"
                                }
                            }
                        }
                        """));

        Map<String, Object> entity = client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertNotNull(entity);
        assertNotNull(entity.get("entity"));
    }

    @Test
    public void testGetEntityByUniqueAttributeReturnsEmptyMapForEmptyBody() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(""));

        Map<String, Object> entity = client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertNotNull(entity);
        assertTrue(entity.isEmpty());
    }

    @Test
    public void testGetEntityByUniqueAttributeUsesCorrectPath() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"entity\":{}}"));

        client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertTrue(apiRequest.getPath().contains("entity/uniqueAttribute/type/nemaki_document"),
                "Expected entity/uniqueAttribute/type path, got: " + apiRequest.getPath());
        assertTrue(apiRequest.getPath().contains("attr:qualifiedName="),
                "Expected attr parameter, got: " + apiRequest.getPath());
        assertEquals("GET", apiRequest.getMethod());
    }

    @Test
    public void testGetEntityByUniqueAttributeThrowsOn403() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"error\":\"Access denied\"}"));

        PurviewClientException ex = assertThrows(PurviewClientException.class,
                () -> client.getEntityByUniqueAttribute(
                        buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-1"));
        assertTrue(ex.getMessage().contains("403"));
    }

    // --- deleteByUniqueAttribute: expanded ---

    @Test
    public void testDeleteByUniqueAttributeReturnsSuccessOn200() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"mutatedEntities\":{\"DELETE\":[{\"guid\":\"g1\"}]}}"));

        PurviewEntityPublishResult result = client.deleteByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPublishedCount());
    }

    @Test
    public void testDeleteByUniqueAttributeReturnsFailureOnServerError() throws Exception {
        enqueueTokenResponse();
        // 4 attempts (initial + 3 retries)
        for (int i = 0; i < 4; i++) {
            apiServer.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));
        }

        PurviewEntityPublishResult result = client.deleteByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    public void testDeleteByUniqueAttributeUsesDeleteMethod() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.deleteByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertEquals("DELETE", apiRequest.getMethod());
        assertTrue(apiRequest.getPath().contains("entity/uniqueAttribute/type/nemaki_document"));
    }

    @Test
    public void testDeleteByUniqueAttributeReturnsFailureOn403() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"error\":\"Access denied\"}"));

        PurviewEntityPublishResult result = client.deleteByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("403"));
    }

    // --- createRelationship: expanded ---

    @Test
    public void testCreateRelationshipReturnsFailureOnServerError() throws Exception {
        enqueueTokenResponse();
        for (int i = 0; i < 4; i++) {
            apiServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        }

        PurviewEntityPublishResult result = client.createRelationship(
                buildRequest(),
                Map.of("typeName", "nemaki_containment"));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    public void testCreateRelationshipReturnsFailureOn400() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":\"Invalid relationship payload\"}"));

        PurviewEntityPublishResult result = client.createRelationship(
                buildRequest(),
                Map.of("typeName", "nemaki_containment"));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("400"));
    }

    @Test
    public void testCreateRelationshipUsesPostMethod() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"guid\":\"rel-001\"}"));

        client.createRelationship(buildRequest(), Map.of("typeName", "nemaki_containment"));

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertEquals("POST", apiRequest.getMethod());
        assertTrue(apiRequest.getPath().contains("/relationship"));
        assertTrue(apiRequest.getBody().readUtf8().contains("nemaki_containment"));
    }

    @Test
    public void testCreateRelationshipHandlesNullGuidInResponse() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ok\"}"));

        PurviewEntityPublishResult result = client.createRelationship(
                buildRequest(),
                Map.of("typeName", "nemaki_containment"));

        assertTrue(result.isSuccess());
        assertNull(result.getResourceGuid());
    }

    // --- deleteRelationshipByGuid: expanded ---

    @Test
    public void testDeleteRelationshipByGuidReturnsSuccessOn200() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(200));

        PurviewEntityPublishResult result = client.deleteRelationshipByGuid(buildRequest(), "rel-guid-456");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPublishedCount());
        assertEquals("rel-guid-456", result.getResourceGuid());
    }

    @Test
    public void testDeleteRelationshipByGuidReturnsFailureOnServerError() throws Exception {
        enqueueTokenResponse();
        for (int i = 0; i < 4; i++) {
            apiServer.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));
        }

        PurviewEntityPublishResult result = client.deleteRelationshipByGuid(buildRequest(), "rel-guid-456");

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    public void testDeleteRelationshipByGuidUsesDeleteMethod() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(200));

        client.deleteRelationshipByGuid(buildRequest(), "rel-guid-456");

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertEquals("DELETE", apiRequest.getMethod());
        assertTrue(apiRequest.getPath().contains("relationship/guid/rel-guid-456"),
                "Expected relationship/guid path, got: " + apiRequest.getPath());
    }

    @Test
    public void testDeleteRelationshipByGuidReturnsFailureOn403() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"error\":\"Access denied\"}"));

        PurviewEntityPublishResult result = client.deleteRelationshipByGuid(buildRequest(), "rel-guid-456");

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("403"));
    }

    // --- Token cache behavior ---

    @Test
    public void testSecondCallReusesCachedTokenWithoutRefetch() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"entity\":{\"guid\":\"g1\"}}"));
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"entity\":{\"guid\":\"g2\"}}"));

        PurviewConnectionRequest request = buildRequest();
        client.getEntityByUniqueAttribute(request, "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");
        client.getEntityByUniqueAttribute(request, "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-002");

        // Both API calls should use the same pre-seeded token (2 requests only)
        assertEquals(2, apiServer.getRequestCount());
        RecordedRequest req1 = apiServer.takeRequest();
        RecordedRequest req2 = apiServer.takeRequest();
        assertEquals("Bearer mock-access-token", req1.getHeader("Authorization"));
        assertEquals("Bearer mock-access-token", req2.getHeader("Authorization"));
    }

    @Test
    public void testDifferentTokensForDifferentTenants() throws Exception {
        tokenCache.put("tenant-A", "client-A", "token-A", 3600L);
        tokenCache.put("tenant-B", "client-B", "token-B", 3600L);
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"entity\":{}}"));
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"entity\":{}}"));

        PurviewConnectionRequest requestA = new PurviewConnectionRequest(
                apiServer.url("/").toString(), "datamap/api/atlas/v2",
                "tenant-A", "client-A", "secret-A", 5000, 30000);
        PurviewConnectionRequest requestB = new PurviewConnectionRequest(
                apiServer.url("/").toString(), "datamap/api/atlas/v2",
                "tenant-B", "client-B", "secret-B", 5000, 30000);

        client.getEntityByUniqueAttribute(requestA, "nemaki_document", "qualifiedName", "qn-1");
        client.getEntityByUniqueAttribute(requestB, "nemaki_document", "qualifiedName", "qn-2");

        RecordedRequest req1 = apiServer.takeRequest();
        RecordedRequest req2 = apiServer.takeRequest();
        assertEquals("Bearer token-A", req1.getHeader("Authorization"));
        assertEquals("Bearer token-B", req2.getHeader("Authorization"));
    }

    // --- Retry integration tests ---

    @Test
    public void testBulkCreateRetriesOn429ThenSucceeds() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limited"));
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"mutatedEntities\":{\"CREATE\":[{\"guid\":\"g1\"}]}}"));

        PurviewEntityPublishResult result = client.bulkCreateOrUpdateEntities(
                buildRequest(),
                Map.of("entities", List.of(Map.of("typeName", "nemaki_document"))));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPublishedCount());
    }

    @Test
    public void testBulkCreateRetriesOn503ThenSucceeds() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        apiServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"mutatedEntities\":{\"CREATE\":[{\"guid\":\"g1\"}]}}"));

        PurviewEntityPublishResult result = client.bulkCreateOrUpdateEntities(
                buildRequest(),
                Map.of("entities", List.of(Map.of("typeName", "nemaki_document"))));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getPublishedCount());
    }

    @Test
    public void testGetEntityRetriesOn429ThenSucceeds() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limited"));
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"entity\":{\"guid\":\"g1\"}}"));

        Map<String, Object> entity = client.getEntityByUniqueAttribute(
                buildRequest(), "nemaki_document", "qualifiedName", "nemaki://bedroom/objects/doc-001");

        assertNotNull(entity);
    }

    // --- bulkCreate: additional edge cases ---

    @Test
    public void testBulkCreateSendsPayloadInRequestBody() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"mutatedEntities\":{\"CREATE\":[{\"guid\":\"g1\"},{\"guid\":\"g2\"}]}}"));

        client.bulkCreateOrUpdateEntities(
                buildRequest(),
                Map.of("entities", List.of(
                        Map.of("typeName", "nemaki_document",
                                "attributes", Map.of("qualifiedName", "nemaki://bedroom/objects/doc-001")),
                        Map.of("typeName", "nemaki_folder",
                                "attributes", Map.of("qualifiedName", "nemaki://bedroom/objects/folder-001")))));

        RecordedRequest apiRequest = apiServer.takeRequest();
        String body = apiRequest.getBody().readUtf8();
        assertTrue(body.contains("nemaki_document"));
        assertTrue(body.contains("nemaki_folder"));
        assertTrue(body.contains("nemaki://bedroom/objects/doc-001"));
    }

    @Test
    public void testBulkCreateReturnsSuccessForEmptyEntitiesList() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"mutatedEntities\":{}}"));

        PurviewEntityPublishResult result = client.bulkCreateOrUpdateEntities(
                buildRequest(), Map.of("entities", List.of()));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testTokenCacheReusesAcquiredToken() throws Exception {
        enqueueTokenResponse();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        PurviewConnectionRequest request = buildRequest();
        client.bulkCreateOrUpdateEntities(request, Map.of("entities", List.of()));
        client.bulkCreateOrUpdateEntities(request, Map.of("entities", List.of()));

        assertEquals(2, apiServer.getRequestCount(), "Both API calls should complete");
    }

    private PurviewConnectionRequest buildRequest() {
        return buildRequestWithBasePath("datamap/api/atlas/v2");
    }

    private PurviewConnectionRequest buildRequestWithBasePath(String basePath) {
        return new PurviewConnectionRequest(
                apiServer.url("/").toString(),
                basePath,
                "test-tenant",
                "test-client-id",
                "test-client-secret",
                5000,
                30000);
    }

    private void enqueueTokenResponse() {
        tokenCache.put("test-tenant", "test-client-id", "mock-access-token", 3600L);
    }

    /**
     * A retry handler with no delays for integration tests.
     */
    private static class TestableRetryHandler extends PurviewHttpRetryHandler {
        @Override
        public long calculateBackoffDelay(int attemptNumber) {
            return 0L;
        }
    }
}
