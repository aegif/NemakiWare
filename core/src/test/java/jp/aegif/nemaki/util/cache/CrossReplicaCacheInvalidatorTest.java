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
package jp.aegif.nemaki.util.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

/**
 * A permission change on one replica has to reach the others, and must not cost anything on the
 * replica that made it.
 */
class CrossReplicaCacheInvalidatorTest {

    private static final String REPO = "repo";

    private AtomicInteger aclCleared;
    private AtomicInteger contentCleared;
    private AtomicInteger userCleared;
    private AtomicInteger groupCleared;
    private AtomicInteger joinedCleared;
    private NemakiCachePool pool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        AclCacheGeneration.resetForTests();
        PrincipalGeneration.resetForTests();
        aclCleared = new AtomicInteger();
        contentCleared = new AtomicInteger();
        userCleared = new AtomicInteger();
        groupCleared = new AtomicInteger();
        joinedCleared = new AtomicInteger();

        NemakiCache<Acl> aclCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(i -> aclCleared.incrementAndGet()).when(aclCache).removeAll();
        NemakiCache<Content> contentCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(i -> contentCleared.incrementAndGet()).when(contentCache).removeAll();
        NemakiCache<UserItem> userCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(i -> userCleared.incrementAndGet()).when(userCache).removeAll();
        NemakiCache<GroupItem> groupCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(i -> groupCleared.incrementAndGet()).when(groupCache).removeAll();
        NemakiCache<List<String>> joinedCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(i -> joinedCleared.incrementAndGet()).when(joinedCache).removeAll();

        CacheService cache = mock(CacheService.class);
        when(cache.getAclCache()).thenReturn(aclCache);
        when(cache.getContentCache()).thenReturn(contentCache);
        when(cache.getUserItemCache()).thenReturn(userCache);
        when(cache.getGroupItemCache()).thenReturn(groupCache);
        when(cache.getJoinedGroupCache()).thenReturn(joinedCache);

        pool = mock(NemakiCachePool.class);
        when(pool.get(anyString())).thenReturn(cache);
    }

    /** A store whose returned high-watermarks the test controls. */
    private static final class FakeStore implements CrossReplicaCacheInvalidator.GenerationStore {
        long acl;
        long principal;

        @Override
        public CrossReplicaCacheInvalidator.Generations publishAndRead(String repositoryId,
                long localAcl, long localPrincipal) {
            return new CrossReplicaCacheInvalidator.Generations(Math.max(acl, localAcl),
                    Math.max(principal, localPrincipal));
        }

        @Override
        public Collection<String> repositoryIds() {
            return List.of(REPO);
        }
    }

    @Test
    @DisplayName("他レプリカの ACL 変更を検知して ACL/content キャッシュを落とす")
    void anAclChangeElsewhereClearsTheAclCaches() {
        FakeStore store = new FakeStore();
        CrossReplicaCacheInvalidator inv = new CrossReplicaCacheInvalidator(pool, store);

        inv.pollOnce();
        assertEquals(0, aclCleared.get(), "nothing has changed yet");

        store.acl = 7; // another replica advanced it
        inv.pollOnce();
        assertEquals(1, aclCleared.get());
        assertEquals(1, contentCleared.get());
        assertEquals(0, userCleared.get(),
                "an ACL change must not drop the principal caches — different counter,"
                        + " different invalidation");
    }

    @Test
    @DisplayName("他レプリカのユーザ/グループ変更を検知して principal キャッシュを落とす")
    void aPrincipalChangeElsewhereClearsThePrincipalCaches() {
        FakeStore store = new FakeStore();
        CrossReplicaCacheInvalidator inv = new CrossReplicaCacheInvalidator(pool, store);

        store.principal = 3;
        inv.pollOnce();
        assertEquals(1, userCleared.get());
        assertEquals(1, groupCleared.get());
        assertEquals(1, joinedCleared.get());
        assertEquals(0, aclCleared.get(),
                "a password or membership change must not drop the effective-ACL cache");
    }

    @Test
    @DisplayName("自分が起こした変更では落とさない (書き込みのたびに全落としにしない)")
    void ownWritesDoNotTriggerAClear() {
        FakeStore store = new FakeStore();
        CrossReplicaCacheInvalidator inv = new CrossReplicaCacheInvalidator(pool, store);

        // This replica performs the ACL change; its own eviction already ran precisely.
        AclCacheGeneration.advance(REPO);
        AclCacheGeneration.advance(REPO);
        inv.pollOnce();

        assertEquals(0, aclCleared.get(),
                "reacting to our own writes would clear the whole repository's caches on every"
                        + " ACL write — worst exactly during the bulk changes this must survive");
    }

    @Test
    @DisplayName("同じ世代を二度見ても二度は落とさない")
    void aGenerationIsActedOnOnce() {
        FakeStore store = new FakeStore();
        CrossReplicaCacheInvalidator inv = new CrossReplicaCacheInvalidator(pool, store);

        store.acl = 5;
        inv.pollOnce();
        inv.pollOnce();
        inv.pollOnce();
        assertEquals(1, aclCleared.get(), "the poll is level-triggered, not edge-repeated");
    }

    @Test
    @DisplayName("ストアが落ちてもポーラは死なない (静かに無界へ戻らない)")
    void aStoreFailureDoesNotKillThePoller() {
        CrossReplicaCacheInvalidator inv = new CrossReplicaCacheInvalidator(pool,
                new CrossReplicaCacheInvalidator.GenerationStore() {
                    @Override
                    public CrossReplicaCacheInvalidator.Generations publishAndRead(String r,
                            long a, long p) {
                        throw new IllegalStateException("CouchDB unavailable");
                    }

                    @Override
                    public Collection<String> repositoryIds() {
                        return List.of(REPO);
                    }
                });
        inv.pollOnce(); // must not throw
        assertTrue(true);
    }
}
