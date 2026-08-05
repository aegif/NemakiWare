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
     * Catalog calls inside one fenced section: the historical publish, and the read-back that
     * confirms it.
     *
     * <p>Two, not one. A budget that counted a single call would pass a configuration whose
     * section takes twice as long as it is allowed to. The source re-check is counted separately
     * because it runs against the repository, not the catalog, with its own cost.
     */
    static final int CATALOG_OPERATIONS_PER_CRITICAL_SECTION = 2;

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
     * The worst case for the whole fenced section.
     *
     * <p>{@code Long.MAX_VALUE} when it cannot be computed — unbounded retries, or arithmetic
     * that would overflow. Both mean "does not fit", which is the fail-closed answer; returning
     * a small number on overflow would turn an absurd configuration into a passing one.
     */
    public long worstCaseMs() {
        if (!bounded()) {
            return Long.MAX_VALUE;
        }
        try {
            long perAttempt = Math.addExact(connectTimeoutMs, readTimeoutMs);
            long attempts = Math.addExact((long) maxRetries, 1L);
            long perOperation = Math.multiplyExact(perAttempt, attempts);
            long catalogOperations = Math.multiplyExact(perOperation,
                    (long) CATALOG_OPERATIONS_PER_CRITICAL_SECTION);
            long withBackoff = Math.addExact(catalogOperations,
                    Math.multiplyExact(retryBackoffTotalMs,
                            (long) CATALOG_OPERATIONS_PER_CRITICAL_SECTION));
            long withSourceRecheck = Math.addExact(withBackoff, sourceRecheckMs);
            return Math.addExact(withSourceRecheck, clientOverheadMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Whether this budget leaves a real margin inside {@code fenceLeaseMs}.
     *
     * <p>Strictly less than: at equality the section can still be running at the instant the
     * fence expires, which is the case the margin exists to exclude.
     */
    public boolean fitsInside(long fenceLeaseMs, long safetyMarginMs) {
        long worst = worstCaseMs();
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
