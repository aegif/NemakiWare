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
import java.util.Map;
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
 * everything before it is a note somebody recorded, and this one is where we stop
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

    /**
     * What happened, and what to tell whoever asked.
     *
     * @param signatureCheck what the signature check found, when one was made. Null on every
     *        path that did not examine a receipt.
     *        <p><b>Carried because the receipt cannot carry it.</b>
     *        {@code CustodyReceipt.signatureVerified} is one boolean and false has three
     *        producers — no key, a check that RAN and did not match, an unreadable signature.
     *        The verifier distinguishes all three and this service used to drop the result on
     *        the floor: {@code Checked.asMap()} had no caller anywhere in main, and a signature
     *        made with the wrong key left nothing behind but a WARN in the log. The operator was
     *        told the deployment held no key.
     */
    public record Outcome(boolean done, CustodyTransfer transfer, String refusedReason,
                          Map<String, Object> signatureCheck) {

        static Outcome done(CustodyTransfer transfer) {
            return new Outcome(true, transfer, null, null);
        }

        static Outcome done(CustodyTransfer transfer, Map<String, Object> signatureCheck) {
            return new Outcome(true, transfer, null, signatureCheck);
        }

        static Outcome refused(CustodyTransfer transfer, String reason) {
            return new Outcome(false, transfer, reason, null);
        }

        static Outcome refused(CustodyTransfer transfer, String reason,
                Map<String, Object> signatureCheck) {
            return new Outcome(false, transfer, reason, signatureCheck);
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
            // "so it does not exist. Nothing was sent and nothing is in flight." was here. The
            // commonest way this branch is reached is a transferId that is ALREADY STORED --
            // the id is caller-supplied, so re-POSTing one is ordinary, and the store logs that
            // case as "already stored under this id by another writer". The operator was told
            // the transfer does not exist BECAUSE one does, plus an assertion about the world
            // ("Nothing was sent") this node cannot make. notFound() eight methods along has
            // the careful version: "NOT a statement that the handover did not happen; it is a
            // statement about what is stored here."
            return Outcome.refused(null, "this transfer was not written. Either a transfer is "
                    + "already stored under this id, or the write did not take. Nothing is "
                    + "stored HERE for this attempt — which is not a statement about whether "
                    + "anything was sent.");
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
        // The forged-mapping check is NOT here. It lives on CustodyReceipt and is applied by
        // CustodyTransfer.verifyReceipt and by restore() -- because a row read back out of the
        // database is the other place a forged pair could arrive, and a check that only guarded
        // this method would have left that open.
        //
        // The signature is checked HERE, at the moment the receipt is examined — not read back
        // out of a row later. The stored flag is deliberately not trusted on reload (anything
        // with database access could set it), so the finding has to be made where the receipt
        // arrives and chained from there.
        ReceiptSignatureVerifier.Checked checked = checkSignature(receipt);
        // The finding travels. Dropping it was how a check that RAN and FAILED became
        // indistinguishable, to everyone outside this JVM, from a deployment with no key.
        Map<String, Object> signatureCheck = checked.asMap();
        CustodyTransfer.Moved moved = transfer.verifyReceipt(checked.receipt(),
                Instant.now().toString());
        if (!moved.accepted()) {
            return Outcome.refused(transfer, moved.refusedReason(), signatureCheck);
        }
        Outcome persisted = persist(transfer, "the receipt was checked but the result was not "
                + "written, so this process does not know where this transfer is now");
        return persisted.done()
                ? Outcome.done(persisted.transfer(), signatureCheck)
                : Outcome.refused(persisted.transfer(), persisted.refusedReason(), signatureCheck);
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
        // Two different sentences, because they are two different facts. "At least N" was the
        // first attempt and it still asserted an existence: one or more IS a claim that a
        // transfer is there, and when the view simply did not answer there may be none. The
        // store now says which case it is instead of folding both into the count.
        if (store.lastQueryFailed()) {
            return new Listed(found, false, "the stored history for this record could NOT BE "
                    + "QUERIED, so this list is empty because nothing could be read, not "
                    + "because nothing was sent. How many transfers exist is unknown — this is "
                    + "not a finding that there are any, and not a finding that there are none");
        }
        return new Listed(found, unread == 0, unread == 0 ? null
                : unread + " stored transfer(s) for this record could not be read and are NOT "
                        + "in this list, so it is not a complete answer about where this record "
                        + "has been");
    }

    /** A list, and what it is missing. */
    public record Listed(List<CustodyTransfer> transfers, boolean complete, String incomplete) {}

    /**
     * Checks the receipt's signature when this deployment holds a key for its agent.
     *
     * <p>No key is "not checked", which is a statement about this deployment — not a finding
     * that the signature is bad. Whose key it is remains a submission agreement question;
     * configuration only says which bytes to check against.
     */
    private ReceiptSignatureVerifier.Checked checkSignature(CustodyReceipt receipt) {
        if (receipt == null || propertyManager == null) {
            return ReceiptSignatureVerifier.verify(receipt, null, "SHA256withRSA");
        }
        String algorithm = propertyManager.readValue(
                jp.aegif.nemaki.util.constant.PropertyKey.CUSTODY_RECEIPT_SIGNATURE_ALGORITHM);
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = "SHA256withRSA";
        }
        java.security.PublicKey key = null;
        String agent = receipt.receivingAgent();
        if (agent != null && !agent.isBlank()) {
            String encoded = propertyManager.readValue(
                    jp.aegif.nemaki.util.constant.PropertyKey.CUSTODY_RECEIPT_KEY_PREFIX + agent);
            if (encoded != null && !encoded.isBlank()) {
                try {
                    key = java.security.KeyFactory.getInstance(
                                    algorithm.contains("RSA") ? "RSA" : "EC")
                            .generatePublic(new java.security.spec.X509EncodedKeySpec(
                                    java.util.Base64.getDecoder().decode(encoded.trim())));
                } catch (Exception e) {
                    // A key we cannot read is not a signature we can fault. Left null, which
                    // reports "not checked" with the reason rather than "invalid".
                    logger.warn("The configured key for {} could not be read ({}), so the "
                            + "receipt's signature was not checked", agent, e.getMessage());
                }
            }
        }
        ReceiptSignatureVerifier.Checked result =
                ReceiptSignatureVerifier.verify(receipt, key, algorithm);
        if (result.ran() && !result.valid()) {
            logger.warn("A custody receipt from {} did not verify against the configured key",
                    agent);
        }
        return result;
    }

    private jp.aegif.nemaki.util.PropertyManager propertyManager;

    /** Optional: without it no receipt signature is checked, and every result says so. */
    @Autowired(required = false)
    public void setPropertyManager(jp.aegif.nemaki.util.PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    private Outcome persist(CustodyTransfer transfer, String failureReason) {
        boolean saved;
        try {
            saved = store.save(transfer);
        } catch (RuntimeException e) {
            // The store can throw before it reaches its own conflict handling — getting a
            // client, building the document. (It no longer reads the current revision at write
            // time, and it does not retry; see CouchCustodyTransferStore.save and design §15.)
            // On the passCustody path the ledger entry is already
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
