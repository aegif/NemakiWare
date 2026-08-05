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
 * What to do about an obligation whose catalog entity is not there.
 *
 * <h2>Why this is its own contract</h2>
 *
 * <p>ABSENT is the only branch that can end an obligation by <em>writing</em> something. Every
 * other branch reads: PRESENT resolves, a failed probe retries. So this is where the two
 * lifecycles diverge — a source NemakiWare destroyed becomes a tombstone, and a source it never
 * owned becomes the ordinary entity the event observed — and it is where a mistake is permanent.
 * Naming it separately keeps that divergence in one readable place instead of inside a switch in
 * the consumer loop.
 *
 * <h2>Absent until wired, and refused while absent</h2>
 *
 * <p>Until an implementation is registered the consumer keeps doing what it did before: release
 * and retry. That is safe — nothing is written — but it is also not a working machine, because
 * an obligation whose authoritative publisher is never going to run would retry for ever. So
 * readiness refuses activation while this is unwired, and refuses it again if the pieces it
 * delegates to are not the same instances the rest of the context got. A half-wired settler
 * that resolved obligations against one store while the projector waited on another would be
 * invisible to a null check and fatal in production.
 */
public interface LineageCatalogAbsenceSettler {

    /** What the settler managed to establish. Four answers; none of them is a boolean. */
    enum Verdict {
        /**
         * The catalog now holds the right entity, confirmed by reading it back.
         *
         * <p>Carries the outcome to record, so the durable answer says which route settled it.
         * A generic "resolved" would store the same thing for a tombstone and for an
         * observation, and nothing later could tell them apart.
         */
        RESOLVED_PURGED(LineageCatalogObligation.Outcome.SOURCE_PURGED),
        /** Settled by materialising what the event observed. */
        RESOLVED_OBSERVED(LineageCatalogObligation.Outcome.OBSERVED_MATERIALIZED),
        /** Settled by publishing the current entity of a source proven to exist. */
        RESOLVED_CURRENT(LineageCatalogObligation.Outcome.CURRENT_MATERIALIZED),
        /**
         * Nothing terminal happened. Includes every failure, every UNKNOWN, every lost CAS and
         * every conflict — all of which may succeed later, so burning the obligation would turn
         * a transient problem into a permanently unprojectable event.
         */
        RETRY(null),
        /**
         * The snapshot cannot reconstruct the entity, and no amount of retrying will change
         * that. The only terminal verdict.
         */
        SNAPSHOT_INCOMPLETE(LineageCatalogObligation.Outcome.SNAPSHOT_INCOMPLETE),
        /**
         * Nothing could be established at all — the waiting snapshot could not be read, or it
         * was corrupt. Distinct from RETRY because the cause is not the catalog: it is the
         * journal, and an operator needs to see the difference.
         */
        INDETERMINATE(null);

        private final LineageCatalogObligation.Outcome outcome;

        Verdict(LineageCatalogObligation.Outcome outcome) {
            this.outcome = outcome;
        }

        /** What to store, or null when the obligation stays open. */
        public LineageCatalogObligation.Outcome outcome() {
            return outcome;
        }

        /** Whether the catalog was confirmed to hold the right entity. */
        public boolean resolves() {
            return this == RESOLVED_PURGED || this == RESOLVED_OBSERVED
                    || this == RESOLVED_CURRENT;
        }
    }

    /**
     * Decide what to write. Reads only — nothing outside this process is changed.
     *
     * <p>Separated from {@link #execute} so the caller can renew the obligation's claim between
     * them. The lookups this performs — the waiting view, a replay's origin point-read, the
     * authoritative source — all take time, and the lease is running throughout. Without a
     * renewal in between, a worker whose lease expired during the lookups would still write to
     * the catalog on its way out and race the worker that took over.
     *
     * @param obligation the claimed obligation; its own target, repository and kind decide
     *        everything, never a caller-supplied default
     * @return the frozen decision; never null
     */
    LineageAbsencePlan prepare(LineageCatalogObligation obligation);

    /**
     * Carry out a plan. The only method that touches anything external.
     *
     * <p>Consumes the plan without re-deriving the route: the branch was chosen before the
     * renewal, and choosing it again afterwards would reopen the window the renewal closes.
     *
     * <p>Must not resolve the obligation — the caller owns the claim and the CAS, and a settler
     * writing the obligation document would race the worker that holds it.
     */
    Verdict execute(LineageAbsencePlan plan);

    /**
     * The waiting-snapshot resolver this settler uses. Identity only; readiness never calls it.
     */
    LineageWaitingSnapshotResolver waitingSnapshotResolverRef();

    /** The historical publish machine this settler drives, for the LEDGERED branch. */
    LineageHistoricalPublishMachine historicalMachineRef();

    /**
     * The observed-entity materializers this settler drives, for the NON_PURGEABLE branch and
     * for a LEDGERED source proven to still exist.
     *
     * <p>A registry rather than one instance: the settler picks by the plan's own target, so a
     * node publishing to two catalogs cannot materialise into the wrong one. Readiness asks it
     * per configured target; a null registry, or one that answers for no target, is a violation.
     */
    LineageObservedEntityMaterializerRegistry observedMaterializersRef();
}
