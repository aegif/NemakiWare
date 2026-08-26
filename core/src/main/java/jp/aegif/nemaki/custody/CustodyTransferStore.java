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
package jp.aegif.nemaki.custody;

import java.util.List;

/**
 * Where transfers live between the steps of a handover (P3-4).
 *
 * <h2>Why a store at all</h2>
 *
 * <p>The steps happen days apart: a package goes out, and the receipt comes back after the far
 * end has ingested it and made an AIP. A transfer held in memory does not survive that, which
 * made the state machine unusable for the process it models — every restart lost every transfer
 * in flight, and there was no way to answer "what did we send them, and did they say anything".
 *
 * <h2>Saving is part of the move, not a step after it</h2>
 *
 * <p>{@link #save} returns false when the write did not take effect, and the service treats
 * that as the move not having happened. The alternative — moving in memory and reporting
 * success while the store rejected the write — produces a transfer whose state is one thing to
 * this process and another thing to the next one, and the receipt that arrives tomorrow is
 * checked against the wrong one.
 */
public interface CustodyTransferStore {

    /** Whether this node can read and write transfers at all. */
    boolean isActive();

    /**
     * Writes the transfer, creating or updating it.
     *
     * @return false when the write did not take effect. NOT an exception: a lost write is an
     *         ordinary outcome under contention, and the caller's response to it — refuse the
     *         move — is the same either way.
     */
    boolean save(CustodyTransfer transfer);

    /** The transfer, or null when there is none by that id. */
    CustodyTransfer find(String repositoryId, String transferId);

    /** Every transfer for one record, newest first. Empty when there are none. */
    List<CustodyTransfer> findByObject(String repositoryId, String objectId, int limit);

    /**
     * How many rows the last {@link #findByObject} on this thread could not read.
     *
     * <p>Zero is the ordinary answer. A non-zero one means the list that came back is not a
     * complete answer, and a caller that reports it as one is telling a reader a record was
     * never sent anywhere on the strength of a row nobody could parse.
     */
    default int unreadableCount() {
        return 0;
    }
}
