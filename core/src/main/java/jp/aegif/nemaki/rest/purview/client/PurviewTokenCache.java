package jp.aegif.nemaki.rest.purview.client;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class PurviewTokenCache {

    private static final long SAFETY_MARGIN_SECONDS = 300L;

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public void put(String tenantId, String clientId, String accessToken, long expiresInSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds).minusSeconds(SAFETY_MARGIN_SECONDS);
        cache.put(cacheKey(tenantId, clientId), new CachedToken(accessToken, expiresAt));
    }

    public String get(String tenantId, String clientId) {
        CachedToken cached = cache.get(cacheKey(tenantId, clientId));
        if (cached == null || Instant.now().isAfter(cached.expiresAt())) {
            return null;
        }
        return cached.accessToken();
    }

    public void invalidate(String tenantId, String clientId) {
        cache.remove(cacheKey(tenantId, clientId));
    }

    public void invalidateAll() {
        cache.clear();
    }

    private static String cacheKey(String tenantId, String clientId) {
        return tenantId + "|" + clientId;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}
}
