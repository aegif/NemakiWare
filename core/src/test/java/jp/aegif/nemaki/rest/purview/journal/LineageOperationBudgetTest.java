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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.client.PurviewHttpRetryHandler;

/**
 * What the fence lease is actually being compared against.
 */
class LineageOperationBudgetTest {

    private static LineageOperationBudget budget(long connect, long read, int retries,
            long backoff, long overhead, long recheck) {
        return new LineageOperationBudget("atlas", EndpointKind.CMIS_DOCUMENT, connect, read,
                retries, backoff, overhead, recheck);
    }

    /**
     * The call counts are the contract, not an implementation detail.
     *
     * <p>Every route reads before writing and reads after — three catalog calls, never two. The
     * earlier model charged two for every route, which under-counted all of them, and
     * under-counting is what lets a section overrun the fence while every request inside it fits.
     */
    @Test
    @DisplayName("each route's call counts are what the code actually does")
    void routeCallCountsArePinned() {
        assertEquals(3, LineageOperationBudget.Route.OBSERVED.catalogOperations());
        assertEquals(0, LineageOperationBudget.Route.OBSERVED.sourceOperations());

        assertEquals(3, LineageOperationBudget.Route.CURRENT.catalogOperations());
        // The live-source re-check executeCurrent takes immediately before writing.
        assertEquals(1, LineageOperationBudget.Route.CURRENT.sourceOperations());

        assertEquals(3, LineageOperationBudget.Route.HISTORICAL.catalogOperations());
        // A re-check on each side of the publish, plus the compensating republish's content read.
        assertEquals(3, LineageOperationBudget.Route.HISTORICAL.sourceOperations());

        // And every route commits its outcome with a store write, after the external calls and
        // still inside the claim that authorised them.
        for (LineageOperationBudget.Route route : LineageOperationBudget.Route.values()) {
            assertEquals(1, route.storeOperations(), route + " must budget its final CAS");
        }

        // Only the historical route enters the machine that takes the subject fence.
        assertTrue(LineageOperationBudget.Route.HISTORICAL.insideSubjectFence());
        assertFalse(LineageOperationBudget.Route.OBSERVED.insideSubjectFence());
        assertFalse(LineageOperationBudget.Route.CURRENT.insideSubjectFence());
    }

    /** Which routes an obligation for a kind can end up on, and therefore what must fit. */
    @Test
    @DisplayName("a LEDGERED kind must fit on both of its routes")
    void reachableRoutesFollowThePolicy() {
        assertEquals(java.util.EnumSet.of(LineageOperationBudget.Route.CURRENT,
                        LineageOperationBudget.Route.HISTORICAL),
                budget(400, 600, 0, 0, 0, 500).reachableRoutes());
        assertEquals(java.util.EnumSet.of(LineageOperationBudget.Route.OBSERVED),
                new LineageOperationBudget("atlas", EndpointKind.EXTERNAL_ASSET, 400, 600, 0, 0,
                        0, 500).reachableRoutes());
    }

    @Test
    @DisplayName("the section counts all three catalog calls, not two")
    void countsPreReadPublishAndReadBack() {
        // No retries, no backoff, no overhead. A budget that counted two calls would say 2_500
        // for the current route and 3_500 for the historical one.
        LineageOperationBudget b = budget(400, 600, 0, 0, 0, 500);
        // One re-check plus the final CAS for the current route; three repository reads plus
        // the CAS for the historical one.
        assertEquals(3 * 1_000 + 2 * 500, b.worstCaseMs(LineageOperationBudget.Route.CURRENT));
        assertEquals(3 * 1_000 + 4 * 500,
                b.worstCaseMs(LineageOperationBudget.Route.HISTORICAL));
        // The reported number is the worst reachable route: the route is not known until the
        // evidence is read, which is long after the fence is taken.
        assertEquals(3 * 1_000 + 4 * 500, b.worstCaseMs());
    }

    @Test
    @DisplayName("retries and their backoff are inside the budget")
    void includesRetries() {
        // 3 retries => 4 attempts per call, and the backoff is per call as well.
        LineageOperationBudget b = budget(400, 600, 3, 7_000, 0, 500);
        assertEquals(3 * (1_000 * 4) + 3 * 7_000 + 2 * 500,
                b.worstCaseMs(LineageOperationBudget.Route.CURRENT));
        assertEquals(3 * (1_000 * 4) + 3 * 7_000 + 4 * 500, b.worstCaseMs());
    }

    @Test
    @DisplayName("a read timeout that fits alone does not mean the section fits")
    void readTimeoutAloneIsNotTheSection() {
        long lease = 120_000L;
        long margin = 60_000L;
        // 20s read timeout is comfortably inside a 120s lease on its own...
        assertTrue(20_000 < lease - margin);
        // ...but the section runs it three times, with 3 retries each and backoff.
        LineageOperationBudget b = budget(2_000, 20_000, 3, 7_000, 5_000, 2_000);
        assertFalse(b.fitsInside(lease, margin),
                "the section must be judged as a whole, not by its largest single request");
    }

    @Test
    @DisplayName("unbounded retries are not budgetable")
    void unboundedRetries() {
        LineageOperationBudget b = budget(100, 100, -1, 0, 0, 100);
        assertFalse(b.bounded());
        assertEquals(Long.MAX_VALUE, b.worstCaseMs());
        assertFalse(b.fitsInside(Long.MAX_VALUE, 0));
    }

    @Test
    @DisplayName("an unknown source re-check cost is not zero")
    void missingSourceRecheck() {
        // Treating "not declared" as free would licence a section whose first step is a remote
        // call nobody measured.
        assertFalse(budget(100, 100, 0, 0, 0, 0).bounded());
    }

    @Test
    @DisplayName("overflow means does not fit, never a small number")
    void overflowFailsClosed() {
        LineageOperationBudget b = budget(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 7,
                Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2);
        assertEquals(Long.MAX_VALUE, b.worstCaseMs());
        assertFalse(b.fitsInside(Long.MAX_VALUE, 1));
    }

    @Test
    @DisplayName("budget + margin == lease is rejected")
    void boundaryEqualityIsRejected() {
        LineageOperationBudget b = budget(500, 500, 0, 0, 0, 1_000);
        assertEquals(7_000, b.worstCaseMs());
        // Exactly equal: the section can still be running at the instant the fence expires.
        assertFalse(b.fitsInside(10_000, 3_000), "equality must be refused");
        // One millisecond of real margin is enough to be inside.
        assertTrue(b.fitsInside(10_001, 3_000));
        // And per route: the cheaper route fits at a lease the worst one does not.
        assertEquals(5_000, b.worstCaseMs(LineageOperationBudget.Route.CURRENT));
        assertTrue(b.fitsInside(LineageOperationBudget.Route.CURRENT, 10_000, 3_000));
        assertFalse(b.fitsInside(LineageOperationBudget.Route.HISTORICAL, 10_000, 3_000));
    }

    @Test
    @DisplayName("a zero or negative lease can never be fitted into")
    void degenerateLease() {
        LineageOperationBudget b = budget(100, 100, 0, 0, 0, 100);
        assertFalse(b.fitsInside(0, 0));
        assertFalse(b.fitsInside(-1, 0));
        assertFalse(b.fitsInside(10_000, -1));
    }

    @Test
    @DisplayName("readiness budgets the retry policy the client actually applies")
    void retryPolicyIsTheClientsOwn() {
        // Identity, not a second copy: if the handler's retry count or backoff changes, the
        // budget changes with it. A duplicated constant would drift and claim a margin the
        // running code does not respect.
        var provider = new ConfiguredLineageOperationBudgetProvider(null, null);
        assertTrue(provider.budgetFor("atlas", EndpointKind.CMIS_DOCUMENT).isEmpty(),
                "no config means no budget");
        assertEquals(3, PurviewHttpRetryHandler.maxRetries());
        // 1000 + 2000 + 4000, each with the positive side of the ±10% jitter.
        assertEquals(1_100 + 2_200 + 4_400, PurviewHttpRetryHandler.worstCaseBackoffTotalMs());
    }

    /**
     * Pinned by exact rendering rather than by the absence of a substring.
     *
     * <p>The earlier form asserted that the text did not contain "600", the read timeout. It
     * held only by luck: the worst case is a sum, and a sum is free to contain any digits its
     * inputs do. Exact equality says the intended thing — the target, the kind and one derived
     * total, and nothing else — and any field added later breaks it on purpose.
     */
    @Test
    @DisplayName("toString carries no endpoint, credential or configuration value")
    void toStringIsSafe() {
        LineageOperationBudget b = budget(400, 600, 3, 7_000, 5_000, 2_000);
        // 3 catalog calls x 4 attempts x 1_000, + 3 x 7_000 backoff, + 4 repository operations
        // x 2_000, + 5_000 overhead.
        long worst = 3 * 4 * 1_000 + 3 * 7_000 + 4 * 2_000 + 5_000;
        assertEquals(worst, b.worstCaseMs());
        assertEquals("LineageOperationBudget[atlas/CMIS_DOCUMENT worstCase=" + worst + "ms]",
                b.toString());
    }
}
