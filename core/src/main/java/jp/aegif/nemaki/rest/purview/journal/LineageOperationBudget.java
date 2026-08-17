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

/**
 * How long the fenced critical section can take, at worst.
 *
 * <h2>Why a single read timeout was the wrong number</h2>
 *
 * <p>The subject fence does not protect one HTTP request. It protects a section containing a
 * source re-check, a historical publish and a read-back, each of which can retry with backoff.
 * Comparing one read timeout against the lease answered a question nobody asked: the section
 * can exceed the lease while every individual request fits inside it comfortably.
 *
 * <p>And a single value cannot be shared across targets. Atlas and Purview are configured
 * separately, and inferring one from the other is the reuse this whole increment keeps refusing.
 *
 * @param connectTimeoutMs per attempt
 * @param readTimeoutMs per attempt
 * @param maxRetries additional attempts after the first; negative means unbounded, which cannot
 *        be budgeted at all
 * @param retryBackoffTotalMs the total sleep across all retries, not per retry
 * @param clientOverheadMs whatever the client adds that is not request time — auth refresh,
 *        connection pool waits, the margin a target's own documentation gives
 * @param sourceRecheckMs the immediately-preceding authoritative source re-check, which is
 *        per endpoint kind and does not go through the catalog client at all
 */
public record LineageOperationBudget(
        String target,
        EndpointKind kind,
        long connectTimeoutMs,
        long readTimeoutMs,
        int maxRetries,
        long retryBackoffTotalMs,
        long clientOverheadMs,
        long sourceRecheckMs) {

    /**
     * What one fenced section actually costs, counted per route.
     *
     * <h2>Why one number for every route was wrong</h2>
     *
     * <p>The earlier model charged every section two catalog calls and one source re-check. No
     * route costs that. Every route does a pre-read, a write and a post-read — three — and the
     * routes differ again in how many times they ask the repository. Under-counting is the
     * dangerous direction: it passes exactly the configurations this check exists to reject,
     * because the section overruns the fence while every individual request fits inside it.
     *
     * <p>Counted from the code that runs, not from the design:
     *
     * <ul>
     *   <li>{@code OBSERVED} — {@code CatalogObservedEntityMaterializer.publishAndConfirm}:
     *       read-back, bulk publish, read-back. Nothing asks the repository; the policy already
     *       said this source is never destroyed.</li>
     *   <li>{@code CURRENT} — the same three, preceded by the live-source re-check that
     *       {@code PolicyRoutedAbsenceSettler.executeCurrent} takes immediately before writing.</li>
     *   <li>{@code HISTORICAL} — {@code LineageHistoricalPublishMachine.publish}: a source
     *       re-check, a read-back, the publish, a second source re-check afterwards, and then the
     *       compensating republish, which is one more catalog write plus one repository read for
     *       the content it republishes. The longest reachable path, because a budget that assumed
     *       the short one would be a budget for the case that never needed it.</li>
     * </ul>
     *
     * @param catalogOperations calls through the catalog client, each retryable with backoff
     * @param repositoryOperations reads against the repository — source re-checks and the
     *        compensating republish's content read. Charged at {@code sourceRecheckMs} each.
     */
    public enum Route {
        OBSERVED(3, 0, false),
        CURRENT(3, 1, false),
        HISTORICAL(3, 3, true);

        private final int catalogOperations;
        private final int sourceOperations;
        private final boolean insideSubjectFence;

        Route(int catalogOperations, int sourceOperations, boolean insideSubjectFence) {
            this.catalogOperations = catalogOperations;
            this.sourceOperations = sourceOperations;
            this.insideSubjectFence = insideSubjectFence;
        }

        public int catalogOperations() {
            return catalogOperations;
        }

        /**
         * Repository operations: the source re-checks, and for the historical route the
         * compensating republish's own content read.
         */
        public int sourceOperations() {
            return sourceOperations;
        }

        /**
         * Plus the store write that records the outcome.
         *
         * <p>One for every route. It happens <em>after</em> the external calls and still has to
         * land inside the claim that authorised them — a CAS that lands after the lease expired
         * is a worker writing a result for an obligation somebody else now owns. Leaving it out
         * budgeted the part that talks to the catalog and ignored the part that commits it.
         */
        public int storeOperations() {
            return 1;
        }

        /**
         * Whether this route also runs inside the historical machine's subject fence.
         *
         * <p>Only the historical one does. The other two never enter the machine: they run under
         * the obligation's own claim and nothing else. Checking them against the subject fence
         * was checking them against a lease they never hold — invisible today only because the
         * two leases happen to carry the same number.
         */
        public boolean insideSubjectFence() {
            return insideSubjectFence;
        }
    }

    /**
     * The routes an obligation for this kind can actually take.
     *
     * <p>A LEDGERED kind can end up on either the current-source route or the historical one,
     * and which it takes is decided by evidence read at prepare time — so both must fit. A kind
     * NemakiWare never destroys only has the observed route.
     *
     * <p>An unclassified kind gets every route. Not a default: readiness already refuses to
     * activate over an unclassified kind, and charging it the cheapest route would be the one
     * answer that could turn that red green.
     */
    public java.util.Set<Route> reachableRoutes() {
        if (kind == null || LineagePurgeLifecyclePolicy.of(kind).isEmpty()) {
            return java.util.EnumSet.allOf(Route.class);
        }
        return LineagePurgeLifecyclePolicy.canBePurged(kind)
                ? java.util.EnumSet.of(Route.CURRENT, Route.HISTORICAL)
                : java.util.EnumSet.of(Route.OBSERVED);
    }

    /**
     * Whether every component is known and bounded.
     *
     * <p>Unbounded retries — a negative count, meaning "until it succeeds" — cannot be budgeted
     * at all, so they are not bounded no matter how small the timeouts are.
     */
    public boolean bounded() {
        return target != null && !target.isBlank() && kind != null
                && connectTimeoutMs > 0 && readTimeoutMs > 0
                && maxRetries >= 0 && retryBackoffTotalMs >= 0 && clientOverheadMs >= 0
                && sourceRecheckMs > 0;
    }

    /**
     * The worst case for one fenced section on this route.
     *
     * <p>{@code Long.MAX_VALUE} when it cannot be computed — unbounded retries, or arithmetic
     * that would overflow. Both mean "does not fit", which is the fail-closed answer; returning
     * a small number on overflow would turn an absurd configuration into a passing one.
     */
    public long worstCaseMs(Route route) {
        if (!bounded() || route == null) {
            return Long.MAX_VALUE;
        }
        try {
            long perAttempt = Math.addExact(connectTimeoutMs, readTimeoutMs);
            long attempts = Math.addExact((long) maxRetries, 1L);
            long perOperation = Math.multiplyExact(perAttempt, attempts);
            long catalogCalls = (long) route.catalogOperations();
            long catalogOperations = Math.multiplyExact(perOperation, catalogCalls);
            long withBackoff = Math.addExact(catalogOperations,
                    Math.multiplyExact(retryBackoffTotalMs, catalogCalls));
            long repositoryTime = Math.multiplyExact(sourceRecheckMs,
                    (long) (route.sourceOperations() + route.storeOperations()));
            return Math.addExact(Math.addExact(withBackoff, repositoryTime), clientOverheadMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The worst case across every route this kind can reach.
     *
     * <p>The number to compare against the fence when only one may be reported, because the route
     * is not known until the evidence is read — well after the fence is taken.
     */
    public long worstCaseMs() {
        long worst = 0L;
        for (Route route : reachableRoutes()) {
            long candidate = worstCaseMs(route);
            if (candidate == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            worst = Math.max(worst, candidate);
        }
        return worst;
    }

    /**
     * Whether this route leaves a real margin inside {@code fenceLeaseMs}.
     *
     * <p>Strictly less than: at equality the section can still be running at the instant the
     * fence expires, which is the case the margin exists to exclude.
     */
    public boolean fitsInside(Route route, long fenceLeaseMs, long safetyMarginMs) {
        return fitsInside(worstCaseMs(route), fenceLeaseMs, safetyMarginMs);
    }

    /** Whether every reachable route fits. One failing route is enough to fail. */
    public boolean fitsInside(long fenceLeaseMs, long safetyMarginMs) {
        return fitsInside(worstCaseMs(), fenceLeaseMs, safetyMarginMs);
    }

    private static boolean fitsInside(long worst, long fenceLeaseMs, long safetyMarginMs) {
        if (worst == Long.MAX_VALUE || fenceLeaseMs <= 0 || safetyMarginMs < 0) {
            return false;
        }
        try {
            return Math.addExact(worst, safetyMarginMs) < fenceLeaseMs;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** Numbers only; a target name is configuration, not a secret. */
    @Override
    public String toString() {
        return "LineageOperationBudget[" + target + "/" + kind + " worstCase="
                + (worstCaseMs() == Long.MAX_VALUE ? "unbounded" : worstCaseMs() + "ms") + "]";
    }
}
