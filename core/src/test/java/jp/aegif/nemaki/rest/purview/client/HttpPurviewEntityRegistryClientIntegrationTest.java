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
