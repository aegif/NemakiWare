package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PurviewTokenCacheTest {

    private PurviewTokenCache cache;

    @BeforeEach
    public void setUp() {
        cache = new PurviewTokenCache();
    }

    @Test
    public void testCacheHitReturnsCachedToken() {
        long expiresIn = 3600L;
        cache.put("tenant-1", "client-1", "token-abc", expiresIn);

        String cached = cache.get("tenant-1", "client-1");
        assertEquals("token-abc", cached);
    }

    @Test
    public void testCacheMissReturnsNullForUnknownKey() {
        assertNull(cache.get("unknown-tenant", "unknown-client"));
    }

    @Test
    public void testExpiredTokenReturnsNull() {
        // expiresIn=0 means already expired (with 5-min safety margin)
        cache.put("tenant-1", "client-1", "token-expired", 0L);

        assertNull(cache.get("tenant-1", "client-1"));
    }

    @Test
    public void testDifferentTenantsAreCachedSeparately() {
        cache.put("tenant-A", "client-1", "token-A", 3600L);
        cache.put("tenant-B", "client-1", "token-B", 3600L);

        assertEquals("token-A", cache.get("tenant-A", "client-1"));
        assertEquals("token-B", cache.get("tenant-B", "client-1"));
    }

    @Test
    public void testDifferentClientsAreCachedSeparately() {
        cache.put("tenant-1", "client-A", "token-A", 3600L);
        cache.put("tenant-1", "client-B", "token-B", 3600L);

        assertEquals("token-A", cache.get("tenant-1", "client-A"));
        assertEquals("token-B", cache.get("tenant-1", "client-B"));
    }

    @Test
    public void testInvalidateRemovesToken() {
        cache.put("tenant-1", "client-1", "token-abc", 3600L);
        cache.invalidate("tenant-1", "client-1");

        assertNull(cache.get("tenant-1", "client-1"));
    }

    @Test
    public void testInvalidateAllClearsAllTokens() {
        cache.put("tenant-A", "client-1", "token-A", 3600L);
        cache.put("tenant-B", "client-2", "token-B", 3600L);
        cache.invalidateAll();

        assertNull(cache.get("tenant-A", "client-1"));
        assertNull(cache.get("tenant-B", "client-2"));
    }

    @Test
    public void testConcurrentAccessIsSafe() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    String tenant = "tenant-" + (idx % 5);
                    String client = "client-" + (idx % 3);
                    cache.put(tenant, client, "token-" + idx, 3600L);
                    String token = cache.get(tenant, client);
                    if (token == null) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertEquals(0, errors.get(), "Concurrent access should not cause errors");
    }

    @Test
    public void testTokenWithSafetyMarginStillValid() {
        // Token expires in 600 seconds (10 min), well above 5-min safety margin
        cache.put("tenant-1", "client-1", "token-valid", 600L);
        assertNotNull(cache.get("tenant-1", "client-1"));
    }

    @Test
    public void testTokenBelowSafetyMarginIsExpired() {
        // Token expires in 200 seconds (~3.3 min), below 5-min safety margin
        cache.put("tenant-1", "client-1", "token-marginal", 200L);
        assertNull(cache.get("tenant-1", "client-1"));
    }
}
