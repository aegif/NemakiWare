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
            return Outcome.refused(null, whyNotFound(transferId));
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
            return Outcome.refused(null, whyNotFound(transferId));
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
            return Outcome.refused(null, whyNotFound(transferId));
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
        CustodyTransfer.Moved moved = transfer.passCustody(at,
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

    /** The transfer as it stands, and why not when there is none. */
    public Found find(String repositoryId, String transferId) {
        CustodyTransfer transfer = load(repositoryId, transferId);
        if (transfer != null) {
            unreadable.remove();
            return new Found(transfer, null, null);
        }
        String why = unreadable.get();
        unreadable.remove();
        return why != null
                ? new Found(null, Found.Absence.UNREADABLE, why)
                : new Found(null, Found.Absence.NOT_STORED, notFound(transferId));
    }

    /**
     * A transfer, or the reason there is none.
     *
     * <p>Two reasons, and they are not the same: nothing is stored, or something is stored and
     * could not be read. A method returning null collapses them, and the collapsed answer —
     * "there is no transfer" — is the reassuring one. The KIND is a field rather than something
     * a caller infers from the wording: a caller matching on the sentence breaks the first time
     * the sentence is improved, and it breaks towards the reassuring answer.
     */
    public record Found(CustodyTransfer transfer, Absence absence, String absent) {

        public enum Absence {
            /** Nothing is stored under that id. */
            NOT_STORED,
            /** A row exists and could not be read back through the state machine. */
            UNREADABLE
        }
    }

    /**
     * Every transfer for one record, newest first, and whether the list is complete.
     *
     * <p>The completeness travels with the list. A list that silently dropped rows it could not
     * read looks like a complete answer, and "this record was never sent anywhere" is exactly
     * the conclusion a dropped row invites.
     */
    public Listed findByObject(String repositoryId, String objectId, int limit) {
        if (notStorable()) {
            return new Listed(List.of(), false, unstorable());
        }
        List<CustodyTransfer> found = store.findByObject(repositoryId, objectId, limit);
        if (found == null) {
            return new Listed(List.of(), false, "the store did not answer, which is not the "
                    + "same as there being no transfers");
        }
        int unread = store.unreadableCount();
        return new Listed(found, unread == 0, unread == 0 ? null
                : unread + " stored transfer(s) for this record could not be read and are NOT "
                        + "in this list, so it is not a complete answer about where this record "
                        + "has been");
    }

    /** A list, and what it is missing. */
    public record Listed(List<CustodyTransfer> transfers, boolean complete, String incomplete) {}

    private Outcome persist(CustodyTransfer transfer, String failureReason) {
        boolean saved;
        try {
            saved = store.save(transfer);
        } catch (RuntimeException e) {
            // The store can throw before it reaches its own retry loop — getting a client,
            // reading the current revision. On the passCustody path the ledger entry is already
            // written by then, so letting this propagate would replace the one message an
            // operator needs (the chain and the transfer disagree) with a stack trace, and a
            // retry would append a second custody entry.
            logger.warn("A custody transfer could not be written: {}", e.getMessage());
            saved = false;
        }
        if (!saved) {
            // NOT the object in hand. advance() mutated it before the write was attempted, so
            // returning it would show a caller `state=SENT` while the database holds whatever
            // the winning writer put there — the losing speculative state presented as current.
            // Null says what is true: this process does not know what the transfer is now.
            return Outcome.refused(null, failureReason);
        }
        return Outcome.done(transfer);
    }

    /**
     * The stored transfer, or null when there is none.
     *
     * <p>Throws nothing. A row that exists and cannot be read is a different fact from "there
     * is no such transfer" — the store raises it rather than collapsing the two — but letting
     * that reach a caller as an exception replaces the one sentence they need with a stack
     * trace and a 500. {@link #unreadable} carries it as a refusal instead.
     */
    private CustodyTransfer load(String repositoryId, String transferId) {
        unreadable.remove();
        if (notStorable()) {
            // Cleared FIRST. The early return used to skip the clear, so a diagnosis left by an
            // earlier request could be reported here — and, normally, this fell through to
            // "there is no transfer", which is a 404 for a node that could not look.
            unreadable.set(unstorable());
            return null;
        }
        try {
            return store.find(repositoryId, transferId);
        } catch (RuntimeException e) {
            // Cleared at entry, set only here: a diagnosis left behind by an earlier request on
            // a pooled thread would otherwise be reported as this one's.
            unreadable.set(e.getMessage());
            return null;
        }
    }

    /**
     * Why the last {@link #load} found nothing, when the reason was not "nothing is there".
     *
     * <p>A thread-local because the answer belongs to one call and the alternative — a field —
     * would let one request's diagnosis be reported on another's.
     */
    private final ThreadLocal<String> unreadable = new ThreadLocal<>();

    private String whyNotFound(String transferId) {
        String reason = unreadable.get();
        unreadable.remove();
        return reason != null ? reason : notFound(transferId);
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
