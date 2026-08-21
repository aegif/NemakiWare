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
        // It describes THIS read, not the configuration. Configuration IS persisted (create /
        // update go through CloudantClientWrapper), so a plain field would be written to CouchDB
        // and then read back as a permanent property of the stored document.
        //
        // Serialised with the SAME mapper the product persists with — the couchdbObjectMapper
        // bean, which is Jackson 3 (tools.jackson.databind). A Jackson 2 mapper here would be a
        // different serialiser answering a question about a path it is not on.
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);

        String json = new jp.aegif.nemaki.config.JacksonConfig()
                .couchdbObjectMapper().writeValueAsString(failed);

        org.junit.jupiter.api.Assertions.assertFalse(json.contains("loadFailed"), json);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("configuration"),
                "control: the mapper does serialise this model, so the absence above is the "
                        + "annotation working rather than the mapper emitting nothing. " + json);
    }
}
