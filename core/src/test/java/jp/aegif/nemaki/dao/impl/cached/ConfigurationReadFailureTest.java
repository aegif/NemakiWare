/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.dao.impl.cached;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A configuration read that FAILED must not be cached as if it were empty.
 *
 * <p>{@code getConfiguration} catches every exception and returns an empty object, so a moment
 * when CouchDB was unreachable looks identical to "nothing is configured". The cached layer then
 * stored that empty object — and {@code configCache} has no expiry at all
 * ({@code ExpiryPolicyBuilder.noExpiration()}), so it was never re-read. One unreachable moment
 * during startup therefore made every dynamic setting read as absent for the rest of that JVM's
 * life, with no signal (external review).
 *
 * <p>{@code lineage.mode} is the sharp case: its startup default is {@code disabled}, and
 * {@code disabled} is the one answer the ingest path treats as benign and reports nothing about.
 */
class ConfigurationReadFailureTest {

    private ContentDaoServiceImpl cached;
    private jp.aegif.nemaki.dao.ContentDaoService nonCached;
    private NemakiCache<Configuration> configCache;

    @SuppressWarnings("unchecked")
    private void wire() {
        cached = new ContentDaoServiceImpl();
        nonCached = mock(jp.aegif.nemaki.dao.ContentDaoService.class);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        CacheService cacheService = mock(CacheService.class);
        configCache = mock(NemakiCache.class);
        when(pool.get(anyString())).thenReturn(cacheService);
        when(cacheService.getConfigCache()).thenReturn(configCache);
        cached.setNonCachedContentDaoService(nonCached);
        cached.setNemakiCachePool(pool);
    }

    @Test
    @DisplayName("a failed read is returned but never cached")
    void failedReadIsNotCached() {
        wire();
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(failed);

        Configuration result = cached.getConfiguration("bedroom");

        assertNotNull(result, "the caller still gets an answer — it is just not a durable one");
        verify(configCache, never()).put(any(), any());
    }

    @Test
    @DisplayName("a genuinely empty configuration is still cached")
    void successfulEmptyReadIsCached() {
        // The distinction has to cut both ways: "nothing configured" is a real answer and caching
        // it is the whole point of the cache.
        wire();
        Configuration empty = new Configuration();
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(empty);

        cached.getConfiguration("bedroom");

        verify(configCache).put("configuration", empty);
    }

    @Test
    @DisplayName("a populated configuration is cached")
    void populatedReadIsCached() {
        wire();
        Configuration populated = new Configuration();
        populated.setConfiguration(new java.util.HashMap<>(Map.of("lineage.mode", "journaled")));
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(populated);

        assertEquals("journaled",
                cached.getConfiguration("bedroom").getConfiguration().get("lineage.mode"));
        verify(configCache).put("configuration", populated);
    }

    @Test
    @DisplayName("a null read is passed through, not cached")
    void nullReadIsPassedThrough() {
        wire();
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(null);

        assertNull(cached.getConfiguration("bedroom"));
        verify(configCache, never()).put(any(), any());
    }

    @Test
    @DisplayName("the marker never reaches the stored document")
    void markerIsNotPersisted() throws Exception {
        // It describes THIS read, not the configuration, and must not be written to CouchDB.
        //
        // Serialised through the object the write path ACTUALLY persists. Configuration itself
        // never reaches the mapper: both couch/ContentDaoServiceImpl.create and .update convert
        // to CouchConfiguration first. An earlier version of this test serialised Configuration
        // with couchdbObjectMapper and claimed to have checked "the same mapper the product
        // uses" — the right mapper, on a path that object is not on (external review).
        Configuration failed = new Configuration();
        failed.setConfiguration(new java.util.HashMap<>(Map.of("lineage.mode", "journaled")));
        failed.setLoadFailed(true);

        String json = new jp.aegif.nemaki.config.JacksonConfig().couchdbObjectMapper()
                .writeValueAsString(new jp.aegif.nemaki.model.couch.CouchConfiguration(
                        failed));

        org.junit.jupiter.api.Assertions.assertFalse(json.contains("loadFailed"), json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("journaled"),
                "control: the settings themselves ARE written, so the absence above is the marker "
                        + "being excluded rather than the document coming out empty. " + json);

        // And the model itself, for any future path that serialises it directly. This is what
        // @JsonIgnore guards: the couchdb mapper sets field visibility to ANY and does not
        // propagate the transient marker, so the annotation is load-bearing, not decorative.
        String direct = new jp.aegif.nemaki.config.JacksonConfig().couchdbObjectMapper()
                .writeValueAsString(failed);
        org.junit.jupiter.api.Assertions.assertFalse(direct.contains("loadFailed"), direct);
        org.junit.jupiter.api.Assertions.assertTrue(direct.contains("journaled"), direct);
    }

    // ── The cost of not caching a failure ──────────────────────────────────────────────────

    @Test
    @DisplayName("a failed read is not re-issued on every call")
    void failedReadIsRateLimited() {
        // This method is reached by PropertyManager.readValue for every key that is not a -D or
        // an env var, which puts it under AuthenticationFilter (per request), CompileServiceImpl
        // (per object) and TypeManagerImpl (~100 keys in one method). Refusing to cache a failure
        // without bounding the retry turned a brief outage into one Mango _find per property
        // read, with no backoff and no client-side timeout (external review).
        wire();
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(failed);

        for (int i = 0; i < 50; i++) {
            assertNotNull(cached.getConfiguration("bedroom"));
        }

        verify(nonCached, times(1)).getConfiguration("bedroom");
        verify(configCache, never()).put(any(), any());
    }

    @Test
    @DisplayName("the cooldown is per repository, so one repository's outage does not mute another")
    void cooldownIsPerRepository() {
        wire();
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(failed);
        when(nonCached.getConfiguration("canopy")).thenReturn(failed);

        cached.getConfiguration("bedroom");
        cached.getConfiguration("bedroom");
        cached.getConfiguration("canopy");

        verify(nonCached, times(1)).getConfiguration("bedroom");
        verify(nonCached, times(1)).getConfiguration("canopy");
    }

    @Test
    @DisplayName("the cooldown expires rather than becoming the new eternal cache")
    void cooldownExpires() throws Exception {
        // The whole defect being fixed was a failure that outlived the outage. A cooldown that
        // never lapsed would be the same bug with a shorter name.
        wire();
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(failed);

        cached.getConfiguration("bedroom");
        expireCooldown();
        cached.getConfiguration("bedroom");

        verify(nonCached, times(2)).getConfiguration("bedroom");
    }

    @Test
    @DisplayName("recovery is not delayed past the cooldown, and the good value is cached")
    void recoveryIsPickedUp() {
        wire();
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        Configuration good = new Configuration();
        good.setConfiguration(new java.util.HashMap<>(Map.of("lineage.mode", "journaled")));
        when(configCache.get("configuration")).thenReturn(null);
        when(nonCached.getConfiguration("bedroom")).thenReturn(failed, good);

        cached.getConfiguration("bedroom");
        expireCooldown();

        assertEquals("journaled",
                cached.getConfiguration("bedroom").getConfiguration().get("lineage.mode"));
        verify(configCache).put("configuration", good);
    }

    /** Winds every recorded failure past its expiry without sleeping for the real cooldown. */
    @SuppressWarnings("unchecked")
    private void expireCooldown() {
        try {
            java.lang.reflect.Field f = ContentDaoServiceImpl.class
                    .getDeclaredField("recentConfigurationFailures");
            f.setAccessible(true);
            java.util.concurrent.ConcurrentMap<String, Object> map =
                    (java.util.concurrent.ConcurrentMap<String, Object>) f.get(cached);
            org.junit.jupiter.api.Assertions.assertFalse(map.isEmpty(),
                    "nothing was recorded, so expiring it proves nothing");
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object rec = e.getValue();
                java.lang.reflect.Method conf = rec.getClass().getDeclaredMethod("configuration");
                conf.setAccessible(true);
                java.lang.reflect.Constructor<?> ctor = rec.getClass().getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                map.put(e.getKey(), ctor.newInstance(conf.invoke(rec), 0L));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
