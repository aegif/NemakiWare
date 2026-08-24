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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Appends to the evidence ledger, closes periods, and answers inclusion proofs (P1-3).
 *
 * <h2>What the caller gets and does not get</h2>
 *
 * <p>Design: {@code docs/design/p1-3-evidence-ledger.md}. Appending is best-effort from the
 * business operation's point of view — a ledger write must not fail an ingest — but it is NOT
 * silent: {@link AppendOutcome} says whether the entry landed, and a caller that ignores it is
 * making the same mistake the DAO's null-returning update made (roadmap §2-1).
 */
public class EvidenceLedgerService {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceLedgerService.class);

    /** How many entries one checkpoint may cover, so a proof stays small and a close is cheap. */
    public static final int MAX_CHECKPOINT_SPAN = 10_000;

    /** Retries when another writer takes the position we aimed at. */
    private static final int APPEND_RETRIES = 5;

    private EvidenceLedgerStore store;

    public void setStore(EvidenceLedgerStore store) {
        this.store = store;
    }

    /** What an append did. */
    public enum AppendOutcome {
        /** The entry is in the ledger at the sequence reported. */
        APPENDED,
        /** The ledger is not wired or not reachable — nothing was recorded. */
        UNAVAILABLE,
        /** Every attempt lost the position to another writer. Nothing was recorded. */
        CONTENDED,
        /** The store refused. Nothing was recorded. */
        REFUSED
    }

    public record AppendResult(AppendOutcome outcome, long sequence, String entryHash,
                               String reason) {
        public boolean recorded() {
            return outcome == AppendOutcome.APPENDED;
        }
    }

    /**
     * Appends one fact's digest at the end of the domain's chain.
     *
     * <p>The position and the link are decided together from the current tail: a writer that
     * lost the race re-reads and tries the next position, so two concurrent appends produce two
     * entries in some order rather than one entry and one silent loss.
     */
    public AppendResult append(String domain, EvidenceLedgerEntry.SubjectKind kind,
            String subjectId, String payloadDigest, String occurredAt) {
        if (store == null || !store.isActive()) {
            // Not "nothing to record" — "we could not record". The caller decides whether that
            // is tolerable for its operation; this method does not decide for it.
            return new AppendResult(AppendOutcome.UNAVAILABLE, -1, null,
                    "the evidence ledger is not available");
        }
        for (int attempt = 0; attempt < APPEND_RETRIES; attempt++) {
            long tail;
            String prevHash;
            try {
                tail = store.highestSequence(domain);
                if (tail < 0) {
                    prevHash = null;
                } else {
                    List<EvidenceLedgerEntry> last = store.range(domain, tail, tail, 2);
                    if (last == null || last.isEmpty()) {
                        return new AppendResult(AppendOutcome.REFUSED, -1, null,
                                "the tail entry could not be read back, so the chain link "
                                        + "cannot be computed");
                    }
                    if (last.size() > 1) {
                        // A fork at the tail. Linking to either arm would pick a winner
                        // silently; the verifier's job is to report it and an operator's to
                        // resolve it.
                        return new AppendResult(AppendOutcome.REFUSED, -1, null,
                                "the chain forks at sequence " + tail + "; appending would "
                                        + "choose one arm silently");
                    }
                    prevHash = last.get(0).entryHash();
                }
                EvidenceLedgerEntry entry = EvidenceLedgerEntry.of(domain, tail + 1, kind,
                        subjectId, payloadDigest, occurredAt, prevHash);
                if (store.append(entry)) {
                    return new AppendResult(AppendOutcome.APPENDED, entry.sequence(),
                            entry.entryHash(), null);
                }
                // Position taken. Re-read and aim past it.
            } catch (Exception e) {
                logger.warn("Evidence ledger append failed for {}: {}", domain, e.toString());
                return new AppendResult(AppendOutcome.REFUSED, -1, null,
                        "the append failed: " + e.getMessage());
            }
        }
        return new AppendResult(AppendOutcome.CONTENDED, -1, null,
                "the tail moved under every attempt; nothing was recorded");
    }

    /**
     * Closes the span since the last checkpoint.
     *
     * <p>Refuses an EMPTY span rather than writing a checkpoint over nothing: an anchor would
     * then attest to a value that commits to no entries, which is worse than no anchor.
     */
    public Map<String, Object> closeCheckpoint(String domain, String createdAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (store == null || !store.isActive()) {
            body.put("status", "error");
            body.put("message", "the evidence ledger is not available");
            return body;
        }
        EvidenceCheckpoint previous = store.latestCheckpoint(domain);
        long from = previous == null ? 0 : previous.toSequence() + 1;
        long tail = store.highestSequence(domain);
        if (tail < from) {
            body.put("status", "noop");
            body.put("message", "no entries since the last checkpoint; a checkpoint over an "
                    + "empty span would commit to nothing");
            return body;
        }
        long to = Math.min(tail, from + MAX_CHECKPOINT_SPAN - 1);
        List<EvidenceLedgerEntry> span =
                store.range(domain, from, to, MAX_CHECKPOINT_SPAN);

        // A span that does not verify must not be sealed. Sealing it would produce a checkpoint
        // that commits to a chain already known to be broken, and an anchor would then make
        // that permanent.
        EvidenceChainVerifier.Report walk = EvidenceChainVerifier.verify(span);
        if (!walk.intact()) {
            body.put("status", "error");
            body.put("message", "the span does not verify, so it must not be sealed");
            body.putAll(walk.asMap());
            return body;
        }

        String root = EvidenceChainVerifier.merkleRootOf(span);
        EvidenceCheckpoint checkpoint = EvidenceCheckpoint.of(domain, from, to, root,
                previous == null ? null : previous.checkpointHash(), createdAt);
        if (!store.appendCheckpoint(checkpoint)) {
            body.put("status", "error");
            body.put("message", "a checkpoint already exists at this position");
            return body;
        }
        body.put("status", "success");
        body.putAll(checkpoint.toDocument());
        body.put("entries", span.size());
        return body;
    }

    /**
     * The audit path proving one entry was in the ledger as of a checkpoint.
     *
     * <p>The proof is against the checkpoint that COVERS the entry — a later checkpoint does
     * not commit to this entry's leaf directly, it commits to the earlier checkpoint's hash.
     * Walking that further is the checkpoint chain, which the caller can verify from the
     * checkpoints alone.
     */
    public Map<String, Object> inclusionProof(String domain, long sequence) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (store == null || !store.isActive()) {
            body.put("status", "error");
            body.put("message", "the evidence ledger is not available");
            return body;
        }
        EvidenceCheckpoint covering = coveringCheckpoint(domain, sequence);
        if (covering == null) {
            // Honest absence: the entry may well be in the ledger, but nothing has committed to
            // it yet. Reporting a proof against the ledger's own current state would prove only
            // that the ledger agrees with itself.
            body.put("status", "unavailable");
            body.put("message", "no checkpoint covers sequence " + sequence + " yet; entries "
                    + "after the last checkpoint are not committed to by anything");
            return body;
        }
        List<EvidenceLedgerEntry> span = store.range(domain, covering.fromSequence(),
                covering.toSequence(), MAX_CHECKPOINT_SPAN);
        List<String> leaves = new ArrayList<>(span.size());
        int index = -1;
        for (int i = 0; i < span.size(); i++) {
            leaves.add(span.get(i).entryHash());
            if (span.get(i).sequence() == sequence) {
                index = i;
            }
        }
        if (index < 0) {
            body.put("status", "error");
            body.put("message", "sequence " + sequence + " is not in the checkpoint's span — "
                    + "the ledger and the checkpoint disagree about what was covered");
            return body;
        }
        List<MerkleTree.ProofStep> path = MerkleTree.proof(leaves, index);
        body.put("status", "success");
        body.put("domain", domain);
        body.put("sequence", sequence);
        body.put("leafHash", leaves.get(index));
        body.put("checkpointHash", covering.checkpointHash());
        body.put("merkleRoot", covering.merkleRoot());
        body.put("auditPath", path.stream().map(step -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("siblingHash", step.siblingHash());
            m.put("siblingIsLeft", step.siblingIsLeft());
            return m;
        }).toList());
        body.put("limits", "This proves the entry was in the span the checkpoint sealed. It "
                + "does NOT prove the checkpoint itself was not rewritten — that needs the "
                + "checkpoint hash to exist somewhere outside this database (P2).");
        return body;
    }

    /**
     * The checkpoint whose span contains this sequence, or null when none does yet.
     *
     * <p>Walks back from the latest. Checkpoints are contiguous by construction — each starts
     * one past the previous — so the walk terminates at the first checkpoint.
     */
    private EvidenceCheckpoint coveringCheckpoint(String domain, long sequence) {
        EvidenceCheckpoint current = store.latestCheckpoint(domain);
        if (current == null || sequence > current.toSequence()) {
            return null;
        }
        while (current != null && sequence < current.fromSequence()) {
            current = store.checkpointEndingBefore(domain, current.fromSequence());
        }
        return current != null && sequence >= current.fromSequence()
                && sequence <= current.toSequence() ? current : null;
    }
}
