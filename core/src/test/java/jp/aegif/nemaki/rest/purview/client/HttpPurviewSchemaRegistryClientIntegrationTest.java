package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

public class HttpPurviewSchemaRegistryClientIntegrationTest {

    private MockWebServer apiServer;
    private HttpPurviewSchemaRegistryClient client;
    private PurviewTokenCache tokenCache;

    @BeforeEach
    public void setUp() throws Exception {
        apiServer = new MockWebServer();
        apiServer.start();
        tokenCache = new PurviewTokenCache();
        PurviewHttpRetryHandler retryHandler = new TestableRetryHandler();
        client = new HttpPurviewSchemaRegistryClient(
                java.net.http.HttpClient.newBuilder().build(),
                tokenCache,
                retryHandler);
    }

    @AfterEach
    public void tearDown() throws Exception {
        apiServer.shutdown();
    }

    @Test
    public void testApplySchemaSuccessSendsCorrectRequest() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));

        Map<String, Object> payload = Map.of("entityDefs", List.of(Map.of("name", "nemaki_document")));
        PurviewSchemaPublishResult result = client.applySchema(buildRequest(), payload);

        assertTrue(result.isSuccess());
        RecordedRequest apiRequest = apiServer.takeRequest();
        assertNotNull(apiRequest.getHeader("Authorization"));
        assertTrue(apiRequest.getHeader("Authorization").startsWith("Bearer "));
        assertTrue(apiRequest.getPath().contains("types/typedefs"));
        assertTrue(apiRequest.getBody().readUtf8().contains("nemaki_document"));
    }

    @Test
    public void testApplySchemaReturnsFailureOnHttpError() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setBody("{\"error\":\"Type already exists\"}"));

        PurviewSchemaPublishResult result = client.applySchema(
                buildRequest(),
                Map.of("entityDefs", List.of()));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("409"));
    }

    @Test
    public void testApplySchemaUsesCorrectPath() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        client.applySchema(buildRequest(), Map.of("entityDefs", List.of()));

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertTrue(apiRequest.getPath().endsWith("/catalog/api/atlas/v2/types/typedefs")
                || apiRequest.getPath().contains("types/typedefs"),
                "Expected types/typedefs path, got: " + apiRequest.getPath());
    }

    private PurviewConnectionRequest buildRequest() {
        return new PurviewConnectionRequest(
                apiServer.url("/").toString(),
                "catalog/api/atlas/v2",
                "test-tenant",
                "test-client-id",
                "test-client-secret",
                5000,
                30000);
    }

    private void seedToken() {
        tokenCache.put("test-tenant", "test-client-id", "mock-access-token", 3600L);
    }

    private static class TestableRetryHandler extends PurviewHttpRetryHandler {
        @Override
        public long calculateBackoffDelay(int attemptNumber) {
            return 0L;
        }
    }
}
