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

    /**
     * 403 is an RBAC verdict, not a transient fault — Data Curator missing on the collection,
     * say. Retrying hammers an account that already answered, and no number of attempts
     * changes a role assignment; the fix is an operator action.
     */
    @Test
    public void testNoRetryOn403() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(403, "Forbidden");
        }, null, null);

        assertEquals(403, result.statusCode());
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
    /**
     * A Retry-After beyond this attempt's budgeted worst ends the sequence, fast.
     *
     * <p>Sleeping it would overrun the fence budget; retrying sooner than instructed would
     * re-hammer a throttled account. Handing the response back is the only move that honours
     * both, and the long wait then happens at the obligation's durable backoff instead.
     */
    @Test
    public void testRetryAfterBeyondBudgetStopsRetrying() throws Exception {
        int[] callCount = {0};
        long started = System.nanoTime();
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            return mockResponse(429, "Too Many Requests",
                    java.util.Map.of("Retry-After", java.util.List.of("9999")));
        }, null, null);

        assertEquals(429, result.statusCode(), "the throttle response is returned, not hidden");
        assertEquals(1, callCount[0], "no further attempt may be made in this pass");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs < 500, "and no sleep either, got " + elapsedMs + "ms");
    }

    /** A Retry-After that fits under the attempt's budgeted worst is slept exactly. */
    @Test
    public void testRetryAfterWithinBudgetIsHonoured() throws Exception {
        int[] callCount = {0};
        long started = System.nanoTime();
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(429, "Too Many Requests",
                        java.util.Map.of("Retry-After", java.util.List.of("1")));
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0]);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs >= 950, "the server's 1s must actually be waited, got "
                + elapsedMs + "ms");
    }

    /**
     * The HTTP-date form reads as no instruction, never as a sleep.
     *
     * <p>Honouring a date would turn clock skew into an arbitrary wait — the unbounded sleep
     * the budget forbids — so the ordinary backoff applies instead.
     */
    @Test
    public void testRetryAfterDateFormFallsBackToBackoff() throws Exception {
        int[] callCount = {0};
        HttpResponse<String> result = handler.sendWithRetry(() -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockResponse(503, "Service Unavailable", java.util.Map.of("Retry-After",
                        java.util.List.of("Wed, 21 Oct 2026 07:28:00 GMT")));
            }
            return mockResponse(200, "ok");
        }, null, null);

        assertEquals(200, result.statusCode());
        assertEquals(2, callCount[0], "an unusable header must not stop the ordinary retry");
    }

    /**
     * The budget total and the per-attempt caps are one formula, not two.
     *
     * <p>The lineage operation budget reads {@code worstCaseBackoffTotalMs()}; the Retry-After
     * cap reads {@code worstDelayForAttempt()}. If they drifted apart, an honoured server delay
     * could exceed what the budget promised.
     */
    @Test
    public void testWorstCaseTotalIsTheSumOfPerAttemptWorsts() {
        long total = 0;
        for (int attempt = 1; attempt <= PurviewHttpRetryHandler.maxRetries(); attempt++) {
            total += PurviewHttpRetryHandler.worstDelayForAttempt(attempt);
        }
        assertEquals(PurviewHttpRetryHandler.worstCaseBackoffTotalMs(), total);
    }

    private HttpResponse<String> mockResponse(int statusCode, String body) {
        return mockResponse(statusCode, body, java.util.Map.of());
    }

    private HttpResponse<String> mockResponse(int statusCode, String body,
            java.util.Map<String, java.util.List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public String body() { return body; }
            @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(headers, (a, b) -> true); }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.net.URI uri() { return null; }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
