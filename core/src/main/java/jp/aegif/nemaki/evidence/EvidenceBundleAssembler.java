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
 * and they reach the bundle differently. An unreachable ledger produces a bundle with no entry
 * AND a reason recorded on the package, never a bundle that looks like a record with no
 * evidence.
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
     * @param versionObjectId the immutable version key. A bundle assembled for "the latest
     *        version" would be about whatever the repository decides later, which is not
     *        evidence — so this is required and is not defaulted from the object id by this
     *        class.
     */
    public EvidenceBundle assemble(String repositoryId, String objectId, String versionObjectId) {
        String createdAt = java.time.Instant.now().toString();

        RecordContentStatementV1 statement = statementFor(repositoryId, versionObjectId);
        EvidenceLedgerEntry entry = entryFor(versionObjectId, statement);

        EvidenceCheckpoint covering = entry == null ? null : coveringFor(entry.sequence());
        EvidenceBundle.InclusionProof proof = proofFor(entry, covering);
        List<EvidenceCheckpoint> chain = chainFrom(covering);
        EvidenceCheckpoint target = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        Map<AnchorKind, EvidenceBundle.AnchorPart> anchors = anchorsFor(target);

        return new EvidenceBundle(repositoryId, objectId, versionObjectId, statement, entry,
                proof, covering, chain, target, anchors, createdAt);
    }

    private RecordContentStatementV1 statementFor(String repositoryId, String versionObjectId) {
        if (journal == null || !journal.isActive()) {
            return null;
        }
        Map<String, Object> document = journal.statementFor(repositoryId, versionObjectId);
        if (document == null) {
            return null;
        }
        try {
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
            // carry a document that fails its own check.
            logger.warn("The stored statement for {} could not be read back; the package will "
                    + "carry none.", versionObjectId, e);
            return null;
        }
    }

    private EvidenceLedgerEntry entryFor(String versionObjectId,
            RecordContentStatementV1 statement) {
        if (ledgerStore == null || !ledgerStore.isActive() || statement == null) {
            return null;
        }
        List<EvidenceLedgerEntry> entries;
        try {
            entries = ledgerStore.findBySubject(RecordContentStateRecorder.DOMAIN, versionObjectId,
                    ENTRIES_PER_VERSION);
        } catch (RuntimeException e) {
            logger.warn("The ledger entries for {} could not be read.", versionObjectId, e);
            return null;
        }
        if (entries == null) {
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
        return match;
    }

    private EvidenceCheckpoint coveringFor(long sequence) {
        if (ledgerStore == null) {
            return null;
        }
        try {
            EvidenceCheckpoint latest = ledgerStore.latestCheckpoint(
                    RecordContentStateRecorder.DOMAIN);
            EvidenceCheckpoint candidate = latest;
            // Walk back until the checkpoint's span contains the entry. A checkpoint AFTER the
            // entry does not cover it, and one before it does not either.
            int guard = 0;
            while (candidate != null && guard++ < 1000) {
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
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private EvidenceBundle.InclusionProof proofFor(EvidenceLedgerEntry entry,
            EvidenceCheckpoint covering) {
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
        if (ledgerService == null) {
            return new EvidenceBundle.InclusionProof(leaf, null,
                    "the ledger service is not wired on this node");
        }
        Map<String, Object> answer = ledgerService.inclusionProof(
                RecordContentStateRecorder.DOMAIN, entry.sequence());
        Object path = answer == null ? null : answer.get("auditPath");
        if (!(path instanceof List<?> steps)) {
            return new EvidenceBundle.InclusionProof(leaf, null,
                    answer == null ? "the ledger did not answer"
                            : String.valueOf(answer.getOrDefault("message",
                                    "no audit path was produced")));
        }
        List<EvidenceBundle.InclusionProof.Step> out = new ArrayList<>();
        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?> step)) {
                // A path with a step this version cannot read is not a shorter path. Shipping
                // the readable part would be a proof that reaches a different root.
                return new EvidenceBundle.InclusionProof(leaf, null,
                        "the audit path contains a step this node could not read");
            }
            Map<String, Object> one = (Map<String, Object>) step;
            out.add(new EvidenceBundle.InclusionProof.Step(text(one.get("siblingHash")),
                    Boolean.TRUE.equals(one.get("siblingIsLeft"))));
        }
        return new EvidenceBundle.InclusionProof(leaf, out, null);
    }

    /**
     * Covering checkpoint through to the newest one, in order.
     *
     * <p>Every link is read here, once. The chain is what carries the connection to earlier
     * periods, and it is the package's own content — an anchor commits to a Merkle root, not to
     * this walk (spec §11), which is why a verifier still needs an externally held checkpoint to
     * detect a rewrite below the anchored root.
     */
    private List<EvidenceCheckpoint> chainFrom(EvidenceCheckpoint covering) {
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
            int guard = 0;
            while (candidate != null && candidate.toSequence() > covering.toSequence()
                    && guard++ < 1000) {
                backwards.add(candidate);
                candidate = ledgerStore.checkpointEndingBefore(RecordContentStateRecorder.DOMAIN,
                        candidate.fromSequence());
            }
            if (candidate == null || candidate.toSequence() != covering.toSequence()) {
                // The walk did not reach the covering checkpoint. A partial chain would be a
                // chain with a gap, and the spec makes that FAILED — so none is shipped and the
                // package supports RECORD_LEDGER_V1 rather than claiming an anchored one.
                logger.warn("The checkpoint chain from {} could not be walked to the newest "
                        + "checkpoint; the package will carry no chain.",
                        covering.toSequence());
                return List.copyOf(forward);
            }
            for (int i = backwards.size() - 1; i >= 0; i--) {
                forward.add(backwards.get(i));
            }
        } catch (RuntimeException e) {
            logger.warn("The checkpoint chain could not be read.", e);
            return List.copyOf(forward);
        }
        return List.copyOf(forward);
    }

    private Map<AnchorKind, EvidenceBundle.AnchorPart> anchorsFor(EvidenceCheckpoint target) {
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
        Map<AnchorKind, AnchorReceipt> byKind = new LinkedHashMap<>();
        if (receipts != null) {
            for (AnchorReceipt receipt : receipts) {
                byKind.put(receipt.kind(), receipt);
            }
        }
        for (AnchorKind kind : AnchorKind.values()) {
            AnchorReceipt receipt = byKind.get(kind);
            if (receipt == null) {
                anchors.put(kind, new EvidenceBundle.AnchorPart(
                        couldNotAsk ? EvidenceBundle.AnchorPart.State.UNAVAILABLE
                                : EvidenceBundle.AnchorPart.State.NOT_CONFIGURED,
                        null,
                        couldNotAsk ? "the receipt store could not be asked, so this is NOT a "
                                + "finding that the rung is unconfigured"
                                : "no receipt for this rung at this checkpoint"));
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
            anchors.put(kind, new EvidenceBundle.AnchorPart(
                    EvidenceBundle.AnchorPart.State.PRESENT, receipt.proof(), null));
        }
        return anchors;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
