package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewHttpRetryHandlerTest {

    private PurviewHttpRetryHandler handler;

    @BeforeEach
    public void setUp() {
        handler = new PurviewHttpRetryHandler();
    }

    @Test
    public void testNoRetryOn200() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(1, callCount[0]);
    }

    @Test
    public void testRetryOn429ThenSuccess() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(429, "Too Many Requests");
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0]);
    }

    @Test
    public void testRetryOn500ThenSuccess() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(500, "Internal Server Error");
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0]);
    }

    @Test
    public void testRetryOn503ThenSuccess() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(503, "Service Unavailable");
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0]);
    }

    @Test
    public void testNoRetryOn400() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(400, "Bad Request");
        }, null, null);

        assertEquals(400, result.statusCode());
        assertEquals(1, callCount[0]);
    }

    @Test
    public void testNoRetryOn404() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(404, "Not Found");
        }, null, null);

        assertEquals(404, result.statusCode());
        assertEquals(1, callCount[0]);
    }

    @Test
    public void testMaxRetriesExhausted() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(500, "Internal Server Error");
        }, null, null);

        assertEquals(500, result.statusCode());
        assertEquals(4, callCount[0]); // 1 initial + 3 retries
    }

    @Test
    public void testRetryOnIOException() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] <= 2) {
                throw new IOException("Connection reset");
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(3, callCount[0]);
    }

    @Test
    public void testIOExceptionExhaustsRetries() {
        int[] callCount = {0};
        PurviewClientException ex = assertThrows(PurviewClientException.class, () -> {
            handler.sendWithRetry(() -> {
                callCount[0]++;
                throw new IOException("Connection refused");
            }, null, null);
        });

        assertEquals(4, callCount[0]); // 1 initial + 3 retries
        assertTrue(ex.getMessage().contains("Connection refused"));
    }

    @Test
    public void test401InvalidatesCacheAndRetries() throws Exception {
        int[] callCount = {0};
        boolean[] invalidated = {false};
        PurviewTokenCache tokenCache = new PurviewTokenCache();
        tokenCache.put("t1", "c1", "old-token", 3600L);

        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(401, "Unauthorized");
            }
            return mockResponse(200, "ok");
        }, tokenCache, () -> {
            invalidated[0] = true;
            tokenCache.invalidate("t1", "c1");
        });

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0]);
        assertTrue(invalidated[0]);
    }

    @Test
    public void test401WithoutCacheDoesNotRetry() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(401, "Unauthorized");
        }, null, null);

        assertEquals(401, result.statusCode());
        assertEquals(1, callCount[0]);
    }

    @Test
    public void testBackoffCalculation() {
        long delay1 = handler.calculateBackoffDelay(1);
        assertTrue(delay1 >= 900 && delay1 <= 1100, "First delay should be ~1000ms, got: " + delay1);

        long delay2 = handler.calculateBackoffDelay(2);
        assertTrue(delay2 >= 1800 && delay2 <= 2200, "Second delay should be ~2000ms, got: " + delay2);

        long delay3 = handler.calculateBackoffDelay(3);
        assertTrue(delay3 >= 3600 && delay3 <= 4400, "Third delay should be ~4000ms, got: " + delay3);
    }

    @Test
    public void testBackoffCapsAtMaximum() {
        long delay = handler.calculateBackoffDelay(100);
        assertTrue(delay <= 330000, "Delay should be capped at max + jitter, got: " + delay);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int statusCode, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public String body() { return body; }
            @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.net.URI uri() { return null; }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
