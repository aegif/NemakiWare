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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a span of the ledger and says what is wrong with it (P1-3 §7).
 *
 * <h2>What each finding means</h2>
 *
 * <p>The four things a tamper-evident chain has to notice are different failures and must be
 * reported as different findings — an operator's next step is not the same for a rewritten
 * entry and a missing one:
 *
 * <ul>
 *   <li>{@code REWRITTEN} — a row's own hash no longer matches its contents.</li>
 *   <li>{@code BROKEN_LINK} — a row's {@code prevEntryHash} is not the previous row's hash.
 *       That is what a rewrite ANYWHERE earlier looks like from here.</li>
 *   <li>{@code MISSING} — the sequence skips. Deleting a row and closing the gap would
 *       otherwise leave a chain that still links up.</li>
 *   <li>{@code FORK} — two rows claim the same sequence with different hashes. A failover
 *       where two writers both believed they held the lease.</li>
 *   <li>{@code CHAIN_BREAK} — a GENESIS entry: the chain deliberately does not continue here.
 *       Reported, never smoothed over: a gap that looks continuous is the worst outcome.</li>
 * </ul>
 */
public final class EvidenceChainVerifier {

    private EvidenceChainVerifier() {
    }

    public enum FindingKind {
        REWRITTEN, BROKEN_LINK, MISSING, FORK, CHAIN_BREAK
    }

    public record Finding(FindingKind kind, long sequence, String detail) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name());
            m.put("sequence", sequence);
            m.put("detail", detail);
            return m;
        }
    }

    /** What a walk established. {@code intact} is true only when nothing was found. */
    public record Report(boolean intact, long from, long to, long verified,
                         List<Finding> findings) {

        /**
         * Whether anything was actually walked.
         *
         * <p>{@code intact} is vacuously true for an empty span — nothing was found wrong
         * because nothing was looked at — and "the chain is intact" is what a reader takes from
         * it either way. Every caller guards the empty case today, so this is latent; it is a
         * public static that answers a question about a span nobody walked, which is the same
         * shape as an index verdict meaning "everything present is stamped".
         */
        public boolean walkedAnything() {
            return verified > 0 || !findings.isEmpty();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intact", intact);
            // Beside the verdict, not somewhere else: `intact: true, verifiedEntries: 0` is
            // read as "checked and fine" unless something says otherwise on the same line.
            m.put("walkedAnything", walkedAnything());
            m.put("fromSequence", from);
            m.put("toSequence", to);
            m.put("verifiedEntries", verified);
            m.put("findings", findings.stream().map(Finding::asMap).toList());
            m.put("limits", (walkedAnything() ? "" : "NOTHING WAS WALKED: this span is empty, "
                    + "so 'intact' means only that no fault was found in zero entries. It is "
                    + "NOT a finding that the chain is sound. ")
                    + "This checks the chain against ITSELF. It detects a rewrite, a "
                    + "deletion, a reordering and a fork WITHIN the span walked. It cannot "
                    + "detect a rewrite that also rewrote every following entry and the "
                    + "checkpoints — that needs an anchor outside this database (P2). Entries "
                    + "after the last checkpoint are not covered by any checkpoint yet.");
            return m;
        }
    }

    /**
     * Verifies a span, which must be in ascending sequence order.
     *
     * <p>Duplicate sequences are expected as INPUT here: that is what a fork looks like from
     * the store, and dropping them before the walk would hide it.
     */
    public static Report verify(List<EvidenceLedgerEntry> entries) {
        List<Finding> findings = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            // `intact` stays true because every caller reads it as "no fault found" and an
            // empty span has none -- flipping it would make an empty ledger report a break.
            // What changes is that the Report now says it walked nothing, in the body and in
            // the limits, so the two readings cannot be confused.
            return new Report(true, 0, 0, 0, List.of());
        }
        long from = entries.get(0).sequence();
        long to = entries.get(entries.size() - 1).sequence();
        long verified = 0;

        EvidenceLedgerEntry previous = null;
        for (EvidenceLedgerEntry entry : entries) {
            if (!entry.selfVerifies()) {
                findings.add(new Finding(FindingKind.REWRITTEN, entry.sequence(),
                        "the row's stored hash does not match its own contents"));
            }
            if (previous != null) {
                if (entry.sequence() == previous.sequence()) {
                    if (!java.util.Objects.equals(entry.entryHash(), previous.entryHash())) {
                        // Two writers, one position. Picking one silently would make the
                        // ledger's own answer depend on which row happened to be read first.
                        findings.add(new Finding(FindingKind.FORK, entry.sequence(),
                                "two entries claim this sequence with different hashes: "
                                        + previous.entryHash() + " and " + entry.entryHash()));
                    }
                } else if (entry.sequence() != previous.sequence() + 1) {
                    findings.add(new Finding(FindingKind.MISSING, previous.sequence() + 1,
                            "the sequence jumps from " + previous.sequence() + " to "
                                    + entry.sequence() + "; " + (entry.sequence()
                                    - previous.sequence() - 1) + " entr(y/ies) are not here"));
                }
            }
            if (entry.subjectKind() == EvidenceLedgerEntry.SubjectKind.GENESIS) {
                // Deliberate, and still reported. A chain that restarts must not look
                // continuous — that is the one outcome the design refuses (§5).
                findings.add(new Finding(FindingKind.CHAIN_BREAK, entry.sequence(),
                        "the chain restarts here: " + (entry.subjectId() == null
                                ? "no reason recorded" : entry.subjectId())));
            } else if (previous != null && entry.sequence() == previous.sequence() + 1
                    && !java.util.Objects.equals(entry.prevEntryHash(), previous.entryHash())) {
                // What a rewrite ANYWHERE earlier looks like from here.
                findings.add(new Finding(FindingKind.BROKEN_LINK, entry.sequence(),
                        "prevEntryHash does not match the previous entry's hash"));
            }
            verified++;
            previous = entry;
        }
        return new Report(findings.isEmpty(), from, to, verified, List.copyOf(findings));
    }

    /**
     * The Merkle root a checkpoint over this span should carry.
     *
     * <p>Leaves are the entries' hashes IN SEQUENCE ORDER — the tree commits to the order as
     * well as the set.
     */
    public static String merkleRootOf(List<EvidenceLedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        List<String> leaves = new ArrayList<>(entries.size());
        for (EvidenceLedgerEntry entry : entries) {
            leaves.add(entry.entryHash());
        }
        return MerkleTree.root(leaves);
    }
}
