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

import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;

import java.util.List;
import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Puts a verified custody receipt into the evidence chain (P3-4).
 *
 * <h2>The bidirectional reference, made in time order</h2>
 *
 * <p>A package cannot name the AIP it will become: when the SIP is built, the far end's AIP id
 * does not exist. So the reference is made in two moves — the SIP carries a chain excerpt
 * outward, and the receipt comes back and is appended here, to be folded into the next anchor.
 * From then on a disagreement between the two ends is detectable.
 *
 * <p>It does not freeze anything. Neither end is prevented from changing its copy; what changes
 * is that changing it becomes visible.
 *
 * <h2>Fail-CLOSED, like disposition and unlike capture</h2>
 *
 * <p>Custody passing is the step before a local copy may be deleted. Letting it pass on a
 * handover this repository could not record would mean the one record of who became answerable
 * for the document has a hole exactly where the handover is — and the next legitimate step is
 * to destroy the local copy.
 *
 * <p>Refusing costs a delay: the transfer stays at RECEIPT_VERIFIED, the record stays here, and
 * the operator tries again. Those are not comparable, so this refuses.
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md}.
 */
@Component
public class CustodyLedgerRecorder {

    private static final Logger logger = LoggerFactory.getLogger(CustodyLedgerRecorder.class);

    /** Domain-separated from every other digest in the product. */
    static final String CUSTODY_DIGEST_DOMAIN = "LEDGER_CUSTODY_RECEIPT_V1";

    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Whether custody may be recorded as passed, and why not when it may not. */
    public record Authorisation(boolean mayProceed, String refusedReason) {

        static Authorisation granted() {
            return new Authorisation(true, null);
        }

        static Authorisation refused(String reason) {
            return new Authorisation(false, reason);
        }
    }

    /**
     * Records a verified receipt, and says whether custody may be recorded as passed.
     *
     * @return {@code mayProceed=false} when the entry could not be written. <b>The caller must
     *         not move to CUSTODY_TRANSFERRED.</b>
     */
    public Authorisation recordVerifiedReceipt(CustodyTransfer transfer, String occurredAt) {
        if (transfer == null || transfer.receipt() == null) {
            return Authorisation.refused("there is no verified receipt to record");
        }
        if (transfer.state() != CustodyState.RECEIPT_VERIFIED) {
            return Authorisation.refused("a receipt is recorded once it has been verified; this "
                    + "transfer is at " + transfer.state());
        }
        if (ledgerService == null) {
            logger.warn("No evidence ledger is wired; refusing to pass custody of {}",
                    transfer.objectId());
            return Authorisation.refused("the evidence ledger is not wired on this node, so this "
                    + "handover cannot be recorded and custody must not be recorded as passed");
        }
        EvidenceLedgerService.AppendResult result;
        try {
            String digest = receiptDigest(transfer);
            // Idempotent on the handover itself. The digest is deterministic — the same
            // transfer and the same receipt produce the same value — so an entry already
            // carrying it IS this handover, recorded. Without this, the honest failure path
            // ("recorded, but the transfer was not written") turns a retry into a SECOND
            // CUSTODY_RECEIPT for one handover, and the chain then says the record was handed
            // over twice.
            if (alreadyRecorded(transfer, digest)) {
                logger.info("The handover of {} is already in the chain; not appending it "
                        + "again", transfer.objectId());
                return Authorisation.granted();
            }
            result = ledgerService.append(transfer.repositoryId(),
                    EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT, transfer.objectId(), digest,
                    occurredAt);
        } catch (RuntimeException e) {
            logger.warn("Refusing to pass custody of {}: the receipt could not be chained ({})",
                    transfer.objectId(), e.getMessage());
            return Authorisation.refused("this handover could not be recorded (" + e.getMessage()
                    + "), so custody has NOT passed. The record is still here and the next "
                    + "attempt will try again.");
        }
        if (result.recorded()) {
            return Authorisation.granted();
        }
        logger.warn("Refusing to pass custody of {}: the ledger did not accept the receipt ({})",
                transfer.objectId(), result.reason());
        return Authorisation.refused("this handover was not recorded (" + result.reason()
                + "), so custody has NOT passed. The record is still here and the next attempt "
                + "will try again.");
    }

    /** How far back the duplicate check looks. A cap, and the code says when it hit it. */
    private static final int ALREADY_RECORDED_SCAN = 500;

    /**
     * Whether this exact handover is already in the chain.
     *
     * <p>Read failures answer {@code false}: not finding it because the store could not be read
     * is not the same as it not being there, and the safe direction is to attempt the append —
     * which the ledger itself can refuse — rather than to report a handover as recorded on the
     * strength of a lookup that did not run.
     *
     * <p>This paragraph used to sit above the constant, with the constant's own one-liner
     * between it and this method — so javadoc dropped it and the method it describes had none.
     */
    private boolean alreadyRecorded(CustodyTransfer transfer, String digest) {
        try {
            // Through the SERVICE, not a second injection of the store. A deployment with the
            // service wired and the store not would have lost this check silently, and
            // everything keeps working until the retry that appends twice.
            List<EvidenceLedgerEntry> entries = ledgerService.entriesFor(transfer.repositoryId(),
                    transfer.objectId(), ALREADY_RECORDED_SCAN);
            for (EvidenceLedgerEntry entry : entries) {
                if (entry.subjectKind() == EvidenceLedgerEntry.SubjectKind.CUSTODY_RECEIPT
                        && digest.equals(entry.payloadDigest())) {
                    return true;
                }
            }
            int undecodable = ledgerService.lastUnreadableCount();
            if (undecodable > 0) {
                // Rows the store returned and could not decode are NOT in `entries`, so "it is
                // not among them" is not "it is not there". Same WARN as the read that threw:
                // the decision to append anyway stands, and it stops being silent.
                logger.warn("{} chain row(s) for {} could not be decoded, so the duplicate check "
                        + "for this handover was made against an incomplete list; the append "
                        + "that follows may write a second CUSTODY_RECEIPT for one handover",
                        undecodable, transfer.objectId());
            }
            if (entries.size() >= ALREADY_RECORDED_SCAN) {
                // Said out loud. findBySubject answers in ascending order with the limit applied
                // by the view, so a full result means the entries NOT read are the most recent
                // — exactly where a handover recorded minutes ago would be. Not finding it here
                // therefore does not mean it is not there, and the append that follows may be a
                // second entry for one handover.
                logger.warn("The first {} chain entries for {} were scanned for an existing "
                        + "handover and it was not among them; there are more, and the ones not "
                        + "read are the most recent, so this append may duplicate one",
                        ALREADY_RECORDED_SCAN, transfer.objectId());
            }
        } catch (RuntimeException e) {
            // WARN, not DEBUG. Answering false here is deliberate — see the javadoc — but it is
            // a decision to append WITHOUT the duplicate check, and at DEBUG nothing recorded
            // that the check had not run. The store two layers down was just changed to throw
            // rather than answer "the chain holds nothing" for a view that did not reply, and
            // its comment names THIS caller as one of the three consumers that must not read
            // the empty answer; the throw arrives here and is turned back into the same false.
            //
            // The behaviour stays (a duplicate the operator can see beats a handover nobody
            // recorded), but it stops being silent: this line is the only trace that an
            // append-only entry was written without checking for its twin.
            logger.warn("The duplicate check for the handover of {} could not run ({}), so the "
                    + "append that follows is NOT protected against writing a second "
                    + "CUSTODY_RECEIPT for one handover", transfer.objectId(), e.getMessage());
        }
        return false;
    }

    /**
     * The canonical digest of a handover.
     *
     * <p>Commits to both ends of the reference: OUR package ({@code sipDigest}) and THEIR
     * artefact ({@code aipId} / {@code aipChecksum}), plus who said so. A digest over their side
     * alone would be a commitment to a value we have never seen; over ours alone it would not
     * record the handover at all.
     *
     * <p><b>It does not distinguish a receipt whose signature was VERIFIED from an otherwise
     * identical one taken on trust.</b> The input is there, and in the flow this product
     * actually has it is always {@code false} — see the comment beside it. It does still
     * separate a signed receipt from an unsigned one, which is a different fact. Say what the
     * entry establishes, and do not let the presence of an input read as the presence of a
     * distinction.
     */
    static String receiptDigest(CustodyTransfer transfer) {
        CustodyReceipt receipt = transfer.receipt();
        return LineageCanonicalHash.hash(CUSTODY_DIGEST_DOMAIN,
                transfer.repositoryId(), transfer.objectId(), transfer.sipDigest(),
                receipt.submissionId(), receipt.aipId(), receipt.aipChecksum(),
                receipt.verificationOutcome(),
                // The receiver's own word too, when a connector mapped it into our vocabulary.
                // Committing only to the mapped word would let the mapping be rewritten later
                // without the entry changing -- and "what did they actually say" is the part a
                // dispute turns on. Empty when nothing was mapped.
                receipt.reportedOutcome() == null ? "" : receipt.reportedOutcome(),
                receipt.receivingAgent(),
                // WHETHER IT WAS SIGNED. This one is live: `signature` is stored on the row
                // and read back, so the digest really does separate a signed receipt from an
                // unsigned one.
                String.valueOf(receipt.signature() != null && !receipt.signature().isBlank()),
                // WHETHER THAT WAS CHECKED — and in the REST flow this input is ALWAYS "false".
                //
                // Measured, not reasoned (StaleWritesAreRefusedTest): passCustody LOADS the
                // transfer, and the store's decode deliberately forces signatureVerified to
                // false, because "a finding read back out of a row anyone with database access
                // can edit is an assertion wearing a finding's name". That rule is right. The
                // consequence is that by the time this digest is taken the finding is gone.
                //
                // PRECISELY: two receipts that differ ONLY in whether the signature was
                // verified digest identically. They still differ if one is signed and the
                // other is not, because the input above is live — so "a verified receipt and a
                // trusted one always digest the same" is too strong, and the first draft of
                // this correction said it. What is lost is the VERIFICATION, not the signature.
                //
                // This comment used to claim the opposite — "an entry that digested the same
                // for both would lose the distinction the moment it mattered" — describing a
                // property the code does not have. It also used to say the gap was "two bits
                // for three facts", i.e. a migration away from being fixed by adding an input.
                // Adding an input would not fix it: the fact is not present here to commit.
                //
                // Committing it needs one of two things, and neither is a wording change:
                // digesting at VERIFICATION time rather than at handover time, or persisting
                // the finding — which is what the store refuses, for a good reason. Design §29.
                String.valueOf(receipt.signatureVerified()));
    }
}
