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

    /** Records a receipt, replacing any earlier one for the same (domain, sequence, rung). */
    void save(String domain, long toSequence, AnchorReceipt receipt);

    /** Every receipt for one checkpoint. */
    List<AnchorReceipt> forCheckpoint(String domain, long toSequence);

    /**
     * Pending receipts, oldest first, so a scheduler can walk them.
     *
     * @return each pending receipt with the checkpoint it belongs to, since {@code upgrade} has
     *         to be able to write the result back to the same place
     */
    List<PendingReceipt> pending(String domain, int limit);

    /** A pending receipt and where it came from. */
    record PendingReceipt(String domain, long toSequence, AnchorReceipt receipt) {
    }

    /** Whether the backing store is reachable. Callers must not read "no pending receipts" from
     *  a store that could not be asked. */
    boolean isActive();
}
