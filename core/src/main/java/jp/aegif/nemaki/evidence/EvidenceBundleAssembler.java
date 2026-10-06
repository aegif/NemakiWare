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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects a package's evidence parts in ONE pass, so they are all true together (plan §9).
 *
 * <p>Every read this class makes happens before the writer starts. What it cannot find, it
 * records as absent rather than waiting for or substituting: a bundle with no checkpoint is an
 * honest bundle for a record nothing has committed to yet, and the package it produces supports
 * {@code PACKAGE_INTEGRITY_V1} and says so.
 *
 * <h2>Reads that fail are not absences</h2>
 *
 * <p>Each part has two ways of not being there — it does not exist, or this node could not ask —
 * and they do not reach the bundle the same way. A part that does not exist is absent, and the
 * package says so. A part this node could not read — a store that threw or was unreachable, a
 * bound hit before the answer, a stored row this version cannot read back — is
 * {@link EvidenceNotReadable}: NO bundle is produced, and the exporter refuses. Until the 9-6
 * review every such failure was logged and the part recorded as absent, so an unreachable
 * ledger produced a package stating, in {@code profile.json} and by what it did not carry, that
 * the record had no evidence — a claim nobody established, under {@code BEST_AVAILABLE} as
 * under {@code REQUIRE_*} (P1).
 */
@Component
public class EvidenceBundleAssembler {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceBundleAssembler.class);

    /** How many entries for one version are looked at. A version rewritten in place has many. */
    static final int ENTRIES_PER_VERSION = 50;

    private EvidenceLedgerService ledgerService;
    private EvidenceLedgerStore ledgerStore;
    private ContentWriteJournal journal;
    private AnchorReceiptStore receiptStore;

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Autowired(required = false)
    public void setLedgerStore(EvidenceLedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @Autowired(required = false)
    public void setJournal(ContentWriteJournal journal) {
        this.journal = journal;
    }

    @Autowired(required = false)
    public void setReceiptStore(AnchorReceiptStore receiptStore) {
        this.receiptStore = receiptStore;
    }

    /**
     * The evidence for a version could not be established: a read failed, a bound was hit before
     * the answer, or a store this node has was unreachable. No bundle is produced — one built
     * from the parts that did come back would say the record has less evidence than it has.
     */
    public static class EvidenceNotReadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final List<String> reasons;

        public EvidenceNotReadable(String versionObjectId, List<String> reasons) {
            super("the evidence for version " + versionObjectId + " could not be read: "
                    + String.join("; ", reasons));
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    /** How many checkpoints a walk back through the chain follows before it is given up. */
    static final int CHECKPOINT_WALK_LIMIT = 1000;

    /**
     * @param versionObjectId the immutable version key. A bundle assembled for "the latest
     *        version" would be about whatever the repository decides later, which is not
     *        evidence — so this is required and is not defaulted from the object id by this
     *        class.
     * @throws EvidenceNotReadable when any part could not be read (as opposed to not existing)
     */
    public EvidenceBundle assemble(String repositoryId, String objectId, String versionObjectId) {
        String createdAt = java.time.Instant.now().toString();
        List<String> notRead = new ArrayList<>();

        RecordStatement statement = statementFor(repositoryId, versionObjectId, notRead);
        EvidenceLedgerEntry entry = entryFor(versionObjectId, statement, notRead);
        EvidenceBundle.Prior prior = priorFor(repositoryId, versionObjectId, statement, notRead);

        EvidenceCheckpoint covering =
                entry == null ? null : coveringFor(entry.sequence(), notRead);
        EvidenceBundle.InclusionProof proof = proofFor(entry, covering, notRead);
        List<EvidenceCheckpoint> chain = chainFrom(covering, notRead);
        EvidenceCheckpoint target = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        Map<AnchorKind, EvidenceBundle.AnchorPart> anchors = anchorsFor(target, notRead);

        if (!notRead.isEmpty()) {
            throw new EvidenceNotReadable(versionObjectId, notRead);
        }
        return new EvidenceBundle(repositoryId, objectId, versionObjectId, statement, entry,
                proof, covering, chain, target, anchors, createdAt, prior);
    }

    private RecordStatement statementFor(String repositoryId, String versionObjectId,
            List<String> notRead) {
        if (journal == null) {
            // No journal on this node: nothing was ever recorded here, and that is an absence.
            return null;
        }
        if (!journal.isActive()) {
            // The node HAS a journal and cannot reach it. The interface says what its null
            // means — "this node has no statement", not "the version has none" — and a package
            // cannot carry that distinction, so it is not built.
            notRead.add("the content-write journal is not reachable, so whether a statement was "
                    + "recorded for " + versionObjectId + " is not known");
            return null;
        }
        Map<String, Object> document;
        try {
            document = journal.statementFor(repositoryId, versionObjectId);
        } catch (RuntimeException e) {
            logger.warn("The statement for {} could not be read.", versionObjectId, e);
            notRead.add("the statement for " + versionObjectId + " could not be read: "
                    + e.getMessage());
            return null;
        }
        if (document == null) {
            return null;
        }
        RecordStatement statement = readBack(document, versionObjectId);
        if (statement == null) {
            notRead.add("the stored statement for " + versionObjectId + " could not be read back "
                    + "as a statement this version knows");
        }
        return statement;
    }

    /**
     * A stored statement document, as the typed statement it is.
     *
     * <p>The shape decides which type is tried — a transition has a {@code transition} field,
     * a state statement never does — and the typed constructor then validates every field.
     * The KIND the package relies on is not this guess but the ledger entry's, which
     * {@link #entryFor} checks agrees with the type read here.
     */
    private RecordStatement readBack(Map<String, Object> document, String versionObjectId) {
        try {
            if (document.containsKey("transition")) {
                Object from = document.get("priorStatementEntrySequence");
                return new RecordContentTransitionV1(
                        text(document.get("repositoryId")), text(document.get("objectId")),
                        text(document.get("versionObjectId")),
                        RecordContentTransitionV1.Transition.valueOf(text(document.get("transition"))),
                        RecordContentTransitionV1.BytesNow.valueOf(text(document.get("bytesNow"))),
                        text(document.get("priorContentDigest")),
                        from == null ? null : ((Number) from).longValue(),
                        text(document.get("recordedAt")));
            }
            return new RecordContentStatementV1(
                    text(document.get("repositoryId")), text(document.get("objectId")),
                    text(document.get("versionObjectId")), text(document.get("contentStreamId")),
                    text(document.get("contentDigest")),
                    ((Number) document.get("contentLength")).longValue(),
                    RecordContentStatementV1.CommitmentKind.valueOf(
                            text(document.get("commitmentKind"))),
                    text(document.get("captureIntentId")), text(document.get("recordedAt")));
        } catch (RuntimeException e) {
            // A stored row this version cannot read back. NOT shipped half-decoded: a statement
            // missing a field is one whose digest nobody can recompute, and the package would
            // carry a document that fails its own check. The caller records it as not read.
            logger.warn("The stored statement for {} could not be read back.", versionObjectId, e);
            return null;
        }
    }

    /**
     * The cited prior of a transition: the statement at the cited sequence and the entry that
     * commits to it. Null when there is nothing to cite, when the journal holds no statement at
     * that sequence, or when the pair this node holds is not a pair — the verifier then says
     * UNAVAILABLE ({@code TRANSITION_PRIOR_NOT_IN_PACKAGE}), which is the honest answer for "the
     * package does not carry it". A read that FAILED is not one of those: it is recorded in
     * {@code notRead} and no package is built.
     */
    private EvidenceBundle.Prior priorFor(String repositoryId, String versionObjectId,
            RecordStatement statement, List<String> notRead) {
        if (!(statement instanceof RecordContentTransitionV1 transition)
                || transition.priorStatementEntrySequence() == null) {
            return null;
        }
        if (journal == null || ledgerStore == null) {
            return null;
        }
        long cited = transition.priorStatementEntrySequence();
        if (!journal.isActive() || !ledgerStore.isActive()) {
            notRead.add("the journal or the evidence ledger is not reachable, so the prior "
                    + "cited by the transition for " + versionObjectId + " (entry " + cited
                    + ") could not be read");
            return null;
        }
        ContentWriteJournal.Recorded recorded;
        List<EvidenceLedgerEntry> at;
        try {
            recorded = journal.recordedAt(repositoryId, versionObjectId, cited);
            at = ledgerStore.range(RecordContentStateRecorder.DOMAIN, cited, cited, 1);
        } catch (RuntimeException e) {
            logger.warn("The prior cited by the transition for {} (entry {}) could not be read.",
                    versionObjectId, cited, e);
            notRead.add("the prior cited by the transition for " + versionObjectId + " (entry "
                    + cited + ") could not be read: " + e.getMessage());
            return null;
        }
        if (at == null) {
            notRead.add("the evidence ledger answered nothing for entry " + cited + ", the prior "
                    + "cited by the transition for " + versionObjectId);
            return null;
        }
        if (recorded == null || recorded.statement() == null || at.isEmpty()
                || at.get(0) == null) {
            return null;
        }
        RecordStatement prior = readBack(recorded.statement(), versionObjectId);
        if (prior == null) {
            notRead.add("the prior statement cited by the transition for " + versionObjectId
                    + " (entry " + cited + ") could not be read back");
            return null;
        }
        EvidenceLedgerEntry entry = at.get(0);
        // The pair has to be a pair: the entry at the cited sequence commits to this statement.
        // A prior that did not would be shipped only to fail the verifier's continuity check,
        // and a mismatch here is this node's problem to log, not the recipient's to find.
        if (entry.sequence() != cited || !prior.digest().equals(entry.payloadDigest())) {
            logger.warn("The prior cited by the transition for {} (entry {}) does not match the "
                    + "ledger entry at that sequence; the package will not carry it.",
                    versionObjectId, cited);
            return null;
        }
        return new EvidenceBundle.Prior(prior, entry);
    }

    private EvidenceLedgerEntry entryFor(String versionObjectId, RecordStatement statement,
            List<String> notRead) {
        if (ledgerStore == null || statement == null) {
            return null;
        }
        if (!ledgerStore.isActive()) {
            notRead.add("the evidence ledger is not reachable, so the entry for "
                    + versionObjectId + " could not be read");
            return null;
        }
        List<EvidenceLedgerEntry> entries;
        try {
            entries = ledgerStore.findBySubject(RecordContentStateRecorder.DOMAIN, versionObjectId,
                    ENTRIES_PER_VERSION);
        } catch (RuntimeException e) {
            logger.warn("The ledger entries for {} could not be read.", versionObjectId, e);
            notRead.add("the ledger entries for " + versionObjectId + " could not be read: "
                    + e.getMessage());
            return null;
        }
        if (entries == null) {
            notRead.add("the evidence ledger answered nothing for the entries of "
                    + versionObjectId);
            return null;
        }
        // The entry whose payloadDigest IS this statement's. Not "the newest entry for this
        // subject": a version rewritten in place has several, and shipping the newest beside an
        // older statement would put two parts in the package that do not refer to each other.
        String digest = statement.digest();
        EvidenceLedgerEntry match = null;
        for (EvidenceLedgerEntry candidate : entries) {
            if (candidate != null && digest.equals(candidate.payloadDigest())) {
                match = candidate;
            }
        }
        if (match == null && entries.size() >= ENTRIES_PER_VERSION) {
            // The window is full and the entry is not in it. "No entry" would be a guess: the
            // one that commits to this statement may be the fifty-first.
            notRead.add(versionObjectId + " has " + ENTRIES_PER_VERSION + " or more ledger "
                    + "entries and none of the " + ENTRIES_PER_VERSION + " read commits to its "
                    + "statement; the one that does may lie beyond what was read");
            return null;
        }
        // The entry's kind is what a verifier reads the statement's shape from (spec §5.3b). An
        // entry that commits to this document under the OTHER kind is a contradiction this node
        // wrote; shipping the pair would hand the recipient a package whose entry says "state"
        // over a document with no content digest, or the reverse. Shipped as no entry instead,
        // which the verifier reports as NOT_PRESENT.
        if (match != null && match.subjectKind() != statement.subjectKind()) {
            logger.warn("The ledger entry for {} is a {} and the statement is a {}; the package "
                    + "will carry no entry for it.", versionObjectId, match.subjectKind(),
                    statement.subjectKind());
            return null;
        }
        return match;
    }

    private EvidenceCheckpoint coveringFor(long sequence, List<String> notRead) {
        if (ledgerStore == null) {
            return null;
        }
        try {
            EvidenceCheckpoint candidate = ledgerStore.latestCheckpoint(
                    RecordContentStateRecorder.DOMAIN);
            // Walk back until the checkpoint's span contains the entry. A checkpoint AFTER the
            // entry does not cover it, and one before it does not either.
            int steps = 0;
            while (candidate != null) {
                if (steps++ >= CHECKPOINT_WALK_LIMIT) {
                    // Falling out of the loop here used to return null — "nothing covers it" —
                    // which is not what was found out.
                    notRead.add("the walk back through the checkpoints did not reach one "
                            + "covering sequence " + sequence + " within "
                            + CHECKPOINT_WALK_LIMIT + " steps; which checkpoint covers it is "
                            + "not known");
                    return null;
                }
                if (candidate.fromSequence() <= sequence && sequence <= candidate.toSequence()) {
                    return candidate;
                }
                if (candidate.fromSequence() <= sequence) {
                    // The entry is past this checkpoint's end and every earlier one ends sooner:
                    // nothing covers it yet.
                    return null;
                }
                candidate = ledgerStore.checkpointEndingBefore(RecordContentStateRecorder.DOMAIN,
                        candidate.fromSequence());
            }
        } catch (RuntimeException e) {
            logger.warn("The covering checkpoint for sequence {} could not be read.", sequence, e);
            notRead.add("the checkpoint covering sequence " + sequence + " could not be read: "
                    + e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private EvidenceBundle.InclusionProof proofFor(EvidenceLedgerEntry entry,
            EvidenceCheckpoint covering, List<String> notRead) {
        if (entry == null) {
            return new EvidenceBundle.InclusionProof(null, null,
                    "there is no ledger entry for this version's content");
        }
        String leaf = MerkleTree.hashLeaf(entry.entryHash());
        if (covering == null) {
            return new EvidenceBundle.InclusionProof(leaf, null,
                    "no checkpoint covers sequence " + entry.sequence() + " yet; entries after "
                            + "the last checkpoint are not committed to by anything");
        }
        // From here a proof EXISTS — the checkpoint covers the entry — so every way of not
        // producing it is a failure to read, not an absence.
        if (ledgerService == null) {
            notRead.add("the ledger service is not wired on this node, so the inclusion proof "
                    + "for entry " + entry.sequence() + " under the checkpoint that covers it "
                    + "could not be produced");
            return new EvidenceBundle.InclusionProof(leaf, null,
                    "the ledger service is not wired on this node");
        }
        Map<String, Object> answer;
        try {
            answer = ledgerService.inclusionProof(RecordContentStateRecorder.DOMAIN,
                    entry.sequence());
        } catch (RuntimeException e) {
            logger.warn("The inclusion proof for entry {} could not be read.", entry.sequence(), e);
            notRead.add("the inclusion proof for entry " + entry.sequence() + " could not be "
                    + "read: " + e.getMessage());
            return new EvidenceBundle.InclusionProof(leaf, null, "the ledger did not answer");
        }
        Object path = answer == null ? null : answer.get("auditPath");
        if (!(path instanceof List<?> steps)) {
            String why = answer == null ? "the ledger did not answer"
                    : String.valueOf(answer.getOrDefault("message", "no audit path was produced"));
            notRead.add("the ledger produced no inclusion proof for entry " + entry.sequence()
                    + " under the checkpoint that covers it: " + why);
            return new EvidenceBundle.InclusionProof(leaf, null, why);
        }
        List<EvidenceBundle.InclusionProof.Step> out = new ArrayList<>();
        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?> step)) {
                // A path with a step this version cannot read is not a shorter path. Shipping
                // the readable part would be a proof that reaches a different root.
                notRead.add("the audit path for entry " + entry.sequence() + " contains a step "
                        + "this node could not read");
                return new EvidenceBundle.InclusionProof(leaf, null,
                        "the audit path contains a step this node could not read");
            }
            Map<String, Object> one = (Map<String, Object>) step;
            Object sibling = one.get("siblingHash");
            Object side = one.get("siblingIsLeft");
            if (!(sibling instanceof String siblingHash) || siblingHash.isBlank()
                    || !(side instanceof Boolean siblingIsLeft)) {
                // A step whose sibling or side is missing or of another type is not a step this
                // node read. Defaulting the side to "right" forged a step and the proof walked to
                // some root, with nothing recorded (c96 confirmation review, P1).
                notRead.add("the audit path for entry " + entry.sequence() + " has a step whose "
                        + "siblingHash or siblingIsLeft is missing or not of its type");
                return new EvidenceBundle.InclusionProof(leaf, null,
                        "a step of the audit path could not be read");
            }
            out.add(new EvidenceBundle.InclusionProof.Step(siblingHash, siblingIsLeft));
        }
        return new EvidenceBundle.InclusionProof(leaf, out, null);
    }

    /**
     * Covering checkpoint through to the newest one, in order.
     *
     * <p>Every link is read here, once. The chain is what carries the connection to earlier
     * periods, and it is the package's own content — an anchor commits to a Merkle root, not to
     * this walk (spec §11), which is why a verifier still needs an externally held checkpoint to
     * detect a rewrite below the anchored root. A walk that could not be completed is recorded
     * in {@code notRead}: the covering checkpoint alone used to be shipped as the chain, and the
     * package then said RECORD_LEDGER_V1 about a record whose chain this node could not read.
     */
    private List<EvidenceCheckpoint> chainFrom(EvidenceCheckpoint covering, List<String> notRead) {
        if (covering == null || ledgerStore == null) {
            return List.of();
        }
        List<EvidenceCheckpoint> forward = new ArrayList<>();
        forward.add(covering);
        try {
            EvidenceCheckpoint latest = ledgerStore.latestCheckpoint(
                    RecordContentStateRecorder.DOMAIN);
            if (latest == null || latest.toSequence() <= covering.toSequence()) {
                return List.copyOf(forward);
            }
            // Walk BACK from the newest to the covering one, then reverse. Walking forward
            // would need a "checkpoint starting after" query the store does not have, and
            // inventing one by scanning would read rows this method has no bound on.
            List<EvidenceCheckpoint> backwards = new ArrayList<>();
            EvidenceCheckpoint candidate = latest;
            int steps = 0;
            while (candidate != null && candidate.toSequence() > covering.toSequence()) {
                if (steps++ >= CHECKPOINT_WALK_LIMIT) {
                    notRead.add("the checkpoint chain from " + covering.toSequence() + " to the "
                            + "newest checkpoint " + latest.toSequence() + " did not close within "
                            + CHECKPOINT_WALK_LIMIT + " steps");
                    return List.copyOf(forward);
                }
                backwards.add(candidate);
                candidate = ledgerStore.checkpointEndingBefore(RecordContentStateRecorder.DOMAIN,
                        candidate.fromSequence());
            }
            if (candidate == null || candidate.toSequence() != covering.toSequence()) {
                // The walk did not reach the covering checkpoint. A partial chain would be a
                // chain with a gap, and the spec makes that FAILED — and a chain of one is a
                // claim that nothing newer exists, which is not what was found out.
                logger.warn("The checkpoint chain from {} could not be walked to the newest "
                        + "checkpoint.", covering.toSequence());
                notRead.add("the checkpoint chain from " + covering.toSequence() + " could not "
                        + "be walked to the newest checkpoint " + latest.toSequence()
                        + ": the walk back does not reach it");
                return List.copyOf(forward);
            }
            for (int i = backwards.size() - 1; i >= 0; i--) {
                forward.add(backwards.get(i));
            }
        } catch (RuntimeException e) {
            logger.warn("The checkpoint chain could not be read.", e);
            notRead.add("the checkpoint chain could not be read: " + e.getMessage());
            return List.copyOf(forward);
        }
        return List.copyOf(forward);
    }

    private Map<AnchorKind, EvidenceBundle.AnchorPart> anchorsFor(EvidenceCheckpoint target,
            List<String> notRead) {
        Map<AnchorKind, EvidenceBundle.AnchorPart> anchors = new LinkedHashMap<>();
        if (target == null) {
            return anchors;
        }
        if (receiptStore == null) {
            for (AnchorKind kind : AnchorKind.values()) {
                anchors.put(kind, new EvidenceBundle.AnchorPart(
                        EvidenceBundle.AnchorPart.State.NOT_CONFIGURED, null,
                        "no anchor receipt store is wired on this node"));
            }
            return anchors;
        }
        List<AnchorReceipt> receipts;
        try {
            receipts = receiptStore.forCheckpoint(RecordContentStateRecorder.DOMAIN,
                    target.toSequence());
        } catch (RuntimeException e) {
            logger.warn("The anchor receipts for checkpoint {} could not be read.",
                    target.toSequence(), e);
            receipts = null;
        }
        boolean couldNotAsk = receipts == null || receiptStore.unreadableCount() > 0;
        if (couldNotAsk) {
            // Classified below as UNAVAILABLE rather than NOT_CONFIGURED — and, since the 9-6
            // review, not shipped at all: a manifest saying "unavailable" about every rung is
            // still a package that claims RECORD_LEDGER_V1 about a record that may be anchored.
            notRead.add("the anchor receipts for checkpoint " + target.toSequence() + " could "
                    + "not be read, so which rungs anchor it is not known");
        }
        Map<AnchorKind, AnchorReceipt> byKind = new LinkedHashMap<>();
        if (receipts != null) {
            for (AnchorReceipt receipt : receipts) {
                byKind.put(receipt.kind(), receipt);
            }
        }
        for (AnchorKind kind : AnchorKind.values()) {
            AnchorReceipt receipt = byKind.get(kind);
            if (receipt == null) {
                // No receipt at all: nothing attempted this rung for this checkpoint. In the
                // enum's words that is NOT_PRESENT ("no settled receipt"), not NOT_CONFIGURED —
                // the anchor service writes a NOT_CONFIGURED receipt for a rung it is not
                // configured for, so an unconfigured rung arrives as a receipt, below. The
                // manifest used to say "unconfigured" about a rung nobody asked (9-6 review, P3).
                anchors.put(kind, new EvidenceBundle.AnchorPart(
                        couldNotAsk ? EvidenceBundle.AnchorPart.State.UNAVAILABLE
                                : EvidenceBundle.AnchorPart.State.NOT_PRESENT,
                        null,
                        couldNotAsk ? "the receipt store could not be asked, so this is NOT a "
                                + "finding that the rung is unconfigured"
                                : "no receipt for this rung at this checkpoint"));
                continue;
            }
            if (receipt.status() == AnchorStatus.NOT_CONFIGURED) {
                anchors.put(kind, new EvidenceBundle.AnchorPart(
                        EvidenceBundle.AnchorPart.State.NOT_CONFIGURED, null,
                        "this deployment has no such rung configured (the anchor service said so "
                                + "for this checkpoint)"));
                continue;
            }
            if (receipt.status() != AnchorStatus.CONFIRMED || receipt.proof() == null
                    || receipt.proof().length == 0) {
                // PENDING is not failure and not material. A package carrying a pending rung's
                // empty proof would be reported by a verifier as a parse failure.
                anchors.put(kind, new EvidenceBundle.AnchorPart(
                        EvidenceBundle.AnchorPart.State.NOT_PRESENT, null,
                        "the receipt is " + receipt.status() + " and carries no usable material"));
                continue;
            }
            // The revocation material captured when the token was obtained (plan §11). Absent
            // is the common case and is not a problem here: the package then ships none and a
            // verifier answers INDETERMINATE for P3's revocation check.
            byte[] revocation;
            try {
                revocation = jp.aegif.nemaki.rest.purview.anchor.RevocationMaterial
                        .materialIn(receipt.attributes());
            } catch (RuntimeException undecodable) {
                // Material that is there and cannot be read is not material that is absent.
                // Shipped as none, the package would answer INDETERMINATE for P3's revocation
                // check about a receipt that holds the answer (9-6 review, P3).
                notRead.add("the revocation material on the " + kind + " receipt for checkpoint "
                        + target.toSequence() + " could not be read: " + undecodable.getMessage());
                continue;
            }
            anchors.put(kind, new EvidenceBundle.AnchorPart(
                    EvidenceBundle.AnchorPart.State.PRESENT, receipt.proof(), revocation, null));
        }
        return anchors;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
