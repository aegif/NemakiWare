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

    /**
     * The canonical digest of a handover.
     *
     * <p>Commits to both ends of the reference: OUR package ({@code sipDigest}) and THEIR
     * artefact ({@code aipId} / {@code aipChecksum}), plus who said so. A digest over their side
     * alone would be a commitment to a value we have never seen; over ours alone it would not
     * record the handover at all.
     */
    static String receiptDigest(CustodyTransfer transfer) {
        CustodyReceipt receipt = transfer.receipt();
        return LineageCanonicalHash.hash(CUSTODY_DIGEST_DOMAIN,
                transfer.repositoryId(), transfer.objectId(), transfer.sipDigest(),
                receipt.submissionId(), receipt.aipId(), receipt.aipChecksum(),
                receipt.verificationOutcome(), receipt.receivingAgent(),
                // Whether it was signed AND whether that was checked. A receipt taken on trust
                // and one whose signature was verified are different facts, and an entry that
                // digested the same for both would lose the distinction the moment it mattered.
                String.valueOf(receipt.signature() != null && !receipt.signature().isBlank()),
                String.valueOf(receipt.signatureVerified()));
    }
}
