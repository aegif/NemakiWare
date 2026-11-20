/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.archive.RetentionJobResult;
import jp.aegif.nemaki.archive.RetentionScheduler;
import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * Force-archive and the retention job ask the store, not this replica's cache, whether a document is checked out (R341,
 * 3.4.2).
 *
 * <p>R331's first fix asked the series' mark through the cached read. A check-out made on another replica does not reach
 * this replica's version-series cache (the cross-replica invalidation does not drop it, and it lives for an hour), and a
 * series whose mark is cleared while a working copy stays in the store (a restore re-creates the series with its mark
 * cleared; a check-out can fail after writing its working copy and before marking the series) was not seen either: every
 * version was deleted and the working copy lost its series again (measured on nb33 by clearing only the store's mark).
 *
 * <p>What is locked, on the product's {@code ArchiveResource.forceArchive} and {@code RetentionScheduler.archiveDocument},
 * through the product's {@code ContentServiceImpl} and the product's caching {@code ContentDaoServiceImpl} over a real
 * cache: a check-out the store holds and the cache does not is refused (409 / skipped) and nothing is deleted — also by the
 * store's mark alone, with no working copy listed; a working copy in the store whose series' mark is cleared is refused the
 * same; a series the store cannot read deletes nothing (force-archive fails, the retention job counts it failed); a document checked out nowhere is archived as before
 * (the positive control — the refusal is not made by anything else); a check-out the cache still holds after the store has
 * let it go does not refuse. The store below the cache, the cache service (but for its version-series and content caches,
 * which are real) and the content service's delete are stand-ins.
 */
class AnArchiveOfACheckedOutDocumentAsksTheStoreNotTheCacheTest {

    private static final String REPO = "bedroom";
    private static final String DOC = "the-document";
    private static final String SERIES = "series-of-the-document";

    private CacheManager cacheManager;
    private ContentDaoService store;
    private ContentServiceImpl contentService;
    private VersionSeries storesSeries;
    private boolean storeFails;
    private final List<Document> storesVersions = new ArrayList<>();
    private final Document document = new Document();

    private static VersionSeries series(boolean checkedOut) {
        VersionSeries series = new VersionSeries();
        series.setId(SERIES);
        series.setVersionSeriesCheckedOut(checkedOut);
        series.setVersionSeriesCheckedOutBy(checkedOut ? "someone" : null);
        return series;
    }

    private static Document workingCopy() {
        Document pwc = new Document();
        pwc.setId(DOC + "-pwc");
        pwc.setVersionSeriesId(SERIES);
        pwc.setPrivateWorkingCopy(Boolean.TRUE);
        return pwc;
    }

    @BeforeEach
    void wire() {
        cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        Cache<String, Object> heap = cacheManager.createCache("bedroom_versionSeriesCache", CacheConfigurationBuilder
                .newCacheConfigurationBuilder(String.class, Object.class, ResourcePoolsBuilder.heap(100)).build());
        NemakiCache<VersionSeries> versionSeriesCache = new NemakiCache<>(true, heap);
        Cache<String, Object> contentHeap = cacheManager.createCache("bedroom_contentCache", CacheConfigurationBuilder
                .newCacheConfigurationBuilder(String.class, Object.class, ResourcePoolsBuilder.heap(100)).build());
        CacheService repositoryCache = mock(CacheService.class, RETURNS_DEEP_STUBS);
        when(repositoryCache.getVersionSeriesCache()).thenReturn(versionSeriesCache);
        when(repositoryCache.getContentCache()).thenReturn(new NemakiCache<jp.aegif.nemaki.model.Content>(true, contentHeap));
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(REPO)).thenReturn(repositoryCache);

        document.setId(DOC);
        document.setName("D.txt");
        document.setVersionSeriesId(SERIES);
        storesVersions.add(document);
        store = mock(ContentDaoService.class);
        when(store.getDocument(REPO, DOC)).thenReturn(document);
        when(store.getContent(REPO, DOC)).thenReturn(document);
        when(store.getVersionSeries(REPO, SERIES)).thenAnswer(call -> {
            if (storeFails) {
                throw new IllegalStateException("the version lookup failed");
            }
            return storesSeries;
        });
        when(store.getAllVersions(REPO, SERIES)).thenAnswer(call -> new ArrayList<>(storesVersions));

        jp.aegif.nemaki.dao.impl.cached.ContentDaoServiceImpl cachedDao = new jp.aegif.nemaki.dao.impl.cached.ContentDaoServiceImpl();
        cachedDao.setNonCachedContentDaoService(store);
        cachedDao.setNemakiCachePool(pool);
        ContentServiceImpl product = new ContentServiceImpl();
        product.setContentDaoService(cachedDao);
        contentService = spy(product);
        doNothing().when(contentService).deleteDocument(any(), anyString(), anyString(), any(), any());
        poolForTheEntries = pool;
    }

    private NemakiCachePool poolForTheEntries;

    @AfterEach
    void close() {
        cacheManager.close();
    }

    /** The cache reads the series as {@code cached}; the store then holds {@code nowInTheStore}. */
    private void theCacheHoldsThenTheStoreHolds(boolean cached, VersionSeries nowInTheStore) {
        storesSeries = series(cached);
        assertEquals(cached, contentService.getVersionSeries(REPO, SERIES).isVersionSeriesCheckedOut(), "the cache was filled");
        storesSeries = nowInTheStore;
        assertEquals(cached, contentService.getVersionSeries(REPO, SERIES).isVersionSeriesCheckedOut(),
                "the cache still holds what it read (the fixture discriminates the cache from the store)");
    }

    private static HttpServletRequest anAdministratorsRequest() {
        CallContext context = mock(CallContext.class);
        when(context.getUsername()).thenReturn("admin");
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(call -> "CallContext".equals(call.getArgument(0)) ? context : null);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        return request;
    }

    private String forceArchive() {
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(REPO, DOC)).thenReturn(new ReentrantLock());
        ArchiveResource resource = new ArchiveResource();
        resource.setContentService(contentService);
        resource.setThreadLockService(locks);
        resource.setNemakiCachePool(poolForTheEntries);
        return resource.forceArchive(REPO, DOC, anAdministratorsRequest());
    }

    private RetentionJobResult retentionArchive() throws Exception {
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(REPO, DOC)).thenReturn(new ReentrantLock());
        RetentionScheduler scheduler = new RetentionScheduler();
        scheduler.setContentService(contentService);
        scheduler.setThreadLockService(locks);
        scheduler.setNemakiCachePool(poolForTheEntries);
        Method m = RetentionScheduler.class.getDeclaredMethod("archiveDocument", String.class, String.class, String.class,
                RetentionJobResult.class);
        m.setAccessible(true);
        RetentionJobResult result = new RetentionJobResult("local-archive", REPO);
        m.invoke(scheduler, REPO, DOC, "expired", result);
        return result;
    }

    @Test
    @DisplayName("force-archive: a check-out the store holds and the cache does not is answered 409, nothing deleted")
    void forceArchiveRefusesACheckOutTheCacheHasNotSeen() {
        theCacheHoldsThenTheStoreHolds(false, series(true));
        storesVersions.add(workingCopy());
        WebApplicationException refusal = assertThrows(WebApplicationException.class, this::forceArchive,
                "force-archiving a document checked out where this replica's cache has not seen it");
        assertEquals(409, refusal.getResponse().getStatus(), "the status of the refusal");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("force-archive: a working copy in the store whose series' mark is cleared is answered 409, nothing deleted")
    void forceArchiveRefusesAWorkingCopyTheMarkDoesNotName() {
        theCacheHoldsThenTheStoreHolds(false, series(false));
        storesVersions.add(workingCopy());
        WebApplicationException refusal = assertThrows(WebApplicationException.class, this::forceArchive,
                "force-archiving a document whose working copy the mark does not name");
        assertEquals(409, refusal.getResponse().getStatus(), "the status of the refusal");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the retention job: a check-out the store holds and the cache does not is skipped, nothing deleted")
    void theRetentionJobSkipsACheckOutTheCacheHasNotSeen() throws Exception {
        theCacheHoldsThenTheStoreHolds(false, series(true));
        storesVersions.add(workingCopy());
        RetentionJobResult result = retentionArchive();
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(1, result.getSkipped(), "skipped");
        assertEquals(0, result.getSucceeded(), "archived");
    }

    @Test
    @DisplayName("the retention job: a working copy in the store whose series' mark is cleared is skipped, nothing deleted")
    void theRetentionJobSkipsAWorkingCopyTheMarkDoesNotName() throws Exception {
        theCacheHoldsThenTheStoreHolds(false, series(false));
        storesVersions.add(workingCopy());
        RetentionJobResult result = retentionArchive();
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(1, result.getSkipped(), "skipped");
    }

    @Test
    @DisplayName("force-archive: the store's mark alone, with no working copy listed and the cache saying otherwise, is answered 409")
    void forceArchiveRefusesOnTheStoresMarkAlone() {
        theCacheHoldsThenTheStoreHolds(false, series(true));
        WebApplicationException refusal = assertThrows(WebApplicationException.class, this::forceArchive,
                "force-archiving a document the store marks checked out and this replica's cache does not");
        assertEquals(409, refusal.getResponse().getStatus(), "the status of the refusal");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the retention job: the store's mark alone, with no working copy listed and the cache saying otherwise, is skipped")
    void theRetentionJobSkipsOnTheStoresMarkAlone() {
        theCacheHoldsThenTheStoreHolds(false, series(true));
        RetentionJobResult result = assertDoesNotThrow(this::retentionArchive, "the retention job on a document the store marks checked out");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(1, result.getSkipped(), "skipped");
    }

    @Test
    @DisplayName("a series the store cannot read deletes nothing — force-archive fails, the retention job counts it failed")
    void aStoreThatCannotBeReadDeletesNothing() {
        theCacheHoldsThenTheStoreHolds(false, series(false));
        storeFails = true;
        String answer = assertDoesNotThrow(this::forceArchive, "force-archive on a series the store cannot read");
        assertTrue(answer.contains("failure"), "force-archive's answer: " + answer);
        RetentionJobResult result = assertDoesNotThrow(this::retentionArchive, "the retention job on a series the store cannot read");
        assertEquals(1, result.getFailed(), "failed");
        verify(contentService, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a document checked out nowhere is archived by both, as before (the positive control)")
    void aDocumentCheckedOutNowhereIsArchived() {
        theCacheHoldsThenTheStoreHolds(false, series(false));
        String answer = assertDoesNotThrow(this::forceArchive, "force-archive refused a document checked out nowhere");
        assertTrue(answer.contains("success"), "force-archive's answer: " + answer);
        RetentionJobResult result = assertDoesNotThrow(this::retentionArchive, "the retention job failed on a document checked out nowhere");
        assertEquals(1, result.getSucceeded(), "archived by the retention job");
        verify(contentService, org.mockito.Mockito.times(2)).deleteDocument(any(), eq(REPO), eq(DOC), eq(true), eq(false));
    }

    @Test
    @DisplayName("a check-out the cache still holds after the store let it go does not refuse")
    void aCheckOutTheStoreLetGoOfDoesNotRefuse() {
        theCacheHoldsThenTheStoreHolds(true, series(false));
        String answer = assertDoesNotThrow(this::forceArchive, "force-archive refused a check-out the store let go of");
        assertTrue(answer.contains("success"), "force-archive's answer: " + answer);
        RetentionJobResult result = assertDoesNotThrow(this::retentionArchive, "the retention job failed on a check-out the store let go of");
        assertEquals(1, result.getSucceeded(), "archived by the retention job");
        assertFalse(result.getSkipped() > 0, "skipped by the retention job");
    }
}
