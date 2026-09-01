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
package jp.aegif.nemaki.cmis.service.impl;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A navigation request's paging arguments are never TRUNCATED into a different request.
 *
 * <h2>The same trap, three arguments later</h2>
 *
 * <p>{@code BigInteger.intValue()} keeps only the low 32 bits: 2^31 arrives as
 * {@code Integer.MIN_VALUE} and 2^32 as 0. The change feed learned what that costs live — an
 * unbounded ask became a CouchDB 400, and the first fix for it became a heap OOM — and got a
 * {@code compareTo} clamp. {@code getChildrenInternal} kept the raw {@code intValue()} for
 * {@code maxItems} and {@code skipCount}, and {@code getDescendants} for {@code depth}; here a
 * huge maxItems ALSO overflowed a second time in {@code dbLimit = _maxItems * oversampleFactor}.
 * Found by the round-34 review as the unfixed sibling of the change-feed clamp.
 *
 * <p>Two halves, on purpose: the behaviour of the clamps is driven directly (they are pure and
 * static), and the CALL SITES are pinned in source — because a clamp nothing calls protects
 * nothing, which is this batch's oldest lesson.
 */
class NavigationPagingArgumentsAreNotTruncatedTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/cmis/service/impl/NavigationServiceImpl.java";

    private static int clamp(String method, BigInteger value) throws Exception {
        try {
            Method m = NavigationServiceImpl.class.getDeclaredMethod(method, BigInteger.class);
            m.setAccessible(true);
            return (Integer) m.invoke(null, value);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(method + " was renamed or reshaped — update this test "
                    + "with it, or the clamp is unmeasured", e);
        }
    }

    @Test
    @DisplayName("maxItems beyond int range becomes a page, not a negative or zero")
    void aHugeMaxItemsBecomesAPage() throws Exception {
        // 2^31 → Integer.MIN_VALUE and 2^32 → 0 under intValue().
        assertEquals(10_000, clamp("clampToPage", BigInteger.valueOf(Integer.MAX_VALUE)));
        assertEquals(10_000, clamp("clampToPage",
                BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE)));
        assertEquals(10_000, clamp("clampToPage", BigInteger.ONE.shiftLeft(32)));
    }

    @Test
    @DisplayName("a non-positive maxItems is the default page, not 'no limit'")
    void aNonPositiveMaxItemsIsTheDefaultPage() throws Exception {
        assertEquals(100, clamp("clampToPage", BigInteger.ZERO));
        assertEquals(100, clamp("clampToPage", BigInteger.valueOf(-5)));
    }

    @Test
    @DisplayName("an ordinary maxItems passes through — the control")
    void anOrdinaryMaxItemsPassesThrough() throws Exception {
        assertEquals(25, clamp("clampToPage", BigInteger.valueOf(25)));
    }

    @Test
    @DisplayName("skipCount is a position: never negative, never truncated")
    void skipCountIsNeverNegative() throws Exception {
        assertEquals(0, clamp("clampSkip", BigInteger.valueOf(-1)));
        assertEquals(Integer.MAX_VALUE, clamp("clampSkip", BigInteger.ONE.shiftLeft(40)));
        assertEquals(7, clamp("clampSkip", BigInteger.valueOf(7)));
    }

    @Test
    @DisplayName("depth keeps -1 (unlimited) but a huge depth does not become 0")
    void depthKeepsItsMeaning() throws Exception {
        // 0 is an explicit error in CMIS (invalidArgumentDepth rejects it), so truncating a
        // huge depth to 0 turned "give me everything" into the no-descendants answer.
        assertEquals(-1, clamp("clampDepth", BigInteger.valueOf(-1)));
        assertEquals(Integer.MAX_VALUE, clamp("clampDepth", BigInteger.ONE.shiftLeft(32)));
        assertEquals(3, clamp("clampDepth", BigInteger.valueOf(3)));
    }

    @Test
    @DisplayName("the call sites use the clamps — a clamp nothing calls protects nothing")
    void theCallSitesUseTheClamps() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(SOURCE));
        assertTrue(source.contains("clampToPage(maxItems)"),
                "getChildrenInternal no longer clamps maxItems — 2^31 arrives negative and "
                        + "the oversampling multiplication overflows on top of it");
        assertTrue(source.contains("clampSkip(skipCount)"),
                "getChildrenInternal no longer clamps skipCount");
        assertTrue(source.contains("clampDepth(depth)"),
                "getDescendants no longer clamps depth");
        // Scoped to the CALL-SITE shapes: the clamps themselves end in intValue(), which is
        // safe because compareTo has already bounded the value. (The first version of this
        // assertion was unscoped and matched the helpers' own line — it fired on the fix
        // rather than on the defect.)
        assertFalse(source.contains("? maxItems.intValue() : DEFAULT_MAX_ITEMS"),
                "the raw maxItems call site came back — 2^31 arrives negative and the "
                        + "oversampling multiplication overflows on top of it");
        assertFalse(source.contains("? skipCount.intValue() : 0"),
                "the raw skipCount call site came back");
        assertFalse(source.contains("depth == null ? 2 : depth.intValue()"),
                "the raw depth call site came back");
        // The small-folder branch hands its paging to the compile service. It used to pass
        // the RAW BigIntegers, so everything the clamp above did was undone for exactly the
        // folders most requests hit — a live probe caught it (empty page, hasMoreItems=true).
        assertTrue(source.contains("BigInteger.valueOf(_maxItems), BigInteger.valueOf(_skipCount)"),
                "the legacy branch passes raw maxItems/skipCount to the compile service "
                        + "again, so the clamp only covers the oversampling branch");
    }
}
