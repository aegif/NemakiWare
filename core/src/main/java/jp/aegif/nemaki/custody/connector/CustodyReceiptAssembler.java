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
package jp.aegif.nemaki.custody.connector;

import jp.aegif.nemaki.custody.CustodyReceipt;

import java.util.Locale;

/**
 * Builds a {@link CustodyReceipt} out of what can be recovered from a receiver — and refuses to
 * build one when what comes back is not this transfer's package.
 *
 * <p><b>"What a receiver holds" is what this said, and it is not what is established.</b> On the
 * RODA route the recovered bytes are the ones submitted, so it happens to be true there (§16).
 * On the Archivematica route the recovered value is a line out of a manifest THIS product wrote
 * and the receiver stored (§17) — the comment beside the code has said so since it was written,
 * while the class contract above it said the stronger thing.
 *
 * <h2>What this layer is</h2>
 *
 * <p>Neither measured receiver exposes anything that is RECOGNISABLY a receipt — §10 walked the
 * stronger claim back after an external review, and what was actually established is that no such
 * resource appears among RODA's 26 v2 API controllers. That is not the same as "returns no
 * receipt": a job report, or the response to the submission itself, has not been ruled out as
 * one. CustodyReceipt.missingRequiredField carries the corrected wording; this file had the
 * withdrawn one, citing the section that withdrew it. Both return a workflow
 * state and an artefact of their own, in their own vocabulary, with no digest of what was
 * submitted. So a receipt has to be assembled, and assembling it is where two receiver-shaped
 * traps get handled:
 *
 * <ul>
 *   <li>the word — {@link ReceivingSystem}, pure, no I/O</li>
 *   <li>the digest — {@link SubmittedDigestRecovery}, one GET</li>
 * </ul>
 *
 * <p>Both are the same shape: <b>what comes back most readily is not what the receipt needs</b>.
 * The state is about the receiver's workflow; the digest is of the receiver's artefact. What a
 * receipt has to tie itself to is OUR package, and that is always one step further in.
 *
 * <h2>What this layer is not</h2>
 *
 * <p><b>It does not send.</b> The identifiers come in as arguments; how a package got to the
 * receiver, and how a caller learned the transfer finished, are the next increment. Nothing here
 * polls, drives the state machine, or calls {@code passCustody} — the single door to
 * {@code CUSTODY_TRANSFERRED} is unchanged.
 *
 * <p><b>It does not verify signatures.</b> Neither measured receiver signs, so every receipt
 * built here has {@code signatureVerified = false}. THIS LAYER never sets it true — which is not
 * the same as "it stays false": ReceiptSignatureVerifier.withVerified rebuilds a receipt with it
 * set, and Inputs.signature is only USUALLY null. The finding is made in
 * {@code CustodyTransferService}, from key material a submission agreement supplies, and saying
 * the value is frozen here would tell the next reader a verified receipt cannot exist.
 *
 * <h2>The refusal is the point</h2>
 *
 * <p>When the recovered digest does not match the package this transfer sent, <b>no receipt is
 * built</b>. The tempting alternative — fill {@code sipDigest} from our own record and let the
 * comparison pass — produces a check that cannot fail. §2 refuses exactly that, and the
 * refusal here is what keeps §2 true once a connector exists.
 */
public final class CustodyReceiptAssembler {

    private final SubmittedDigestRecovery recovery;

    public CustodyReceiptAssembler() {
        this(new SubmittedDigestRecovery());
    }

    public CustodyReceiptAssembler(SubmittedDigestRecovery recovery) {
        this.recovery = recovery;
    }

    /**
     * A receipt, or the reason there is not one.
     *
     * <p>A refusal is not an error: "what came back is something else" is a finding about the
     * handover, and it has to reach the operator as words rather than as an exception.
     */
    public record Assembled(CustodyReceipt receipt, String refusedReason) {

        public boolean assembled() {
            return receipt != null;
        }

        static Assembled refused(String reason) {
            return new Assembled(null, reason);
        }
    }

    /**
     * Everything a receipt needs that this layer cannot work out for itself.
     *
     * @param receiver which receiver, i.e. which field and which vocabulary
     * @param baseUrl where to fetch the digest evidence from — for Archivematica this is the
     *        STORAGE SERVICE (:62081), NOT the Dashboard the reportedWord came from. They are
     *        different hosts and the Dashboard answers 404 for extract_file, which arrives as
     *        "the receiver could not be read" and reads like the receiver being down
     * @param submissionId the id this transfer was opened with
     * @param aipId the receiver's identifier for what it produced
     * @param aipChecksum the receiver's checksum of its OWN artefact — carried because the
     *        roadmap's receipt contents list it, never used as {@code sipDigest}
     * @param reportedWord the receiver's own word, from {@link ReceivingSystem#outcomeFieldName}
     * @param receivingAgent who is answerable at the far end
     * @param receivedAt when the receiver says it received
     * @param signature whatever signature came with it, usually null
     * @param expectedSipDigest <b>the digest of the E-ARK SIP</b> — not of the bag it may have
     *        travelled in. This is the value the recovery is compared against, and it is never
     *        copied into the receipt.
     *        <p>The distinction is not pedantry, and it bites on exactly one receiver.
     *        RODA returns the submitted bytes, so a bag digest would match a bag and a SIP
     *        digest would match a SIP — either works, and nothing warns you. Archivematica
     *        returns a line from the shipped bag's {@code manifest-sha256.txt}, and a manifest
     *        describes its PAYLOAD, never itself: what comes back is always the SIP's digest.
     *        So a transfer opened with the bag's SHA-256 assembles fine against RODA and then
     *        <b>refuses every genuine Archivematica receipt</b>.
     *        <p>{@code EarkSipExportController} already passes {@code digestOf(exported.sip())}
     *        into the bag's {@code External-Description}; that is the value to remember. Design
     *        §13.2.
     */
    public record Inputs(
            ReceivingSystem receiver,
            String baseUrl,
            String submissionId,
            String aipId,
            String aipChecksum,
            String reportedWord,
            String receivingAgent,
            String receivedAt,
            String signature,
            String expectedSipDigest,
            String authorization,
            /** RODA only: the transferred resource still held by the receiver. */
            String transferredResourceUuid,
            /**
             * Archivematica only: where the shipped manifest sits inside the AIP.
             *
             * <p>Measured shape (design §17):
             * {@code {name}-{AIP uuid}/data/objects/metadata/transfers/{name}-{TRANSFER
             * uuid}/manifest-sha256.txt}. <b>The middle uuid is the transfer's, not the AIP's</b>,
             * and the AIP root holds a same-named manifest of Archivematica's own — see
             * {@link SubmittedDigestRecovery#fromArchivematicaManifest}.
             */
            String manifestRelativePath,
            /** Archivematica only: the payload's name, i.e. which manifest line to read. */
            String payloadName) {
    }

    /** Assembles, or says why not. */
    public Assembled assemble(Inputs in) {
        if (in.expectedSipDigest() == null || in.expectedSipDigest().isBlank()) {
            return Assembled.refused("this transfer does not name the digest of the SIP it sent, "
                    + "so there is nothing to check the receiver's copy against. A receipt built "
                    + "now could only compare our own value with itself");
        }

        ReceivingSystem.Outcome outcome = in.receiver().read(in.reportedWord());
        if (!outcome.readable()) {
            return Assembled.refused(outcome.unreadable() + ", so there is no verification "
                    + "result to record. A receipt that says nothing about the outcome is not a "
                    + "weaker receipt, it is a different object");
        }

        SubmittedDigestRecovery.Recovered recovered = recover(in);
        if (!recovered.present()) {
            return Assembled.refused(recovered.unavailable()
                    + ". No receipt is built: filling sipDigest from this repository's own "
                    + "record would make the later comparison our value against itself");
        }
        if (!recovered.sha256Hex().equalsIgnoreCase(in.expectedSipDigest().trim())) {
            // NOT "the receiver holds a different package". On the Archivematica branch the
            // recovered value is a line out of a manifest THIS product wrote, so what differs is
            // the recovered value, and the comment 30 lines below already said calling it "what
            // the far end has" would overstate it. The message says which value was compared;
            // recovered.source() names where it came from, and an operator reads that.
            return Assembled.refused("what was recovered is not this transfer's package. "
                    + recovered.source() + " digests to " + recovered.sha256Hex()
                    + ", and this transfer sent " + in.expectedSipDigest().toLowerCase(Locale.ROOT)
                    + ". Either could be the odd one out — most often it is OURS: a transfer opened "
        + "with the bag's SHA-256 assembles against RODA and then refuses every genuine "
        + "Archivematica receipt, because the two receivers are given different files. "
        + "Check which digest this transfer was opened with before asking the receiver");
        }

        return new Assembled(new CustodyReceipt(
                in.submissionId(),
                in.aipId(),
                in.aipChecksum(),
                // The RECOVERED value, not the expected one. Past the equalsIgnoreCase gate
                // above the two differ only in case, so this buys little today: it normalises to
                // whatever the receiver wrote, and it keeps the receiver's side of the record if
                // that gate is ever loosened. Not more than that -- for Archivematica the
                // "recovered" value is itself a line this product wrote and the receiver kept,
                // so calling it "what the far end has" would overstate it.
                recovered.sha256Hex(),
                outcome.verificationOutcome(),
                outcome.reportedOutcome(),
                in.receivingAgent(),
                in.receivedAt(),
                in.signature(),
                // Never set here. Neither measured receiver signs anything, and a connector
                // asserting a verification it did not perform is the failure this flag was
                // split out to prevent.
                false), null);
    }

    private SubmittedDigestRecovery.Recovered recover(Inputs in) {
        return switch (in.receiver()) {
            case RODA -> recovery.fromRodaTransfer(in.baseUrl(), in.transferredResourceUuid(),
                    in.authorization());
            case ARCHIVEMATICA -> recovery.fromArchivematicaManifest(in.baseUrl(), in.aipId(),
                    in.manifestRelativePath(), in.payloadName(), in.authorization());
        };
    }
}
