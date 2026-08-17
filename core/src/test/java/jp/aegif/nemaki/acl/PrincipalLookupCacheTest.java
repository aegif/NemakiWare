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
package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.cache.PrincipalGeneration;

/**
 * Memoising principal kind is only safe if it cannot outlive a principal change and cannot cache
 * an outage.
 */
class PrincipalLookupCacheTest {

    private static final String REPO = "repo";

    @BeforeEach
    void reset() {
        PrincipalLookupCache.invalidateAll();
        PrincipalGeneration.resetForTests();
    }

    @Test
    @DisplayName("同じ principal の 2 回目は view を叩かない (これが伝播コストの主因だった)")
    void aRepeatedLookupIsServedFromTheMemo() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            PrincipalLookupCache.get(REPO, "user", "alice", () -> {
                loads.incrementAndGet();
                return PrincipalLookup.FOUND;
            });
        }
        assertEquals(1, loads.get(),
                "one lookup per ACE per node was the measured cost; the whole point is to pay it"
                        + " once per traversal");
    }

    @Test
    @DisplayName("UNAVAILABLE は絶対に保存しない (障害を答えに変えない)")
    void anOutageIsNeverMemoised() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            PrincipalLookup r = PrincipalLookupCache.get(REPO, "user", "bob", () -> {
                loads.incrementAndGet();
                return PrincipalLookup.UNAVAILABLE;
            });
            assertSame(PrincipalLookup.UNAVAILABLE, r);
        }
        assertEquals(3, loads.get(),
                "caching UNAVAILABLE would replay an outage as an answer, and the caller turns an"
                        + " un-served lookup into a refusal to project — a cached one would make"
                        + " that refusal permanent");
    }

    @Test
    @DisplayName("null を返す resolver も保存しない")
    void aNullOutcomeIsNeverMemoised() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            PrincipalLookupCache.get(REPO, "group", "g1", () -> {
                loads.incrementAndGet();
                return null;
            });
        }
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("principal の変更で memo が一斉に無効になる (TTL を待たない)")
    void aPrincipalChangeInvalidatesEveryEntryAtOnce() {
        AtomicInteger loads = new AtomicInteger();
        PrincipalLookupCache.get(REPO, "user", "alice", () -> {
            loads.incrementAndGet();
            return PrincipalLookup.FOUND;
        });
        assertEquals(1, loads.get());

        PrincipalGeneration.advance(REPO); // a user or group was created / updated / deleted

        PrincipalLookupCache.get(REPO, "user", "alice", () -> {
            loads.incrementAndGet();
            return PrincipalLookup.NOT_FOUND;
        });
        assertEquals(2, loads.get(),
                "a deletion is a revocation; it must not have to wait out a TTL");
    }

    @Test
    @DisplayName("NOT_FOUND も記憶する (存在しない principal の繰り返し照会も安くなる)")
    void anAbsentPrincipalIsMemoisedToo() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            PrincipalLookup r = PrincipalLookupCache.get(REPO, "user", "ghost", () -> {
                loads.incrementAndGet();
                return PrincipalLookup.NOT_FOUND;
            });
            assertSame(PrincipalLookup.NOT_FOUND, r);
        }
        assertEquals(1, loads.get());
    }

    @Test
    @DisplayName("user と group と repository でキーが混ざらない")
    void keysDoNotCollideAcrossKindOrRepository() {
        AtomicInteger loads = new AtomicInteger();
        java.util.function.Supplier<PrincipalLookup> found = () -> {
            loads.incrementAndGet();
            return PrincipalLookup.FOUND;
        };
        PrincipalLookupCache.get(REPO, "user", "x", found);
        PrincipalLookupCache.get(REPO, "group", "x", found);
        PrincipalLookupCache.get("other", "user", "x", found);
        assertEquals(3, loads.get(), "a user named x is not a group named x");
    }
}
