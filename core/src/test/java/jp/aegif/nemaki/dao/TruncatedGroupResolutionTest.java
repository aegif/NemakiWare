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
package jp.aegif.nemaki.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An incomplete group membership must be distinguishable from a complete one.
 *
 * <p>Measured before this type existed: a user at the bottom of a 55-level group chain lost
 * access to documents ACL'd to the top of the chain. Search returned 0 hits and getObject
 * returned 403 — consistently, because the authorization gate and the search projection both
 * consume the same resolution. The only trace was a single {@code log.warn} phrased as a
 * suspected cycle, and the truncated answer was cached, so it stayed wrong.
 */
class TruncatedGroupResolutionTest {

    @Test
    @DisplayName("打ち切りは通常のリストと型で区別でき、到達できたぶんは保持される")
    void aTruncatedResolutionIsDistinguishableAndKeepsWhatItReached() {
        List<String> reached = Arrays.asList("g1", "g2", "g3");
        TruncatedGroupResolution truncated = new TruncatedGroupResolution(reached, 50);

        assertTrue(TruncatedGroupResolution.isTruncated(truncated));
        assertFalse(TruncatedGroupResolution.isTruncated(new ArrayList<>(reached)),
                "an ordinary list must not be mistaken for a truncated one");
        assertEquals(reached, truncated, "the groups that WERE reached are still usable");
        assertEquals(50, truncated.getLimit());
    }

    @Test
    @DisplayName("null や無関係な型を渡しても打ち切り扱いしない")
    void isTruncatedIsNullSafe() {
        assertFalse(TruncatedGroupResolution.isTruncated(null));
        assertFalse(TruncatedGroupResolution.isTruncated("g1"));
    }

    @Test
    @DisplayName("打ち切り回数が数えられる (管理エンドポイントで露出する値)")
    void truncationsAreCounted() {
        long before = TruncatedGroupResolution.truncationCount();
        new TruncatedGroupResolution(Arrays.asList("g1"), 50);
        new TruncatedGroupResolution(Arrays.asList("g2"), 50);
        assertEquals(before + 2, TruncatedGroupResolution.truncationCount());
    }

    /**
     * The signal is worthless if the caching layer memoises it anyway. This asserts the wiring
     * rather than the type, because the type cannot enforce how callers treat it.
     */
    @Test
    @DisplayName("cached DAO が打ち切り結果を joinedGroupCache に入れないこと")
    void theCachingLayerRefusesToMemoiseATruncatedResolution() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java"),
                StandardCharsets.UTF_8);
        int start = src.indexOf("public List<String> getJoinedGroupByUserId");
        assertTrue(start > 0, "getJoinedGroupByUserId not found — this test needs updating");
        String method = src.substring(start, src.indexOf("\n\t}", start));

        int guard = method.indexOf("TruncatedGroupResolution.isTruncated");
        int put = method.indexOf("joinedGroupCache.put");
        assertTrue(guard > 0,
                "the cached layer must check for a truncated resolution before caching it");
        assertTrue(guard < put,
                "the truncation check must come BEFORE the cache put, or an incomplete"
                        + " membership becomes a durable authorization error");
    }
}
