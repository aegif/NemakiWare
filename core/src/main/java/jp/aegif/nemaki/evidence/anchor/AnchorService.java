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
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one checkpoint's Merkle root to every configured rung (P2-0).
 *
 * <p>The rungs themselves are {@code jp.aegif.nemaki.rest.purview.anchor}: {@link AnchorKind}
 * carries what each one's time may be read as, so this class never has to hold that in prose.
 * What it adds is the two refusals that only make sense once there is a LEDGER behind the digest.
 *
 * <h2>It will not anchor over an unsettled tail</h2>
 *
 * <p>If the ledger's highest sequence has moved past the checkpoint's, entries are still landing
 * behind the root — and "this root is the ledger as it stood" is already false at the moment it
 * would be fixed somewhere unrewritable. So the service returns a reason instead of anchoring
 * and apologising afterwards. An unreadable head refuses too: not knowing whether anything is
 * behind the root is not the same as knowing nothing is.
 *
 * <h2>It will not let one rung's failure take another down</h2>
 *
 * <p>The ladder exists so a customer can lean on a different rung. {@link AnchorTarget} already
 * promises not to throw for ordinary failure; this contains the rest anyway, because a rung that
 * breaks its promise must not be able to hide the rungs that kept theirs.
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}.
 */
public class AnchorService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorService.class);

    private final List<AnchorTarget> targets = new ArrayList<>();
    private EvidenceLedgerStore store;
    private AnchorReceiptStore receiptStore;

    public void setTargets(List<AnchorTarget> targets) {
        this.targets.clear();
        if (targets != null) {
            this.targets.addAll(targets);
        }
    }

    public void setStore(EvidenceLedgerStore store) {
        this.store = store;
    }

    public void setReceiptStore(AnchorReceiptStore receiptStore) {
        this.receiptStore = receiptStore;
    }

    /**
     * What a claim built on this rung may and may not say.
     *
     * <p>Derived from the enum rather than supplied as text, so a new {@code TimeSemantics}
     * cannot be added without the compiler demanding a sentence for it. A blank-able string
     * field would eventually be left blank on exactly the rung that most needed it.
     */
    public static String claimLimitsFor(AnchorKind kind) {
        return claimLimitsFor(kind.timeSemantics());
    }

    /**
     * The limits for a RECEIPT, which may be weaker than its kind's default.
     *
     * <p>A pending or failed receipt carries {@code NOT_A_TIME_PROOF} regardless of kind, and an
     * RFC 3161 token without {@code accuracy} is deliberately downgraded to
     * {@code UPPER_BOUND_ONLY} when it is issued. Rendering the KIND's sentence undid both:
     * a pending TSA attempt was shown beside "binds a message imprint to the authority's stated
     * time", which is what a confirmed token establishes and this one does not.
     */
    public static String claimLimitsFor(AnchorReceipt receipt) {
        String base = claimLimitsFor(receipt.timeSemantics());
        if (receipt.status() == AnchorStatus.CONFIRMED && receipt.anchoredAt() == null) {
            // The UPPER_BOUND_ONLY sentence says the commitment existed "no later than that
            // time" — and for a confirmed OpenTimestamps proof on a deployment with no Bitcoin
            // node there IS no such time here. Leaving the sentence as it stands points a
            // reader at a value the response does not carry, which is the substitution this
            // whole layer exists to prevent (review).
            return base + " NOTE: this deployment does not hold the anchoring time. The proof "
                    + "is complete and a third party can read the time from the block it "
                    + "commits to, but nothing here states it, and no time in this response "
                    + "should be read as the anchoring time.";
        }
        return base;
    }

    private static String claimLimitsFor(AnchorKind.TimeSemantics semantics) {
        return switch (semantics) {
            case NOT_A_TIME_PROOF -> "This is NOT a time proof and NOT independent evidence. It "
                    + "records when the destination was TOLD, not when the data existed, and a "
                    + "destination the same party administers can be rewritten by that party "
                    + "along with the ledger.";
            case UPPER_BOUND_ONLY -> "This establishes only that the COMMITMENT existed no later "
                    + "than that time — nothing about how much earlier, and nothing about the "
                    + "record itself. The subject is the commitment, not the document, and it "
                    + "says nothing about whether the metadata was true or the capture complete.";
            case BIDIRECTIONAL_WITHIN_ACCURACY -> "This binds a message imprint to the "
                    + "authority's stated time, within the accuracy the token itself states, "
                    + "and only under the trust and policy checks the deployment chose. The "
                    + "protocol alone implies no accreditation, and none of it establishes that "
                    + "the record or its metadata are true.";
        };
    }

    /** Every rung's receipt, plus what the set of them does and does not amount to. */
    public record Outcome(String domain, long toSequence, String merkleRoot,
                          List<AnchorReceipt> receipts, String refusedReason) {

        /**
         * Rungs that actually confirmed. {@code PENDING} is excluded — an OpenTimestamps
         * commitment is legitimately pending for hours, and during those hours nothing has been
         * proved.
         */
        public List<String> confirmedRungs() {
            List<String> confirmed = new ArrayList<>();
            for (AnchorReceipt receipt : receipts) {
                if (receipt.status() == AnchorStatus.CONFIRMED) {
                    confirmed.add(receipt.kind().name());
                }
            }
            return confirmed;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", domain);
            m.put("toSequence", toSequence);
            m.put("merkleRoot", merkleRoot);
            m.put("refused", refusedReason != null);
            m.put("refusedReason", refusedReason);
            // The list, never a single flag: "anchored" as one word lets a deployment running
            // only the catalog rung borrow the sentence that belongs to a timestamp authority.
            m.put("confirmedRungs", confirmedRungs());
            List<Map<String, Object>> rows = new ArrayList<>(receipts.size());
            for (AnchorReceipt receipt : receipts) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rung", receipt.kind().name());
                row.put("status", receipt.status().name());
                row.put("timeSemantics", receipt.timeSemantics().name());
                // Immediately after the status, so a reader cannot take the status alone.
                // From the RECEIPT, not the kind: a pending or failed receipt establishes
                // nothing, and showing the kind's affirmative sentence next to it is exactly
                // the substitution this whole layer exists to prevent.
                row.put("claimLimits", claimLimitsFor(receipt));
                row.put("anchoredDigest", receipt.anchoredDigest());
                row.put("attemptedAt", receipt.attemptedAt() == null ? null : receipt.attemptedAt().toString());
                row.put("anchoredAt", receipt.anchoredAt() == null ? null : receipt.anchoredAt().toString());
                row.put("proofDigest", receipt.proofDigest());
                row.put("attributes", receipt.attributes());
                row.put("failureReason", receipt.failureReason());
                rows.add(row);
            }
            m.put("receipts", rows);
            return m;
        }
    }

    public Outcome anchor(EvidenceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return new Outcome(null, -1, null, List.of(), "there is no checkpoint to anchor");
        }
        String refusal = refusalFor(checkpoint);
        if (refusal != null) {
            // Nothing is sent. A root that is already out of date cannot be made current by
            // being anchored, and the anchor would be evidence of a claim that was false when
            // it was made.
            logger.warn("Refusing to anchor {} at {}: {}", checkpoint.domain(),
                    checkpoint.toSequence(), refusal);
            return new Outcome(checkpoint.domain(), checkpoint.toSequence(),
                    checkpoint.merkleRoot(), List.of(), refusal);
        }
        List<AnchorReceipt> receipts = new ArrayList<>(targets.size());
        for (AnchorTarget target : targets) {
            AnchorReceipt receipt = receiptFrom(target, checkpoint.merkleRoot());
            receipts.add(receipt);
            persist(checkpoint.domain(), checkpoint.toSequence(), receipt);
        }
        return new Outcome(checkpoint.domain(), checkpoint.toSequence(), checkpoint.merkleRoot(),
                receipts, null);
    }

    /**
     * Re-checks pending receipts and stores any that have settled.
     *
     * <p>This is the other half of rung 2. Without it an OpenTimestamps commitment stays
     * {@code PENDING} for ever: the calendar has it, a block confirmed it, and the deployment
     * never asked — so the anchor exists and the proof does not.
     *
     * @return the receipts that CHANGED. An empty list means nothing had settled yet, which is
     *         the ordinary answer during the hours a block takes and not a failure.
     */
    public List<AnchorReceipt> upgradePending(String domain, int limit) {
        List<AnchorReceipt> upgraded = new ArrayList<>();
        if (receiptStore == null) {
            logger.warn("No anchor receipt store: pending commitments cannot be upgraded");
            return upgraded;
        }
        for (AnchorReceiptStore.PendingReceipt pending : receiptStore.pending(domain, limit)) {
            AnchorTarget target = targetFor(pending.receipt().kind());
            if (target == null) {
                // The rung that made this receipt is no longer configured. Leaving the row
                // pending is right: deleting it would lose a proof the calendar still holds,
                // and marking it failed would assert something about an anchor nobody checked.
                continue;
            }
            AnchorReceipt after;
            try {
                after = target.upgrade(pending.receipt());
            } catch (RuntimeException e) {
                logger.warn("Upgrade of a pending {} receipt failed: {}",
                        pending.receipt().kind(), e.getMessage());
                continue;
            }
            if (after == null || after.status() == AnchorStatus.PENDING) {
                continue;
            }
            if (after.kind() != pending.receipt().kind()) {
                // Not an upgrade of THIS receipt. Saving it would write a row under the other
                // rung's key and leave this one pending for ever — the commitment would look
                // unsettled while a settled proof sat one row away under the wrong name.
                logger.warn("Rung {} returned an upgrade of kind {}; ignoring it",
                        pending.receipt().kind(), after.kind());
                continue;
            }
            try {
                if (receiptStore.save(pending.domain(), pending.toSequence(), after)
                        == AnchorReceiptStore.SaveOutcome.KEPT_STRONGER) {
                    // Something stronger is already there. Reporting this one as upgraded would
                    // name a change that did not happen.
                    continue;
                }
            } catch (RuntimeException e) {
                // One contended row must not abandon the rest of the batch: the others are
                // still upgradable and the next run is not guaranteed to come sooner.
                logger.warn("Could not store the upgraded {} receipt for {}@{}: {}",
                        after.kind(), pending.domain(), pending.toSequence(), e.getMessage());
                continue;
            }
            upgraded.add(after);
        }
        return upgraded;
    }

    private AnchorTarget targetFor(AnchorKind kind) {
        for (AnchorTarget target : targets) {
            if (target.kind() == kind && target.isConfigured()) {
                return target;
            }
        }
        return null;
    }

    private void persist(String domain, long toSequence, AnchorReceipt receipt) {
        if (receiptStore == null) {
            if (receipt.status() == AnchorStatus.PENDING) {
                // Worth saying loudly: a pending commitment that is never written down can
                // never be upgraded, so rung 2 silently becomes decorative.
                logger.warn("A PENDING {} receipt was not stored (no receipt store); it can "
                        + "never be upgraded and the proof will be lost", receipt.kind());
            }
            return;
        }
        try {
            // The monotonicity rule lives in the STORE, inside the same compare-and-set as the
            // write. It used to be here, as a read then a write, which left a window: a weak
            // writer reads PENDING, a concurrent writer stores CONFIRMED, the weak writer
            // overwrites it. A service-level check cannot close that; only the store can.
            AnchorReceiptStore.SaveOutcome outcome =
                    receiptStore.save(domain, toSequence, receipt);
            if (outcome == AnchorReceiptStore.SaveOutcome.KEPT_STRONGER) {
                logger.info("Kept the CONFIRMED {} receipt for {}@{}; the new attempt is {}",
                        receipt.kind(), domain, toSequence, receipt.status());
            }
        } catch (RuntimeException e) {
            logger.warn("Could not store the {} anchor receipt for {}@{}: {}", receipt.kind(),
                    domain, toSequence, e.getMessage());
        }
    }

    private AnchorReceipt receiptFrom(AnchorTarget target, String merkleRoot) {
        try {
            if (!target.isConfigured()) {
                return AnchorReceipt.notConfigured(target.kind(), merkleRoot);
            }
            AnchorReceipt receipt = target.anchor(merkleRoot);
            if (receipt == null) {
                return AnchorReceipt.failed(target.kind(), merkleRoot, Instant.now(),
                        "the rung returned no receipt");
            }
            // The receipt must be about what we asked. `upgradePending` already checked the
            // kind; the first anchoring accepted whatever came back, so a buggy rung could
            // return CONFIRMED for another digest and this Outcome would still name our root
            // and count that rung as confirmed (review).
            if (receipt.kind() != target.kind()) {
                return AnchorReceipt.failed(target.kind(), merkleRoot, Instant.now(),
                        "the rung returned a receipt for " + receipt.kind()
                                + "; a receipt from a different rung says nothing about this one");
            }
            if (!merkleRoot.equalsIgnoreCase(receipt.anchoredDigest())) {
                return AnchorReceipt.failed(target.kind(), merkleRoot, Instant.now(),
                        "the rung anchored a different value than the one it was given; the "
                                + "receipt is not about this checkpoint");
            }
            return receipt;
        } catch (RuntimeException e) {
            // Contained on purpose. AnchorTarget promises not to throw for ordinary failure;
            // a rung that breaks that promise must not be able to hide the rungs that kept it.
            logger.warn("Anchor rung {} failed: {}", target.kind(), e.getMessage());
            return AnchorReceipt.failed(target.kind(), merkleRoot, Instant.now(), e.getMessage());
        }
    }

    private String refusalFor(EvidenceCheckpoint checkpoint) {
        if (checkpoint.merkleRoot() == null || checkpoint.merkleRoot().isBlank()) {
            return "the checkpoint has no Merkle root";
        }
        if (!checkpoint.selfVerifies()) {
            // The method existed with no production caller. A checkpoint row whose root or
            // range was edited still hashes to something, just not to its own contents — and
            // anchoring it fixes the edited value somewhere it cannot be taken back.
            return "the checkpoint does not hash to its own contents (domain, range, root, "
                    + "previous hash, created-at); it has been altered or was written by a "
                    + "different version, and anchoring it would make the altered value durable";
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
