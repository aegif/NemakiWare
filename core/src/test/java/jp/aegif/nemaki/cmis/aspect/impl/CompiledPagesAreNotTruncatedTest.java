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
package jp.aegif.nemaki.cmis.aspect.impl;

import jp.aegif.nemaki.util.test.HarnessBroken;
import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared page assembler never truncates a client's paging arguments.
 *
 * <h2>The empty page a live probe found</h2>
 *
 * <p>Navigation clamped its own copies of maxItems/skipCount — and then its small-folder
 * branch handed the RAW BigIntegers to this service, which called {@code intValue()} on them.
 * A live request with {@code maxItems=2^32} therefore became {@code _maxItems = 0}:
 * {@code subList(0, 0)} returned an EMPTY page with {@code hasMoreItems = true}, as a clean
 * 200. Query and relationships reach the same two paging blocks, so the clamp belongs here
 * rather than at one caller.
 *
 * <p>{@code skipCount + maxItems} is also kept in int range: two large-but-valid values used
 * to sum to a negative index.
 */
class CompiledPagesAreNotTruncatedTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java";

    private static int clamp(String method, Class<?>[] types, Object... args) throws Exception {
        try {
            Method m = CompileServiceImpl.class.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return (Integer) m.invoke(null, args);
        } catch (NoSuchMethodException e) {
            throw new HarnessBroken(method + " was renamed or reshaped — update this test "
                    + "with it, or the clamp is unmeasured", e);
        }
    }

    private static int maxItems(BigInteger skip, BigInteger max) throws Exception {
        return clamp("clampMaxItems", new Class<?>[] { BigInteger.class, BigInteger.class },
                skip, max);
    }

    private static int skipCount(BigInteger skip) throws Exception {
        return clamp("clampSkipCount", new Class<?>[] { BigInteger.class }, skip);
    }

    @Test
    @DisplayName("maxItems beyond int range becomes a page, not an empty one")
    void aHugeMaxItemsBecomesAPage() throws Exception {
        assertEquals(10_000, maxItems(BigInteger.ZERO, BigInteger.ONE.shiftLeft(32)),
                "2^32 truncated to 0, so subList(0, 0) served an empty page while "
                        + "hasMoreItems said there was more");
        assertEquals(10_000, maxItems(BigInteger.ZERO,
                BigInteger.valueOf(Integer.MAX_VALUE)));
    }

    @Test
    @DisplayName("skipCount + maxItems stays a positive index")
    void theSumStaysInRange() throws Exception {
        int skip = skipCount(BigInteger.valueOf(Integer.MAX_VALUE - 5));
        int max = maxItems(BigInteger.valueOf(Integer.MAX_VALUE - 5),
                BigInteger.valueOf(10_000));
        assertEquals(Integer.MAX_VALUE - 5, skip);
        assertTrue(skip + max > 0, "the page bounds overflowed into a negative index");
    }

    @Test
    @DisplayName("ordinary paging passes through — the control")
    void ordinaryPagingPassesThrough() throws Exception {
        assertEquals(25, maxItems(BigInteger.TEN, BigInteger.valueOf(25)));
        assertEquals(10, skipCount(BigInteger.TEN));
        assertEquals(0, skipCount(BigInteger.valueOf(-3)));
    }

    @Test
    @DisplayName("a non-positive maxItems is the default page here too — one answer per input")
    void aNonPositiveMaxItemsIsTheDefaultPage() throws Exception {
        // Navigation mapped a non-positive ask to its default page while query and
        // relationships handed the raw value straight here and got an EMPTY page — the same
        // 200-with-nothing the live probe found, reached by the other door.
        assertEquals(100, maxItems(BigInteger.ZERO, BigInteger.ZERO));
        assertEquals(100, maxItems(BigInteger.ZERO, BigInteger.valueOf(-5)));
        assertEquals(100, maxItems(BigInteger.ZERO, null));
    }

    @Test
    @DisplayName("both paging blocks use the clamps — a clamp nothing calls protects nothing")
    void bothPagingBlocksUseTheClamps() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        // The PAIR, counted exactly: the first version of this assertion counted
        // clampSkipCount occurrences and demanded ">= 2", which the helper's own internal
        // call already satisfied — so reverting ONE paging block slipped through (the
        // runner caught it). Breaking either line of either block now drops the count.
        String pair = "int _skipCount = clampSkipCount(skipCount);\n"
                + "\t\t\tint _maxItems = clampMaxItems(skipCount, maxItems);";
        int blocks = source.split(java.util.regex.Pattern.quote(pair), -1).length - 1;
        assertEquals(2, blocks,
                "expected both paging blocks to clamp, found " + blocks + " — this service "
                        + "has two, and both are reachable from navigation, query and "
                        + "relationships");
        assertFalse(source.contains("= maxItems.intValue();"),
                "a raw maxItems.intValue() came back in a paging block (the clamp's own use "
                        + "is inside a ternary, not a plain assignment)");
    }
}
