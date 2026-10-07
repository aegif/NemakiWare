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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

/**
 * The CACHING layer never converts a lookup failure back into "does not exist".
 *
 * <h2>The re-flattening this pins</h2>
 *
 * <p>Round 32 made the couch layer throw for failures and keep null strictly for absence — and
 * the round-33 review found that the cached decorator, the only wired path every consumer
 * uses, caught that throw and returned null. On a cache miss during a CouchDB hiccup,
 * deleteObject skipped "already deleted" and reported success, user/group deletion finished
 * over an unstripped membership, and the lineage reconciler marked live folders ORPHAN. The
 * same shape sat in every {@code *Fresh} sibling. This class drives the CACHED implementation
 * with a throwing delegate — the layer the earlier test could not see.
 */
class CachedLookupFailuresAreNotAbsenceTest {

    private static final String REPO = "bedroom";

    private ContentDaoService delegate;
    private ContentDaoServiceImpl cached;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(ContentDaoService.class);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        CacheService cacheService = mock(CacheService.class);
        NemakiCache<Content> contentCache = mock(NemakiCache.class);
        when(pool.get(REPO)).thenReturn(cacheService);
        when(cacheService.getContentCache()).thenReturn(contentCache);
        when(contentCache.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        cached = new ContentDaoServiceImpl();
        cached.setNonCachedContentDaoService(delegate);
        cached.setNemakiCachePool(pool);
    }

    @Test
    @DisplayName("a cache miss over a failing store throws — not 'the object is gone'")
    void aCacheMissOverAFailureThrows() {
        when(delegate.getContent(REPO, "doc-1"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getContent(REPO, "doc-1"),
                "the cache layer flattened the store's refusal back into null, and "
                        + "deleteObject reported success over an object that still exists");
    }

    @Test
    @DisplayName("an unwired cache pool refuses — the door directly above the fixed catch")
    void anUnwiredCachePoolRefuses() {
        // The round-34 review's point: the catch was closed while the two wiring arms
        // immediately above it still answered null — on the MAINLINE read, which is what
        // deleteObject, the lineage reconciler and the principal-strip actually call.
        ContentDaoServiceImpl unwired = new ContentDaoServiceImpl();
        unwired.setNonCachedContentDaoService(delegate);

        assertThrows(IllegalStateException.class,
                () -> unwired.getContent(REPO, "doc-1"),
                "a misconfiguration answered 'the object does not exist'");
    }

    @Test
    @DisplayName("an unwired delegate refuses — the twin arm")
    void anUnwiredDelegateRefuses() {
        ContentDaoServiceImpl unwired = new ContentDaoServiceImpl();
        NemakiCachePool pool = mock(NemakiCachePool.class);
        CacheService cacheService = mock(CacheService.class);
        @SuppressWarnings("unchecked")
        NemakiCache<Content> contentCache = mock(NemakiCache.class);
        when(pool.get(REPO)).thenReturn(cacheService);
        when(cacheService.getContentCache()).thenReturn(contentCache);
        unwired.setNemakiCachePool(pool);

        assertThrows(IllegalStateException.class,
                () -> unwired.getContent(REPO, "doc-1"));
    }

    @Test
    @DisplayName("a genuine absence is still null through the cache — the control")
    void aGenuineAbsenceIsStillNull() {
        when(delegate.getContent(REPO, "doc-2")).thenReturn(null);

        assertNull(cached.getContent(REPO, "doc-2"));
    }

    @Test
    @DisplayName("getFolder propagates the refusal instead of answering 'no folder'")
    void getFolderPropagatesTheRefusal() {
        when(delegate.getContent(REPO, "folder-1"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getFolder(REPO, "folder-1"));
    }

    @Test
    @DisplayName("getDocumentFresh propagates — the Fresh siblings had the same catch")
    void getDocumentFreshPropagates() {
        when(delegate.getDocument(REPO, "doc-3"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getDocumentFresh(REPO, "doc-3"));
    }

    @Test
    @DisplayName("getFolderFresh propagates")
    void getFolderFreshPropagates() {
        when(delegate.getFolder(REPO, "folder-2"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getFolderFresh(REPO, "folder-2"));
    }

    @Test
    @DisplayName("getRelationshipFresh propagates")
    void getRelationshipFreshPropagates() {
        when(delegate.getRelationship(REPO, "rel-1"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getRelationshipFresh(REPO, "rel-1"));
    }

    @Test
    @DisplayName("getPolicyFresh propagates")
    void getPolicyFreshPropagates() {
        when(delegate.getPolicy(REPO, "pol-1"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getPolicyFresh(REPO, "pol-1"));
    }

    @Test
    @DisplayName("getItemFresh propagates")
    void getItemFreshPropagates() {
        when(delegate.getItem(REPO, "item-1"))
                .thenThrow(new IllegalStateException("could not be read"));

        assertThrows(IllegalStateException.class,
                () -> cached.getItemFresh(REPO, "item-1"));
    }

    @Test
    @DisplayName("getGroupItemByIdFresh refuses on failure — the principal-delete input")
    void getGroupItemByIdFreshRefuses() {
        when(delegate.getGroupItemByIdFresh(REPO, "group-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> cached.getGroupItemByIdFresh(REPO, "group-1"),
                "a failed fresh read answered 'no such group', and the membership-update "
                        + "retry loop silently skipped the change while reporting success");
    }
}
