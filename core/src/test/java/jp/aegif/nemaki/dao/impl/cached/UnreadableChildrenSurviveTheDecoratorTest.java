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

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.model.Tree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The count of children that could not be read has to survive the layer the container wires.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code daoContext.xml} binds {@code contentDaoService} to the CACHED decorator, with the
 * CouchDB implementation injected underneath it. So every caller — including the fixity scan,
 * which records a verdict about "everything under this folder" into an append-only chain — asks
 * the decorator, and the decorator did not implement {@code lastUnreadableChildCount()}. The
 * interface default answered 0.
 *
 * <p>That made the whole correction dead in production while its own tests stayed green: the
 * store test drove the CouchDB class directly, and the controller test stubbed
 * {@code ContentService}. Neither went through the object the container actually builds.
 *
 * <p>Worse on the cached path: with the tree cache enabled the decorator never calls the store
 * at all. It walks the tree's child ids and silently skips any whose document does not come
 * back — the same substitution, in a loop the store's counter cannot see.
 *
 * <p>Both branches are driven here, because they lose the count for different reasons and a
 * test naming one of them passes while the other stays broken.
 */
class UnreadableChildrenSurviveTheDecoratorTest {

    private static final String REPO = "bedroom";

    @Test
    @DisplayName("the delegating branch carries the store's count out of the decorator")
    void theDelegatingBranchCarriesTheCount() throws Exception {
        ContentDaoService store = mock(ContentDaoService.class);
        when(store.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(store.lastUnreadableChildCount()).thenReturn(3);

        ContentDaoServiceImpl decorator = decoratorOver(store, false, Set.of());

        assertEquals(List.of(), decorator.getChildren(REPO, "f-1"));
        assertEquals(3, decorator.lastUnreadableChildCount(),
                "the store counted children it could not read and the decorator answered with "
                        + "the interface default, so every caller in a real deployment sees 0");
    }

    @Test
    @DisplayName("a tree entry whose document is gone sends the read back to the store")
    void aStaleTreeEntrySendsTheReadBackToTheStore() throws Exception {
        // The first version of this counted such a child as UNREADABLE. It cannot: this path
        // cannot tell "the document could not be read" from "the child was deleted since the
        // tree was cached", and the fixity scan turns a non-zero count into status "partial"
        // AND into the scope string it writes to the APPEND-ONLY chain. Guessing "unreadable"
        // stamped a permanent claim of incompleteness on a folder that was merely stale here.
        //
        // The store can tell them apart — a deleted child is not in the view, an undecodable
        // row is counted — so a disagreement between cache and database sends the read there.
        ContentDaoService store = mock(ContentDaoService.class);
        when(store.getContent(anyString(), anyString())).thenReturn(null);
        when(store.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(store.lastUnreadableChildCount()).thenReturn(0);

        ContentDaoServiceImpl decorator = decoratorOver(store, true, Set.of("child-1", "child-2"));

        assertEquals(List.of(), decorator.getChildren(REPO, "f-1"));
        assertEquals(0, decorator.lastUnreadableChildCount(),
                "a stale cache entry was reported as an unreadable child, which the fixity "
                        + "scan writes into the chain as a permanent claim of incompleteness");
        org.mockito.Mockito.verify(store).getChildren(REPO, "f-1");
    }

    @Test
    @DisplayName("a COLD tree cache keeps the count the store took while building it")
    void aColdTreeCacheKeepsTheStoresCount() throws Exception {
        // Building the tree calls the store, which counts what it could not decode — and the
        // tree then simply does not contain those children. Without carrying the number out,
        // the first read after a restart answers 0 for a folder the store had just reported
        // losses on, and only the SECOND read (warm) would be right. It is the first read a
        // scheduled fixity pass makes.
        ContentDaoService store = mock(ContentDaoService.class);
        when(store.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(store.lastUnreadableChildCount()).thenReturn(4);

        ContentDaoServiceImpl decorator = decoratorOver(store, true, null);

        assertEquals(List.of(), decorator.getChildren(REPO, "f-1"));
        assertEquals(4, decorator.lastUnreadableChildCount(),
                "the count the store took while the tree was being built was discarded");
    }

    @Test
    @DisplayName("a warm hit does not report the store's counter — the control")
    void aFullyReadFolderCountsNone() throws Exception {
        // Without this, answering non-zero always would satisfy both tests above and every
        // fixity scan would report itself partial.
        Document child = new Document();
        child.setId("child-1");
        ContentDaoService store = mock(ContentDaoService.class);
        when(store.getContent(anyString(), anyString())).thenReturn(child);
        // STUBBED TO A NON-ZERO VALUE ON PURPOSE. This is the only case that takes the WARM
        // path, where the tree is already cached and the store is never called — so whatever
        // the decorator reads from the store belongs to a DIFFERENT call, possibly a different
        // folder in a different request on the same pooled thread. Leaving it unstubbed let
        // Mockito's default 0 satisfy the assertion, and the test passed because of its
        // fixture rather than because of the code.
        when(store.lastUnreadableChildCount()).thenReturn(7);

        ContentDaoServiceImpl decorator = decoratorOver(store, true, Set.of("child-1"));

        assertEquals(1, decorator.getChildren(REPO, "f-1").size());
        assertEquals(0, decorator.lastUnreadableChildCount(),
                "a warm hit reported the STORE's counter, which belongs to a different call — "
                        + "possibly a different folder in a different request on this thread: "
                        + decorator.lastUnreadableChildCount());
    }

    @Test
    @DisplayName("a warm hit reports what the BUILD found, not 'unknown' and not the store's")
    void aWarmHitReportsWhatTheBuildFound() throws Exception {
        // A cache HIT is the ordinary state of a working cache, not a failure. The first
        // version answered -1 ("unknown") on every hit, which is honest and is also an outage:
        // external ingest refuses on a non-zero count, so any folder listed twice stopped
        // accepting imports, and the fixity scan wrote folder-children-uncounted into the
        // append-only chain on every pass.
        //
        // Both halves are asserted: a tree built from a LOSSY read still reports the loss on
        // later hits, and a tree built from a clean read reports zero. Only one of those makes
        // the outage go away; only the other keeps the protection.
        Document child = new Document();
        child.setId("child-1");
        ContentDaoService store = mock(ContentDaoService.class);
        when(store.getContent(anyString(), anyString())).thenReturn(child);
        when(store.getChildren(REPO, "f-1")).thenReturn(List.of(child));
        when(store.lastUnreadableChildCount()).thenReturn(5);

        // Cold: the tree is built here, from the store, and takes its count.
        ContentDaoServiceImpl decorator = decoratorOver(store, true, null);
        decorator.getChildren(REPO, "f-1");
        assertEquals(5, decorator.lastUnreadableChildCount(),
                "the count the store took while the tree was built was not carried");

        // Warm: the same tree, no store call — and the loss must still be reported.
        Tree built = new Tree("f-1");
        built.add("child-1");
        built.setUnreadableAtBuild(5);
        ContentDaoServiceImpl warm = decoratorOverTree(store, built);
        warm.getChildren(REPO, "f-1");
        assertEquals(5, warm.lastUnreadableChildCount(),
                "a warm hit forgot what the build had found, so a permanently short listing "
                        + "reads as a whole folder");
    }

    private static ContentDaoServiceImpl decoratorOverTree(ContentDaoService store, Tree tree)
            throws Exception {
        ContentDaoServiceImpl decorator = new ContentDaoServiceImpl();
        set(decorator, "nonCachedContentDaoService", store);
        @SuppressWarnings("unchecked")
        NemakiCache<Tree> treeCache = mock(NemakiCache.class);
        when(treeCache.isCacheEnabled()).thenReturn(true);
        when(treeCache.get(anyString())).thenReturn(tree);
        @SuppressWarnings("unchecked")
        NemakiCache<jp.aegif.nemaki.model.Content> contentCache = mock(NemakiCache.class);
        when(contentCache.isCacheEnabled()).thenReturn(false);
        when(contentCache.get(anyString())).thenReturn(null);
        CacheService cache = mock(CacheService.class);
        when(cache.getTreeCache()).thenReturn(treeCache);
        when(cache.getContentCache()).thenReturn(contentCache);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(anyString())).thenReturn(cache);
        set(decorator, "nemakiCachePool", pool);
        return decorator;
    }

    private static ContentDaoServiceImpl decoratorOver(ContentDaoService store,
            boolean treeCacheEnabled, Set<String> treeChildren) throws Exception {
        ContentDaoServiceImpl decorator = new ContentDaoServiceImpl();
        set(decorator, "nonCachedContentDaoService", store);

        @SuppressWarnings("unchecked")
        NemakiCache<Tree> treeCache = mock(NemakiCache.class);
        when(treeCache.isCacheEnabled()).thenReturn(treeCacheEnabled);
        // A NULL treeChildren means "the cache is cold": treeCache.get returns nothing and the
        // decorator builds the tree from the store. The earlier fixture always stubbed a tree,
        // so the cold path — the one a restarted node takes — was never driven.
        if (treeCacheEnabled && treeChildren != null) {
            Tree tree = new Tree("f-1");
            for (String childId : treeChildren) {
                tree.add(childId);
            }
            when(treeCache.get(anyString())).thenReturn(tree);
        }
        // The decorator's own getContent goes through the CONTENT cache before the store, so
        // the fixture has to provide one or every lookup returns null and the "fully read"
        // control could never pass — a fixture that makes the control vacuous, which is how a
        // pair of tests ends up asserting nothing in opposite directions.
        @SuppressWarnings("unchecked")
        NemakiCache<jp.aegif.nemaki.model.Content> contentCache = mock(NemakiCache.class);
        when(contentCache.isCacheEnabled()).thenReturn(false);
        when(contentCache.get(anyString())).thenReturn(null);
        CacheService cache = mock(CacheService.class);
        when(cache.getTreeCache()).thenReturn(treeCache);
        when(cache.getContentCache()).thenReturn(contentCache);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(anyString())).thenReturn(cache);
        set(decorator, "nemakiCachePool", pool);
        return decorator;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
