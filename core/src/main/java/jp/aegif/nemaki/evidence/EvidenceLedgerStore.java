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
package jp.aegif.nemaki.evidence;

import java.util.List;

/**
 * Where the evidence ledger lives (P1-3).
 *
 * <h2>Append-only means append-only</h2>
 *
 * <p>There is no update and no delete on this interface, and that is the interface's main
 * content. It is an application-layer rule — anything with direct database access can still
 * rewrite a row, which is why the chain exists to DETECT that — but a store that offers no way
 * to rewrite cannot be used to rewrite by accident, and a future caller looking for
 * {@code update} finds a deliberate absence rather than a method.
 *
 * <p>Design: {@code docs/design/p1-3-evidence-ledger.md}.
 */
public interface EvidenceLedgerStore {

    /**
     * Appends at {@code entry.sequence()}, creating only if that position is free.
     *
     * @return {@code true} when this call created the row. {@code false} means the position was
     *         already taken — which is how a second writer at the same sequence loses instead
     *         of producing a silent duplicate. The caller re-reads and decides; it must not
     *         treat a lost race as success.
     */
    boolean append(EvidenceLedgerEntry entry);

    /** The highest sequence in this domain, or {@code -1} when the domain has no entries. */
    long highestSequence(String domain);

    /**
     * Rows the last read on this thread returned and could not decode.
     *
     * <p>Zero from a store that does not track it. A caller that reads an empty list as "the
     * chain holds nothing about this" must consult it: a row the view returned and the store
     * could not read is an entry that exists and is NOT in the list.
     */
    default int unreadableCount() {
        return 0;
    }

    /** Entries in ascending sequence order, inclusive. Duplicates at one sequence are RETURNED
     *  rather than collapsed — that is what a fork looks like, and hiding it here would make
     *  the verifier unable to see it. */
    List<EvidenceLedgerEntry> range(String domain, long fromSequence, long toSequence, int limit);

    /**
     * Every entry ABOUT one subject, in sequence order.
     *
     * <p>The only way in that does not require already knowing a sequence number. A reader holds
     * an object id or a capture id, never a sequence; without this there is no route from one to
     * the other, and an inclusion proof for a specific record cannot be produced at all. A
     * design that records evidence nobody can look up is not far from recording none.
     *
     * @return possibly empty, never null. Empty means no entry names this subject — a true
     *         statement about a record that was never chained, not an error
     */
    List<EvidenceLedgerEntry> findBySubject(String domain, String subjectId, int limit);

    /** Writes a checkpoint. Same create-only rule as {@link #append}. */
    boolean appendCheckpoint(EvidenceCheckpoint checkpoint);

    /** The most recent checkpoint for a domain, or null when there is none. */
    EvidenceCheckpoint latestCheckpoint(String domain);

    /**
     * The checkpoint whose span ends just before {@code fromSequence}, or null.
     *
     * <p>Used to walk back to the checkpoint that covers an older entry, so an inclusion proof
     * can be answered without loading every checkpoint.
     */
    EvidenceCheckpoint checkpointEndingBefore(String domain, long fromSequence);

    /** Whether the backing store is reachable. A ledger that cannot be read must say so rather
     *  than answering "no entries", which reads as "nothing has happened". */
    boolean isActive();
}
