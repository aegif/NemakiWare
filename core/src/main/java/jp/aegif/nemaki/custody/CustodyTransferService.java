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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The one place that moves a transfer, saves it, and enforces the rule (P3-4).
 *
 * <h2>The rule was typed and nobody executed it</h2>
 *
 * <p>{@link CustodyLedgerRecorder#recordVerifiedReceipt} returns an {@code Authorisation} whose
 * javadoc says the caller must not pass custody when it refuses. There was no caller. A rule
 * whose enforcement lives in a comment is a rule that holds until the first person who does not
 * read the comment — and the thing it guards is a repository deciding it no longer holds the
 * only copy of a record.
 *
 * <p>{@link #passCustody} is that caller. It records first, and moves only if the recording
 * took. Custody is the one move in this machine that is worth refusing over a ledger failure:
 * everything before it is a note about what the far end said, and this one is where we stop
 * being answerable for the record.
 *
 * <h2>Saving is part of the move</h2>
 *
 * <p>Every method here writes before it reports success. A move that happened in memory and did
 * not reach the store leaves a transfer whose state is one thing to this process and another to
 * the next one — and the receipt that arrives tomorrow is checked against the wrong one.
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md} §7.
 */
@Component
public class CustodyTransferService {

    private static final Logger logger = LoggerFactory.getLogger(CustodyTransferService.class);

    private CustodyTransferStore store;
    private CustodyLedgerRecorder ledgerRecorder;

    @Autowired(required = false)
    public void setStore(CustodyTransferStore store) {
        this.store = store;
    }

    @Autowired(required = false)
    public void setLedgerRecorder(CustodyLedgerRecorder ledgerRecorder) {
        this.ledgerRecorder = ledgerRecorder;
    }

    /** What happened, and what to tell whoever asked. */
    public record Outcome(boolean done, CustodyTransfer transfer, String refusedReason) {

        static Outcome done(CustodyTransfer transfer) {
            return new Outcome(true, transfer, null);
        }

        static Outcome refused(CustodyTransfer transfer, String reason) {
            return new Outcome(false, transfer, reason);
        }
    }

    /** Opens a transfer for a package that has been built. */
    public Outcome open(String repositoryId, String transferId, String objectId, String sipDigest,
            String receivingSystem) {
        if (notStorable()) {
            return Outcome.refused(null, unstorable());
        }
        CustodyTransfer transfer = new CustodyTransfer(transferId, repositoryId, objectId,
                sipDigest, receivingSystem, Instant.now().toString());
        if (!store.save(transfer)) {
            return Outcome.refused(null, "the transfer was not written, so it does not exist. "
                    + "Nothing was sent and nothing is in flight.");
        }
        return Outcome.done(transfer);
    }

    /**
     * An ordinary move.
     *
     * <p>{@code CUSTODY_TRANSFERRED} is refused here on purpose: it is not ordinary, and
     * letting it through this door would put the ledger rule back where it was — in a comment.
     */
    public Outcome advance(String repositoryId, String transferId, CustodyState next,
            String reason) {
        if (next == CustodyState.CUSTODY_TRANSFERRED) {
            return Outcome.refused(null, "custody does not pass by advancing to it. Use "
                    + "passCustody, which records the handover first and moves only if that "
                    + "recording took effect.");
        }
        CustodyTransfer transfer = load(repositoryId, transferId);
        if (transfer == null) {
            return Outcome.refused(null, notFound(transferId));
        }
        CustodyTransfer.Moved moved = transfer.advance(next, Instant.now().toString(), reason);
        if (!moved.accepted()) {
            return Outcome.refused(transfer, moved.refusedReason());
        }
        return persist(transfer, "the move was made but not written, so it did not happen");
    }

    /** Checks a receipt against the package this transfer sent, and saves the result. */
    public Outcome verifyReceipt(String repositoryId, String transferId, CustodyReceipt receipt) {
        CustodyTransfer transfer = load(repositoryId, transferId);
        if (transfer == null) {
            return Outcome.refused(null, notFound(transferId));
        }
        CustodyTransfer.Moved moved = transfer.verifyReceipt(receipt, Instant.now().toString());
        if (!moved.accepted()) {
            return Outcome.refused(transfer, moved.refusedReason());
        }
        return persist(transfer, "the receipt was checked but the result was not written, so "
                + "this transfer is still where it was");
    }

    /**
     * Passes custody — recording the handover FIRST, and moving only if that took effect.
     *
     * <p>The order is the whole point and it is the opposite of the capture rule. A capture has
     * already happened when we try to chain it, so refusing would destroy the thing the record
     * was about. Custody has NOT passed when we try to chain it; refusing costs a retry, and
     * proceeding would mean this repository stopped being answerable for a record with nothing
     * anywhere saying when or to whom.
     */
    public Outcome passCustody(String repositoryId, String transferId) {
        CustodyTransfer transfer = load(repositoryId, transferId);
        if (transfer == null) {
            return Outcome.refused(null, notFound(transferId));
        }
        if (ledgerRecorder == null) {
            return Outcome.refused(transfer, "the custody ledger recorder is not wired on this "
                    + "node, so this handover cannot be recorded and custody must not pass.");
        }
        String at = Instant.now().toString();
        CustodyLedgerRecorder.Authorisation authorisation =
                ledgerRecorder.recordVerifiedReceipt(transfer, at);
        if (!authorisation.mayProceed()) {
            logger.warn("Custody of {} did not pass: {}", transfer.objectId(),
                    authorisation.refusedReason());
            return Outcome.refused(transfer, authorisation.refusedReason());
        }
        CustodyTransfer.Moved moved = transfer.advance(CustodyState.CUSTODY_TRANSFERRED, at,
                "the handover was recorded in the evidence chain");
        if (!moved.accepted()) {
            // The entry is already in the chain and the transfer did not move. Said out loud
            // rather than swallowed: the chain now holds a handover the transfer does not, and
            // an operator reconciling the two has to know which way round it is.
            logger.warn("The handover of {} was recorded but the transfer did not move: {}",
                    transfer.objectId(), moved.refusedReason());
            return Outcome.refused(transfer, "this handover was recorded in the evidence chain, "
                    + "but the transfer would not move: " + moved.refusedReason()
                    + ". The chain now holds an entry this transfer does not reflect.");
        }
        return persist(transfer, "this handover was recorded in the evidence chain, but the "
                + "transfer was not written. The chain holds an entry this transfer does not "
                + "reflect.");
    }

    /** The transfer as it stands, or null. */
    public CustodyTransfer find(String repositoryId, String transferId) {
        return load(repositoryId, transferId);
    }

    /** Every transfer for one record, newest first. */
    public List<CustodyTransfer> findByObject(String repositoryId, String objectId, int limit) {
        if (notStorable()) {
            return List.of();
        }
        List<CustodyTransfer> found = store.findByObject(repositoryId, objectId, limit);
        return found == null ? List.of() : found;
    }

    private Outcome persist(CustodyTransfer transfer, String failureReason) {
        if (!store.save(transfer)) {
            return Outcome.refused(transfer, failureReason);
        }
        return Outcome.done(transfer);
    }

    private CustodyTransfer load(String repositoryId, String transferId) {
        if (notStorable()) {
            return null;
        }
        return store.find(repositoryId, transferId);
    }

    private boolean notStorable() {
        return store == null || !store.isActive();
    }

    private static String unstorable() {
        return "transfers cannot be stored on this node, so none can be opened. A transfer that "
                + "only exists in memory is lost at the next restart, and the receipt comes back "
                + "days later.";
    }

    private static String notFound(String transferId) {
        return "there is no transfer " + transferId + " on this node. This is NOT a statement "
                + "that the handover did not happen; it is a statement about what is stored "
                + "here.";
    }
}
