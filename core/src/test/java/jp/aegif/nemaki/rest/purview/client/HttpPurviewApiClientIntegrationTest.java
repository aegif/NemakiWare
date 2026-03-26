package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class HttpPurviewApiClientIntegrationTest {

    private MockWebServer apiServer;
    private HttpPurviewApiClient client;
    private PurviewTokenCache tokenCache;

    @BeforeEach
    public void setUp() throws Exception {
        apiServer = new MockWebServer();
        apiServer.start();
        tokenCache = new PurviewTokenCache();
        PurviewHttpRetryHandler retryHandler = new TestableRetryHandler();
        client = new HttpPurviewApiClient(
                java.net.http.HttpClient.newBuilder().build(),
                tokenCache,
                retryHandler);
    }

    @AfterEach
    public void tearDown() throws Exception {
        apiServer.shutdown();
    }

    @Test
    public void testProbeConnectionSuccessReturnsOk() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertTrue(result.isSuccess());
        RecordedRequest apiRequest = apiServer.takeRequest();
        assertNotNull(apiRequest.getHeader("Authorization"));
        assertTrue(apiRequest.getPath().contains("types/typedefs/headers"));
    }

    @Test
    public void testProbeConnectionReturnsFailureOnServerError() throws Exception {
        seedToken();
        // Enqueue enough 503s for all retry attempts (initial + MAX_RETRIES=3)
        for (int i = 0; i < 4; i++) {
            apiServer.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setBody("Service Unavailable"));
        }

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("503"));
    }

    @Test
    public void testProbeConnectionReturnsFailureOn403() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"error\":\"Forbidden\"}"));

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("403"));
    }

    @Test
    public void testProbeConnectionSendsBearerToken() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        client.probeConnection(buildRequest());

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertTrue(apiRequest.getHeader("Authorization").equals("Bearer mock-access-token"));
    }

    @Test
    public void testProbeConnectionRetriesOn429ThenSucceeds() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limited"));
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertTrue(result.isSuccess());
    }

    @Test
    public void testProbeConnectionRetriesOn500ThenSucceeds() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse().setResponseCode(500).setBody("Error"));
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[]"));

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertTrue(result.isSuccess());
    }

    @Test
    public void testProbeConnectionUsesCorrectEndpointPath() throws Exception {
        seedToken();
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        client.probeConnection(buildRequest());

        RecordedRequest apiRequest = apiServer.takeRequest();
        assertEquals("GET", apiRequest.getMethod());
        assertTrue(apiRequest.getPath().contains("types/typedefs/headers"),
                "Expected types/typedefs/headers path, got: " + apiRequest.getPath());
    }

    @Test
    public void testProbeConnectionReturnsFailureOn401() throws Exception {
        seedToken();
        // 401 triggers 1 retry (token invalidation + refetch which fails since cache empty)
        apiServer.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"));
        apiServer.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"Unauthorized\"}"));

        PurviewProbeResult result = client.probeConnection(buildRequest());

        assertFalse(result.isSuccess());
    }

    @Test
    public void testProbeConnectionSendsBasicAuthHeaderForBasicAuth() throws Exception {
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        PurviewConnectionRequest basicRequest = new PurviewConnectionRequest(
                apiServer.url("/").toString(),
                "api/atlas/v2",
                "basic",
                "", "", "",
                "admin", "admin",
                5000, 30000);

        PurviewProbeResult result = client.probeConnection(basicRequest);

        assertTrue(result.isSuccess());
        RecordedRequest apiRequest = apiServer.takeRequest();
        String authHeader = apiRequest.getHeader("Authorization");
        assertNotNull(authHeader);
        assertTrue(authHeader.startsWith("Basic "), "Expected Basic auth header, got: " + authHeader);
    }

    @Test
    public void testProbeConnectionBasicAuthDoesNotAcquireOAuthToken() throws Exception {
        apiServer.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

        PurviewConnectionRequest basicRequest = new PurviewConnectionRequest(
                apiServer.url("/").toString(),
                "api/atlas/v2",
                "basic",
                "", "", "",
                "admin", "admin",
                5000, 30000);

        client.probeConnection(basicRequest);

        // Only 1 request (the probe itself), no token request
        assertEquals(1, apiServer.getRequestCount());
    }

    private PurviewConnectionRequest buildRequest() {
        return new PurviewConnectionRequest(
                apiServer.url("/").toString(),
                "datamap/api/atlas/v2",
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
