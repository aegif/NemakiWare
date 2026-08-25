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
package jp.aegif.nemaki.evidence.anchor;

import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;

import java.util.List;

/**
 * Where anchor receipts live between being made and being upgradable (P2-0).
 *
 * <h2>Deliberately NOT part of {@code EvidenceLedgerStore}</h2>
 *
 * <p>That interface has no update method, on purpose. A receipt genuinely changes: an
 * OpenTimestamps commitment is {@code PENDING} for hours and then carries a fuller proof once a
 * block confirms it. Putting a mutable row behind an append-only interface would either force a
 * dishonest interface or teach the next person that the ledger's rows can be updated after all.
 *
 * <h2>Why it must persist at all</h2>
 *
 * <p>{@code AnchorTarget.upgrade} needs the pending proof BYTES. Held only in memory, every
 * pending commitment is lost at the next restart and can never be upgraded — the anchor was
 * made, the calendar has it, and the deployment can no longer produce the proof. That is the
 * quiet way rung 2 becomes decorative.
 */
public interface AnchorReceiptStore {

    /**
     * Records a receipt for a (domain, sequence, rung), and enforces monotonicity while doing it.
     *
     * <p><b>A CONFIRMED receipt is never replaced by a weaker one.</b> The rule lives HERE and
     * not in the caller because the caller cannot enforce it: a read-then-write in the service
     * leaves a window in which another writer stores CONFIRMED and this one overwrites it. The
     * decision has to be made inside the same compare-and-set as the write.
     *
     * <p>Nothing about the stored document changes for this — CouchDB's {@code _rev} already IS
     * the compare-and-set, and the previous code already sent it. What was missing was making
     * the decision inside that window and retrying when the revision turned out to be stale.
     *
     * @return {@link SaveOutcome#STORED} when the receipt was written, or
     *         {@link SaveOutcome#KEPT_STRONGER} when a CONFIRMED receipt was already there.
     *         Real failures THROW: "we chose not to write" and "we could not write" are
     *         different answers and a boolean would merge them.
     */
    SaveOutcome save(String domain, long toSequence, AnchorReceipt receipt);

    /** What {@link #save} did. */
    enum SaveOutcome {
        /** The receipt is now the stored one. */
        STORED,
        /** A CONFIRMED receipt was already there and was left alone. Not a failure. */
        KEPT_STRONGER
    }

    /** Every receipt for one checkpoint. */
    List<AnchorReceipt> forCheckpoint(String domain, long toSequence);

    /**
     * Pending receipts, oldest first, so a scheduler can walk them.
     *
     * @return each pending receipt with the checkpoint it belongs to, since {@code upgrade} has
     *         to be able to write the result back to the same place
     */
    List<PendingReceipt> pending(String domain, int limit);

    /**
     * CONFIRMED receipts, oldest first.
     *
     * <p>Separate from {@link #pending} because the two are used by different jobs and the
     * views that back them are different. The long-term-validity assessment asked
     * {@code pending()} for confirmed receipts and then filtered — a loop whose body could
     * never run, so no deployed timestamp token was ever assessed for renewal.
     */
    List<PendingReceipt> confirmed(String domain, int limit);

    /** A receipt and where it came from. Named for its first use; {@link #confirmed} shares it. */
    record PendingReceipt(String domain, long toSequence, AnchorReceipt receipt) {
    }

    /** Whether the backing store is reachable. Callers must not read "no pending receipts" from
     *  a store that could not be asked. */
    boolean isActive();
}
