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
package jp.aegif.nemaki.evidence.validity;

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds an RFC 4998 evidence record from an anchor this repository already has (P2-3).
 *
 * <h2>Nothing new is timestamped</h2>
 *
 * <p>{@link ErsRecord} explains why the data object is a checkpoint's canonical bytes: the
 * checkpoint hash IS their SHA-256, and the RFC 3161 anchor over that checkpoint is a token
 * whose message imprint is exactly that value. So an evidence record can be assembled from what
 * is already stored — no second TSA round trip, no second anchor, and no new claim.
 *
 * <p>Which also means this can produce nothing a deployment does not already have. A checkpoint
 * with no CONFIRMED RFC 3161 receipt has no evidence record, and that is reported as an absence
 * of an anchor rather than as a failure: OpenTimestamps receipts are not RFC 3161 tokens and
 * cannot stand in one's place.
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md} §8.
 */
@Component
public class EvidenceRecordService {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceRecordService.class);

    private EvidenceLedgerStore ledgerStore;
    private AnchorReceiptStore anchorReceiptStore;

    @Autowired(required = false)
    public void setLedgerStore(EvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @Autowired(required = false)
    public void setAnchorReceiptStore(AnchorReceiptStore anchorReceiptStore) {
        this.anchorReceiptStore = anchorReceiptStore;
    }

    /** An evidence record, or why there is none. */
    public record Built(byte[] der, EvidenceCheckpoint checkpoint, String unavailable) {

        public boolean present() {
            return der != null;
        }

        /** What a reader must not conclude, whether or not there is a record. */
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("limits", ErsRecord.LIMITS);
            m.put("format", ErsFormat.CHOSEN.specification());
            m.put("present", present());
            if (!present()) {
                m.put("unavailable", unavailable);
            } else {
                m.put("coversCheckpoint", checkpoint.checkpointHash());
                m.put("fromSequence", checkpoint.fromSequence());
                m.put("toSequence", checkpoint.toSequence());
            }
            return m;
        }
    }

    /**
     * The evidence record for the newest sealed checkpoint of {@code domain}.
     *
     * <p>Never throws. Every reason there is no record is a statement about this deployment —
     * no ledger, no checkpoint, no confirmed RFC 3161 anchor — and none of them is a finding
     * about the records the checkpoint covers.
     */
    public Built latest(String domain) {
        if (ledgerStore == null || !ledgerStore.isActive()) {
            return absent("the evidence ledger is not available on this node");
        }
        EvidenceCheckpoint checkpoint;
        try {
            checkpoint = ledgerStore.latestCheckpoint(domain);
        } catch (RuntimeException e) {
            return absent("the latest checkpoint could not be read (" + e.getMessage() + ")");
        }
        if (checkpoint == null) {
            return absent("nothing has been sealed for " + domain + " yet, so there is no "
                    + "checkpoint for an evidence record to be about");
        }
        return forCheckpoint(domain, checkpoint);
    }

    /** As above, for one checkpoint. */
    public Built forCheckpoint(String domain, EvidenceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return absent("there is no checkpoint");
        }
        if (!checkpoint.selfVerifies()) {
            // A checkpoint that does not verify against its own fields is not a value to build
            // evidence over; doing so would wrap a bad root in a standard container and make
            // it look better.
            return absent("the checkpoint at " + checkpoint.toSequence() + " does not verify "
                    + "against its own fields, so no evidence record is built over it");
        }
        if (anchorReceiptStore == null || !anchorReceiptStore.isActive()) {
            return absent("anchor receipts are not available on this node, so the token an "
                    + "evidence record is built from cannot be read");
        }
        AnchorReceipt token = null;
        try {
            for (AnchorReceipt receipt
                    : anchorReceiptStore.forCheckpoint(domain, checkpoint.toSequence())) {
                if (receipt.kind() == AnchorKind.RFC3161_TSA
                        && receipt.status() == AnchorStatus.CONFIRMED
                        && receipt.proof() != null && receipt.proof().length > 0) {
                    token = receipt;
                    break;
                }
            }
        } catch (RuntimeException e) {
            return absent("the anchor receipts for checkpoint " + checkpoint.toSequence()
                    + " could not be read (" + e.getMessage() + ")");
        }
        if (token == null) {
            return absent("checkpoint " + checkpoint.toSequence() + " has no CONFIRMED RFC 3161 "
                    + "token. An OpenTimestamps receipt cannot stand in for one: an evidence "
                    + "record's timestamp is an RFC 3161 token, and this is a statement about "
                    + "what has been anchored — not about the records the checkpoint covers");
        }
        byte[] dataObjectHash;
        try {
            dataObjectHash = HexFormat.of().parseHex(checkpoint.checkpointHash());
        } catch (RuntimeException e) {
            return absent("the checkpoint hash is not a hex digest, so it cannot be the message "
                    + "imprint of an RFC 3161 token");
        }
        // The receipt's FIELD first, because a mismatch there is the cheap diagnosis.
        if (!checkpoint.checkpointHash().equalsIgnoreCase(token.anchoredDigest())) {
            return absent("the confirmed token for checkpoint " + checkpoint.toSequence()
                    + " is recorded as being over " + token.anchoredDigest() + ", and this "
                    + "checkpoint's hash is " + checkpoint.checkpointHash() + ". A record built "
                    + "from it would be about a different value");
        }
        // Then the TOKEN ITSELF. The field is this repository's own note about what it asked
        // for; the imprint is what the authority actually signed over, and they are two facts.
        // The dangerous combination is precisely a receipt whose field says "checkpoint" and
        // whose proof is over the merkle root — a value also to hand at anchoring time — which
        // produces a record that assembles cleanly and is about something else. Reading the
        // field alone would wave that through.
        byte[] imprint;
        try {
            imprint = new org.bouncycastle.tsp.TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(token.proof()))
                    .getTimeStampInfo().getMessageImprintDigest();
        } catch (Exception e) {
            return absent("the confirmed token for checkpoint " + checkpoint.toSequence()
                    + " could not be read (" + e.getMessage() + "), so what it covers is "
                    + "unknown and no record is built from it");
        }
        if (!java.util.Arrays.equals(imprint, dataObjectHash)) {
            return absent("the confirmed token for checkpoint " + checkpoint.toSequence()
                    + " was SIGNED over " + HexFormat.of().formatHex(imprint) + ", not over "
                    + "this checkpoint's hash. The receipt says otherwise; the token is the one "
                    + "that counts, and a record built from it would verify internally while "
                    + "being about a different value");
        }
        byte[] der;
        try {
            der = ErsRecord.first(dataObjectHash, token.proof()).der();
        } catch (RuntimeException e) {
            logger.warn("The evidence record for checkpoint {} could not be built: {}",
                    checkpoint.toSequence(), e.getMessage());
            return absent("the evidence record could not be built (" + e.getMessage() + ")");
        }
        // And read it back the way a receiver will. Assembling is cheap and shipping a record
        // a standard tool rejects is not: the package goes to another organisation, and "it
        // came out of the exporter" is not a reason for them to accept it.
        ErsVerifier.Report check = ErsVerifier.verify(der, dataObjectHash);
        if (!check.linksHold()) {
            logger.warn("The evidence record assembled for checkpoint {} does not verify: {}",
                    checkpoint.toSequence(), check.asMap());
            return absent("the evidence record was assembled and then did not verify against "
                    + "the checkpoint it is about, so it is not shipped. This is a fault in "
                    + "this deployment's anchoring, not a finding about the records covered");
        }
        return new Built(der, checkpoint, null);
    }

    private static Built absent(String why) {
        return new Built(null, null, why);
    }
}
