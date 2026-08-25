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

import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.fixity.FixityVerifier;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "what is going stale, and which renewal does it need?" (P2-3).
 *
 * <p>It reports; it does not renew. Hash-tree renewal reads every archived object, and choosing
 * to spend that is the operator's, not a default this project can justify (the same reason the
 * fixity pass has no schedule).
 *
 * <p>Design: {@code docs/design/p2-3-long-term-validity.md}.
 */
@Component
public class LongTermValidityService {

    /** Where the ledger's own hashes come from, so a rename cannot leave this looking elsewhere. */
    static final String LEDGER_HASH_ALGORITHM = "SHA-256";

    /**
     * The attribute an anchor receipt records its MESSAGE IMPRINT algorithm under.
     *
     * <p>{@code Rfc3161AnchorTarget} writes {@code digestAlgorithm}, and that is the imprint —
     * the hash of the value being stamped. It is NOT the algorithm that signed the token, which
     * is what RFC 4998's timestamp renewal actually fires on and which nothing in this product
     * records. Two names, two meanings; treating the first as the second reports a token as
     * sound because its imprint is, while its CMS signature may already be broken.
     */
    static final String IMPRINT_ALGORITHM_ATTRIBUTE = "digestAlgorithm";

    /** How many receipts one assessment reads before it stops — and says that it stopped. */
    static final int RECEIPT_SCAN_LIMIT = 1000;

    private AlgorithmRegistry registry = AlgorithmRegistry.withDefaults();
    private AnchorReceiptStore receiptStore;

    public void setRegistry(AlgorithmRegistry registry) {
        this.registry = registry == null ? AlgorithmRegistry.withDefaults() : registry;
    }

    @Autowired(required = false)
    public void setReceiptStore(AnchorReceiptStore receiptStore) {
        this.receiptStore = receiptStore;
    }

    public AlgorithmRegistry registry() {
        return registry;
    }

    /**
     * Assesses the algorithms this deployment's evidence rests on.
     *
     * @param when the date to judge against. A parameter rather than "now" so an operator can ask
     *             "what will be due in two years?" — which is the only useful question, given
     *             that renewal applied after a break does not reach backwards.
     */
    public Map<String, Object> assess(String repositoryId, LocalDate when) {
        List<RenewalNeed> needs = new ArrayList<>();
        needs.add(ledgerNeed(when));
        needs.add(fixityNeed(when));
        List<RenewalNeed> anchors = anchorNeeds(repositoryId, when);
        int assessedReceipts = anchors.size();
        needs.addAll(anchors);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryId", repositoryId);
        body.put("assessedFor", when.toString());
        body.put("declarationIsNotAWarranty", AlgorithmRegistry.DEFAULTS_ARE_NOT_A_WARRANTY);
        List<Map<String, Object>> rows = new ArrayList<>(needs.size());
        // Counted per kind, never summed: the two renewals differ by whether every archived
        // object has to be read, so one total would hide the only number that costs money.
        int timestampRenewals = 0;
        int hashTreeRenewals = 0;
        int undetermined = 0;
        for (RenewalNeed need : needs) {
            rows.add(need.asMap());
            switch (need.kind()) {
                case TIMESTAMP_RENEWAL -> timestampRenewals++;
                case HASH_TREE_RENEWAL -> hashTreeRenewals++;
                case UNDETERMINED -> undetermined++;
                case NONE -> { }
            }
        }
        body.put("timestampRenewalsDue", timestampRenewals);
        // Said out loud rather than left to be inferred from a zero. RFC 4998's timestamp
        // renewal fires on the TOKEN'S SIGNATURE algorithm, and no rung records that, so this
        // count can only ever be 0 until one does. A zero here is "not looked at", not "fine".
        body.put("timestampRenewalsNote", "No rung records the algorithm that SIGNED its token, "
                + "so signature-driven timestamp renewal is NOT assessed and this count is "
                + "structurally 0. What is assessed is the message imprint, whose failure is a "
                + "hash-tree renewal.");
        body.put("hashTreeRenewalsDue", hashTreeRenewals);
        body.put("undetermined", undetermined);
        body.put("needs", rows);
        if (assessedReceipts >= RECEIPT_SCAN_LIMIT) {
            // No silent caps. An assessment that stopped at 1000 and reports ordinary totals
            // reads as "everything is accounted for".
            body.put("receiptsTruncated", true);
            body.put("receiptsTruncatedNote", "Only the first " + RECEIPT_SCAN_LIMIT
                    + " confirmed receipts were assessed; there are more, and they were NOT "
                    + "looked at.");
        } else {
            body.put("receiptsTruncated", false);
        }
        return body;
    }

    private RenewalNeed ledgerNeed(LocalDate when) {
        AlgorithmRegistry.Soundness soundness =
                registry.soundnessOf(LEDGER_HASH_ALGORITHM, when);
        // The ledger's chain and Merkle tree are built from this hash, so its failure is a
        // hash-tree renewal — the expensive one.
        return new RenewalNeed(kindForTree(soundness), "evidence ledger chain and Merkle tree",
                LEDGER_HASH_ALGORITHM, soundness,
                "entryHash, checkpoint hashes and every inclusion proof rest on this");
    }

    private RenewalNeed fixityNeed(LocalDate when) {
        String algorithm = FixityVerifier.ALGORITHM;
        AlgorithmRegistry.Soundness soundness = registry.soundnessOf(algorithm, when);
        return new RenewalNeed(kindForTree(soundness), "stored content digests", algorithm,
                soundness, "nemaki:contentHash and every fixity verdict rest on this");
    }

    private List<RenewalNeed> anchorNeeds(String repositoryId, LocalDate when) {
        List<RenewalNeed> needs = new ArrayList<>();
        if (receiptStore == null) {
            needs.add(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "anchor receipts", null,
                    null, "the anchor receipt store is not wired, so no token could be read. "
                            + "This is NOT a finding that there are no anchors."));
            return needs;
        }
        List<AnchorReceiptStore.PendingReceipt> rows;
        try {
            // confirmed(), NOT pending(). The first version asked the pending-only query for
            // confirmed receipts and then filtered — a loop whose body could never run, so
            // timestampRenewalsDue was structurally always 0 and no deployed token was ever
            // assessed. Three reviewers found it independently.
            rows = receiptStore.confirmed(repositoryId, RECEIPT_SCAN_LIMIT);
        } catch (RuntimeException e) {
            needs.add(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "anchor receipts", null,
                    null, "the receipts could not be read (" + e.getMessage() + ")"));
            return needs;
        }
        for (AnchorReceiptStore.PendingReceipt row : rows) {
            AnchorReceipt receipt = row.receipt();
            if (receipt.status() != AnchorStatus.CONFIRMED) {
                // A pending or failed receipt has no token to renew. Reporting a renewal need
                // for it would put work on a list that cannot be done.
                continue;
            }
            // The imprint, which is all any rung records.
            String algorithm = receipt.attributes().get(IMPRINT_ALGORITHM_ATTRIBUTE);
            AlgorithmRegistry.Soundness soundness = registry.soundnessOf(algorithm, when);
            // A failing IMPRINT means the value has to be re-hashed and re-stamped, which needs
            // the archived data — the expensive renewal, not the cheap one. Only a failing
            // SIGNATURE is a timestamp renewal, and no rung records the signature algorithm.
            needs.add(new RenewalNeed(kindForTree(soundness),
                    "anchor receipt " + receipt.kind() + " @" + row.toSequence() + " (imprint)",
                    algorithm, soundness, algorithm == null
                            ? "this rung records no imprint algorithm"
                            : "the message imprint's algorithm. The algorithm that SIGNED the "
                                    + "token is not recorded by any rung, so its state is "
                                    + "unknown and is not assessed here"));
        }
        return needs;
    }

    private static RenewalNeed.Kind kindForTree(AlgorithmRegistry.Soundness soundness) {
        return switch (soundness) {
            case SOUND -> RenewalNeed.Kind.NONE;
            // DEPRECATED is already a need: acting only at UNSOUND means acting after the break,
            // and renewal does not reach backwards.
            case DEPRECATED, UNSOUND -> RenewalNeed.Kind.HASH_TREE_RENEWAL;
            case UNKNOWN -> RenewalNeed.Kind.UNDETERMINED;
        };
    }

    private static RenewalNeed.Kind kindForToken(AlgorithmRegistry.Soundness soundness) {
        return switch (soundness) {
            case SOUND -> RenewalNeed.Kind.NONE;
            case DEPRECATED, UNSOUND -> RenewalNeed.Kind.TIMESTAMP_RENEWAL;
            case UNKNOWN -> RenewalNeed.Kind.UNDETERMINED;
        };
    }
}
