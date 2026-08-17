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
 * What an ABSENT obligation needs written, decided before anything is written.
 *
 * <h2>Why the decision is a value</h2>
 *
 * <p>The route has to be chosen from the waiting snapshot, the replay origin and the source
 * evidence — all reads, all of which take time, and all of which happen while the obligation's
 * claim lease is running. The external write must therefore be preceded by a renewal, and a
 * renewal is only meaningful if nothing between it and the write can change what gets written.
 *
 * <p>So the route is frozen into one of these before the renewal, and the executor consumes it
 * without looking at the obligation again. Re-deriving the branch after the renewal — from the
 * raw obligation, or from a nullable evidence field — would reopen exactly the window the
 * renewal exists to close, and the second derivation could disagree with the first.
 *
 * <h2>Every write-carrying variant carries its own proof</h2>
 *
 * <p>Each holds an already-validated snapshot type rather than the parts to build one. The types
 * refuse each other's cases: a tombstone cannot be built from a live source, and a live entity
 * cannot be built from a purged one. By the time a plan exists, the question "is this allowed"
 * has already been answered by a constructor.
 */
public sealed interface LineageAbsencePlan {

    /** Publish a tombstone through the historical machine. Only for a proven purge. */
    record HistoricalPurgedPlan(LineageCatalogObligation obligation,
            HistoricalEntitySnapshot historical, LineageObservationProvenance provenance,
            java.util.List<String> mandatoryAttributes) implements LineageAbsencePlan { }

    /** Materialise what the event observed. Only for a source NemakiWare never destroys. */
    record ObservedPlan(ObservedEntitySnapshot observed) implements LineageAbsencePlan { }

    /**
     * Materialise the current entity for a source proven to exist.
     *
     * <p>This is what stops {@code SOURCE_EXISTS} being an infinite retry: the authoritative
     * publisher may never run for this subject, and the obligation would wait for ever on a
     * source that is sitting right there.
     */
    record CurrentSourcePlan(VerifiedCurrentEntitySnapshot current) implements LineageAbsencePlan { }

    /** Nothing to write. The three ways that happens are different statements. */
    sealed interface NoWrite extends LineageAbsencePlan {

        /** May succeed later — the catalog, the source or the store was simply not ready. */
        record Retry(String reason) implements NoWrite { }

        /**
         * Nothing could be established: the waiting row could not be read, or it contradicts
         * itself. Distinct from Retry because the cause is the journal, not the catalog.
         */
        record Indeterminate(String reason) implements NoWrite { }

        /** The snapshot cannot reconstruct the entity. The only terminal one. */
        record SnapshotIncomplete(String reason) implements NoWrite { }
    }

    /** Whether executing this plan touches anything outside this process. */
    default boolean writesExternally() {
        return !(this instanceof NoWrite);
    }
}
