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

import java.util.Comparator;

/**
 * Where an event sits in the journal's own order — the same coordinate the cursor uses.
 *
 * <h2>Which value is canonical, and why not the delivery id</h2>
 *
 * <p>The ordered v2 stream is {@code v2_by_repository_and_sequence}, keyed
 * {@code [repositoryId, sequenceNumber]}, and §8 defines {@code sequence} as finalization
 * order. That is the contract the projector and the cursor advance along, so it is the one a
 * resolver must use to say which of two snapshots is later.
 *
 * <p>A delivery id is a stable identifier and nothing more. Sorting by it lexicographically
 * produces <em>an</em> order, which is why it looked adequate — but it is unrelated to when
 * anything happened, so "the last one" under that sort can be the first one that occurred. It
 * survives here only as a deterministic tie-break, where two candidates share a coordinate and
 * the choice must at least be repeatable.
 *
 * <h2>Repository is part of the coordinate, not a filter applied afterwards</h2>
 *
 * <p>Sequence numbers come from a per-repository counter, so two repositories' sequence 41 are
 * different events. Comparing across repositories is meaningless rather than merely wrong, and
 * the resolver treats a mixed candidate set as corruption instead of ordering it.
 */
public record LineageJournalOrder(String repositoryId, long sequenceNumber, String deliveryId) {

    /**
     * Whether this coordinate can order anything.
     *
     * <p>{@code sequenceNumber} zero means UNSEQUENCED — the event has not been given a place
     * in the stream yet, so it has no position to compare. That is indeterminate, not "first".
     */
    public boolean usable() {
        return repositoryId != null && !repositoryId.isBlank()
                && sequenceNumber > 0L
                && deliveryId != null && !deliveryId.isBlank();
    }

    /** Same position in the stream — where a payload difference becomes a contradiction. */
    public boolean samePositionAs(LineageJournalOrder other) {
        return other != null
                && sequenceNumber == other.sequenceNumber
                && repositoryId.equals(other.repositoryId);
    }

    /**
     * Journal order within one repository: sequence first, delivery id only to break a tie.
     *
     * <p>Deliberately not usable across repositories — see the class javadoc. Callers must
     * establish a single repository before sorting.
     */
    public static Comparator<LineageJournalOrder> withinRepository() {
        return Comparator.comparingLong(LineageJournalOrder::sequenceNumber)
                .thenComparing(LineageJournalOrder::deliveryId);
    }
}
