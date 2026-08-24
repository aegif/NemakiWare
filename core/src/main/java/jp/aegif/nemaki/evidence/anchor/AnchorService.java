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
package jp.aegif.nemaki.evidence.anchor;

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one checkpoint's root to every enabled tier (P2-0).
 *
 * <h2>Two refusals</h2>
 *
 * <ul>
 *   <li><b>It will not anchor over an unsettled tail.</b> If the ledger's highest sequence has
 *       moved past the checkpoint's, entries are still landing behind the root — and the claim
 *       "this root is the ledger as it stood" is false the moment it is made. The service
 *       returns a reason instead of anchoring and apologising later (P1-3's
 *       "no anchor while unsequenced backlog exists", implemented here).</li>
 *   <li><b>It will not let one tier's failure stop another.</b> Each target is called inside its
 *       own try, because the tiers exist precisely so a customer can rely on a different one.
 *       A tier that throws is reported {@code FAILED}; it does not become an exception the
 *       caller sees instead of the other tiers' results.</li>
 * </ul>
 */
public class AnchorService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorService.class);

    private final List<AnchorTarget> targets = new ArrayList<>();
    private EvidenceLedgerStore store;

    public void setTargets(List<AnchorTarget> targets) {
        this.targets.clear();
        if (targets != null) {
            this.targets.addAll(targets);
        }
    }

    public void setStore(EvidenceLedgerStore store) {
        this.store = store;
    }

    /** Every tier's receipt, plus what the set of them does and does not amount to. */
    public record Outcome(String domain, long toSequence, List<AnchorReceipt> receipts,
                          String refusedReason) {

        /** Tiers that actually confirmed. {@code SUBMITTED} is excluded — see {@link
         *  AnchorState}. */
        public List<String> confirmedTiers() {
            List<String> confirmed = new ArrayList<>();
            for (AnchorReceipt receipt : receipts) {
                if (receipt.counts()) {
                    confirmed.add(receipt.tierId());
                }
            }
            return confirmed;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", domain);
            m.put("toSequence", toSequence);
            m.put("refused", refusedReason != null);
            m.put("refusedReason", refusedReason);
            // The list, never a single flag: "anchored" as one word lets a deployment with only
            // the catalog tier borrow the sentence that belongs to a TSA.
            m.put("confirmedTiers", confirmedTiers());
            List<Map<String, Object>> rows = new ArrayList<>(receipts.size());
            for (AnchorReceipt receipt : receipts) {
                rows.add(receipt.asMap());
            }
            m.put("receipts", rows);
            return m;
        }
    }

    public Outcome anchor(EvidenceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return new Outcome(null, -1, List.of(), "there is no checkpoint to anchor");
        }
        String refusal = refusalFor(checkpoint);
        if (refusal != null) {
            // Nothing is sent. A root that is already out of date cannot be made current by
            // being anchored, and the anchor would be evidence of a claim that was false when
            // it was made.
            logger.warn("Refusing to anchor {} at {}: {}", checkpoint.domain(),
                    checkpoint.toSequence(), refusal);
            return new Outcome(checkpoint.domain(), checkpoint.toSequence(), List.of(), refusal);
        }
        List<AnchorReceipt> receipts = new ArrayList<>(targets.size());
        for (AnchorTarget target : targets) {
            receipts.add(receiptFrom(target, checkpoint));
        }
        return new Outcome(checkpoint.domain(), checkpoint.toSequence(), receipts, null);
    }

    private AnchorReceipt receiptFrom(AnchorTarget target, EvidenceCheckpoint checkpoint) {
        String limits;
        try {
            limits = target.claimLimits();
        } catch (RuntimeException e) {
            limits = null;
        }
        if (limits == null || limits.isBlank()) {
            // A tier that cannot say what it does not establish is not usable as evidence, and
            // the receipt type would refuse to be built anyway. Say which tier, once.
            limits = "tier '" + target.tierId() + "' did not declare what it does not establish, "
                    + "so nothing here may be read as a claim of any kind";
            return AnchorReceipt.failed(target.tierId(), checkpoint.domain(),
                    checkpoint.merkleRoot(), "the tier declared no claimLimits", limits);
        }
        if (!target.isEnabled()) {
            return AnchorReceipt.notAttempted(target.tierId(), limits);
        }
        try {
            AnchorReceipt receipt = target.submit(checkpoint.domain(), checkpoint.fromSequence(),
                    checkpoint.toSequence(), checkpoint.merkleRoot(), checkpoint.createdAt());
            if (receipt == null) {
                return AnchorReceipt.failed(target.tierId(), checkpoint.domain(),
                        checkpoint.merkleRoot(), "the tier returned no receipt", limits);
            }
            return receipt;
        } catch (RuntimeException e) {
            // Contained here on purpose. The tiers exist so that one being unavailable does not
            // take the others with it.
            logger.warn("Anchor tier {} failed for {}: {}", target.tierId(), checkpoint.domain(),
                    e.getMessage());
            return AnchorReceipt.failed(target.tierId(), checkpoint.domain(),
                    checkpoint.merkleRoot(), e.getMessage(), limits);
        }
    }

    private String refusalFor(EvidenceCheckpoint checkpoint) {
        if (checkpoint.merkleRoot() == null || checkpoint.merkleRoot().isBlank()) {
            return "the checkpoint has no Merkle root";
        }
        if (store == null) {
            return "the evidence ledger is not wired, so the checkpoint's currency cannot be "
                    + "established and anchoring it would assert something unverified";
        }
        long highest;
        try {
            highest = store.highestSequence(checkpoint.domain());
        } catch (RuntimeException e) {
            return "the ledger's head could not be read (" + e.getMessage() + "), so it is "
                    + "unknown whether entries are still landing behind this root";
        }
        if (highest > checkpoint.toSequence()) {
            return "the ledger has advanced to " + highest + " while this checkpoint ends at "
                    + checkpoint.toSequence() + "; anchoring it would assert that this root was "
                    + "the ledger, which it already is not";
        }
        return null;
    }
}
