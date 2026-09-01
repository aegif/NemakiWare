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
        AnchorNeeds anchors = anchorNeeds(repositoryId, when);
        needs.addAll(anchors.needs());

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
        // No silent caps. An assessment that stopped at 1000 and reports ordinary totals reads
        // as "everything is accounted for".
        //
        // Three corrections in one place. It counted NEEDS, not the rows read: a row that is not
        // CONFIRMED yields no need, the store drops undecodable rows before this method sees
        // them, and each of the four "could not ask" arms yields exactly one need — so an
        // unwired receipt store answered `false`, meaning "everything was looked at", having
        // looked at nothing. And `>=` establishes only that the scan REACHED the cap; exactly
        // 1000 receipts with none beyond them is not truncation. FixityScanReport draws the
        // same distinction in this release and this class did not.
        if (anchors.rowsRead() == null) {
            body.put("receiptsTruncated", null);
            body.put("receiptsTruncatedNote", "the receipt store was not successfully asked, so "
                    + "whether every receipt was assessed is unknown. This is NOT a statement "
                    + "that all of them were.");
        } else if (anchors.rowsRead() >= RECEIPT_SCAN_LIMIT) {
            body.put("receiptsTruncated", true);
            body.put("receiptsTruncatedNote", "The scan stopped at its limit of "
                    + RECEIPT_SCAN_LIMIT + " confirmed receipts. Receipts beyond that were NOT "
                    + "looked at — whether any exist is unknown, because reaching the cap is "
                    + "all this establishes.");
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

    /**
     * The renewal needs, and how many receipt ROWS were read to find them.
     *
     * <p>{@code rowsRead} is {@code null} when the store was never successfully asked. The
     * truncation flag used to be computed from {@code needs.size()} instead, and the two differ
     * three ways: a row that decodes to something other than CONFIRMED produces no need; the
     * store drops rows it cannot decode before this method sees them; and each of the four
     * early returns below produces exactly ONE need — so a repository whose receipt store was
     * unwired reported {@code receiptsTruncated: false}, i.e. "everything was looked at", having
     * looked at nothing.
     */
    private record AnchorNeeds(List<RenewalNeed> needs, Integer rowsRead) {
    }

    private AnchorNeeds anchorNeeds(String repositoryId, LocalDate when) {
        List<RenewalNeed> needs = new ArrayList<>();
        if (receiptStore == null) {
            needs.add(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "anchor receipts", null,
                    null, "the anchor receipt store is not wired, so no token could be read. "
                            + "This is NOT a finding that there are no anchors."));
            return new AnchorNeeds(needs, null);
        }
        if (!receiptStore.isActive()) {
            // Wired but unanswerable. Without this the wired-and-empty case emitted NO ROW at
            // all, so the response read `hashTreeRenewalsDue: 0, undetermined: 0` -- and the
            // UNWIRED case above got MORE honesty than the wired one, which is backwards. A
            // CouchDB view still building returns [] rather than throwing, so "the store said
            // nothing" and "there is nothing" were indistinguishable. AnchorReceiptStore's own
            // javadoc says callers must not read "no pending receipts" from a store that could
            // not be asked; this caller never asked.
            needs.add(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "anchor receipts", null,
                    null, "the anchor receipt store could not be reached, so no token could be "
                            + "read. This is NOT a finding that there are no anchors."));
            return new AnchorNeeds(needs, null);
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
            return new AnchorNeeds(needs, null);
        }
        // Rows the store dropped are not in `rows`, and rows() counts a view that answered
        // with nothing at all as one of them — so an empty list here can mean "the view is
        // rebuilding", not "nothing is anchored". The branch below states the second.
        //
        // AnchorService reads this count in both of its verbs and EvidenceRecordService was
        // corrected for it in this batch. This is the same store's THIRD caller, and its answer
        // goes to /long-term-validity, which is where an operator decides whether a renewal is
        // due — so "nothing is anchored" here reads as "there is nothing to renew".
        int unaccounted = receiptStore.unreadableCount();
        if (unaccounted > 0) {
            // The fourth consumer of the same count, split for the same reason as the other
            // three: "N row(s)" asserts rows exist, and a view that did not answer counts as
            // one while there may be none.
            needs.add(new RenewalNeed(RenewalNeed.Kind.UNDETERMINED, "anchor receipts", null,
                    null, receiptStore.lastQueryFailed()
                            ? "the anchor receipts could NOT BE QUERIED, so what is anchored is "
                                    + "unknown. This is not a finding that anything is, or is "
                                    + "not."
                            : unaccounted + " anchor receipt row(s) could not be accounted for, "
                                    + "so what is anchored is unknown. This is NOT a finding "
                                    + "that nothing is."));
            return new AnchorNeeds(needs, null);
        }
        if (rows.isEmpty()) {
            // An empty answer from a store that WAS asked. Distinct from the two branches
            // above: this one really did look. Said out loud so a reader is not left to infer
            // "there are none" from a missing row -- the same distinction the report layer
            // draws between ABSENT and UNAVAILABLE.
            needs.add(new RenewalNeed(RenewalNeed.Kind.NONE, "anchor receipts", null, null,
                    "the anchor receipt store was asked and holds no CONFIRMED receipt for this "
                            + "repository, so there is no token to renew — which also means "
                            + "nothing is anchored"));
            return new AnchorNeeds(needs, rows.size());
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
        return new AnchorNeeds(needs, rows.size());
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

    /**
     * The renewal a failing TOKEN SIGNATURE would need — kept, unwired, and unreachable.
     *
     * <p>No caller, and that is deliberate: p2-3 §5.5 records that no rung stores the algorithm
     * that SIGNED its token, so this product cannot tell whether a signature is still sound.
     * Wiring this to {@code digestAlgorithm} — the imprint's algorithm, which IS recorded — was
     * done once and is wrong: it answers a different question and reports timestamp renewals
     * that nothing established a need for.
     *
     * <p>Left in place because deleting it invites the same wiring to be written again from
     * scratch; the javadoc is the point of keeping it.
     */
    private static RenewalNeed.Kind kindForToken(AlgorithmRegistry.Soundness soundness) {
        return switch (soundness) {
            case SOUND -> RenewalNeed.Kind.NONE;
            case DEPRECATED, UNSOUND -> RenewalNeed.Kind.TIMESTAMP_RENEWAL;
            case UNKNOWN -> RenewalNeed.Kind.UNDETERMINED;
        };
    }
}
