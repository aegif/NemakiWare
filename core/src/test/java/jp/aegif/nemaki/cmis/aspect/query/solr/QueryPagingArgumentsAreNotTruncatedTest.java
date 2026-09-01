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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A query's paging arguments are never TRUNCATED into a different query.
 *
 * <h2>Why Math.max(0, ...) was not the guard it looked like</h2>
 *
 * <p>The query pager read {@code Math.max(0, maxItems.intValue())}, which bounds the RESULT of
 * the truncation rather than preventing it: {@code intValue()} keeps the low 32 bits first, so
 * 2^32 arrives as 0, {@code Math.max} happily returns 0, and the query answers with an empty
 * page. The children listing and the type listing were fixed for exactly this; the query was
 * the third reader of the same trap.
 */
class QueryPagingArgumentsAreNotTruncatedTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java";

    private static int clamp(String method, BigInteger value) throws Exception {
        try {
            Method m = SolrQueryProcessor.class.getDeclaredMethod(method, BigInteger.class);
            m.setAccessible(true);
            return (Integer) m.invoke(null, value);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(method + " was renamed — update this test with it, or "
                    + "the clamp is unmeasured", e);
        }
    }

    @Test
    @DisplayName("a maxItems beyond int range is a page, not an empty result")
    void aHugeQueryMaxItemsIsAPage() throws Exception {
        assertEquals(10_000, clamp("clampQueryPage", BigInteger.ONE.shiftLeft(32)),
                "2^32 truncated to 0 and the query answered with an empty page");
        assertEquals(10_000, clamp("clampQueryPage",
                BigInteger.valueOf(Integer.MAX_VALUE)));
        assertEquals(25, clamp("clampQueryPage", BigInteger.valueOf(25)));
    }

    @Test
    @DisplayName("a non-positive maxItems is the default page — one answer across services")
    void aNonPositiveQueryMaxItemsIsTheDefaultPage() throws Exception {
        assertEquals(100, clamp("clampQueryPage", BigInteger.ZERO));
        assertEquals(100, clamp("clampQueryPage", BigInteger.valueOf(-7)));
    }

    @Test
    @DisplayName("a skipCount beyond int range is a position, not a wrapped one")
    void aHugeQuerySkipIsAPosition() throws Exception {
        assertEquals(Integer.MAX_VALUE, clamp("clampQuerySkip", BigInteger.ONE.shiftLeft(40)));
        assertEquals(0, clamp("clampQuerySkip", BigInteger.valueOf(-4)));
        assertEquals(12, clamp("clampQuerySkip", BigInteger.valueOf(12)));
    }

    @Test
    @DisplayName("the query pager uses the clamps — a clamp nothing calls protects nothing")
    void theQueryPagerUsesTheClamps() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains("clampQuerySkip(skipCount)"),
                "the query pager no longer clamps skipCount");
        assertTrue(source.contains("clampQueryPage(maxItems)"),
                "the query pager no longer clamps maxItems");
        assertFalse(source.contains("Math.max(0, maxItems.intValue())"),
                "the Math.max(0, intValue()) form came back — it bounds the result of the "
                        + "truncation, not the truncation");
        assertFalse(source.contains("Math.max(0, skipCount.intValue())"),
                "the Math.max(0, intValue()) form came back for skipCount");
    }
}
