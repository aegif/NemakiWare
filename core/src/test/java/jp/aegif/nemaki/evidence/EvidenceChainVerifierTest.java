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

import jp.aegif.nemaki.evidence.EvidenceLedgerEntry.SubjectKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance conditions of {@code docs/design/p1-3-evidence-ledger.md} §7.
 *
 * <p>Every one of these is a thing a tamper-evident ledger has to NOTICE. A chain that links up
 * but does not notice a deletion is not tamper-evident; it is a chain.
 */
class EvidenceChainVerifierTest {

    private static final String DOMAIN = "bedroom";

    /** A clean chain of {@code n} entries starting at sequence 1. */
    private static List<EvidenceLedgerEntry> chain(int n) {
        List<EvidenceLedgerEntry> entries = new ArrayList<>(n);
        String prev = null;
        for (int i = 1; i <= n; i++) {
            EvidenceLedgerEntry entry = EvidenceLedgerEntry.of(DOMAIN, i,
                    SubjectKind.CAPTURE_COMPLETED, "intent-" + i, "mh1:" + i,
                    "2026-08-24T00:00:0" + (i % 10) + "Z", prev);
            entries.add(entry);
            prev = entry.entryHash();
        }
        return entries;
    }

    private static boolean hasKind(EvidenceChainVerifier.Report report,
            EvidenceChainVerifier.FindingKind kind) {
        return report.findings().stream().anyMatch(f -> f.kind() == kind);
    }

    @Test
    @DisplayName("AC control: an untouched chain is intact")
    void anUntouchedChainIsIntact() {
        // Without this, a verifier that reported a finding for everything would pass every
        // test below.
        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(chain(5));

        assertTrue(report.intact(), report.findings().toString());
        assertEquals(5, report.verified());
    }

    @Test
    @DisplayName("AC 1: rewriting one entry is detected, and everything after it too")
    void rewritingAnEntryIsDetected() {
        List<EvidenceLedgerEntry> entries = new ArrayList<>(chain(5));
        EvidenceLedgerEntry original = entries.get(2);
        // The attacker changes the payload and leaves the stored hash alone — the cheapest
        // edit, and the one a chain must catch on the row itself.
        entries.set(2, new EvidenceLedgerEntry(original.domain(), original.sequence(),
                original.subjectKind(), original.subjectId(), "mh1:FORGED",
                original.occurredAt(), original.prevEntryHash(), original.entryHash()));

        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);

        assertFalse(report.intact());
        assertTrue(hasKind(report, EvidenceChainVerifier.FindingKind.REWRITTEN),
                report.findings().toString());
    }

    @Test
    @DisplayName("AC 1b: recomputing the hash to match the forgery breaks the LINK instead")
    void aRecomputedForgeryBreaksTheLink() {
        // The smarter attacker fixes the row's own hash. Then the row self-verifies — and the
        // NEXT row's prevEntryHash no longer matches. That is what prevEntryHash is for.
        List<EvidenceLedgerEntry> entries = new ArrayList<>(chain(5));
        EvidenceLedgerEntry original = entries.get(2);
        entries.set(2, EvidenceLedgerEntry.of(original.domain(), original.sequence(),
                original.subjectKind(), original.subjectId(), "mh1:FORGED",
                original.occurredAt(), original.prevEntryHash()));

        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);

        assertFalse(report.intact());
        assertTrue(hasKind(report, EvidenceChainVerifier.FindingKind.BROKEN_LINK),
                report.findings().toString());
    }

    @Test
    @DisplayName("AC 2: deleting an entry and closing the gap is detected")
    void aDeletedEntryIsDetected() {
        // Without the sequence-continuity check, removing a row leaves a chain whose remaining
        // links still verify against each other — the entry simply never existed.
        List<EvidenceLedgerEntry> entries = new ArrayList<>(chain(5));
        entries.remove(2);

        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);

        assertFalse(report.intact());
        assertTrue(hasKind(report, EvidenceChainVerifier.FindingKind.MISSING),
                report.findings().toString());
    }

    @Test
    @DisplayName("AC 3: the sequence is inside the hash, so entries cannot be swapped")
    void orderIsCommittedToByTheHash() {
        // If sequence were not hashed, two entries could exchange positions and every hash
        // would still verify: the chain would fix the SET of entries and not their order.
        String atOne = EvidenceLedgerEntry.computeEntryHash(DOMAIN, 1,
                SubjectKind.CAPTURE_COMPLETED, "x", "mh1:x", "2026-08-24T00:00:00Z", null);
        String atTwo = EvidenceLedgerEntry.computeEntryHash(DOMAIN, 2,
                SubjectKind.CAPTURE_COMPLETED, "x", "mh1:x", "2026-08-24T00:00:00Z", null);

        assertNotEquals(atOne, atTwo,
                "the same fact at two positions hashes identically, so the ledger does not "
                        + "commit to order");
    }

    @Test
    @DisplayName("AC 7: two entries at one sequence are a FORK, not a choice")
    void aForkIsReported() {
        // A failover where two writers both believed they held the lease. Silently taking one
        // would make the ledger's answer depend on which row was read first.
        List<EvidenceLedgerEntry> entries = new ArrayList<>(chain(3));
        EvidenceLedgerEntry two = entries.get(1);
        entries.add(2, EvidenceLedgerEntry.of(DOMAIN, two.sequence(), two.subjectKind(),
                "intent-2-from-the-other-writer", "mh1:other", two.occurredAt(),
                two.prevEntryHash()));

        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);

        assertFalse(report.intact());
        assertTrue(hasKind(report, EvidenceChainVerifier.FindingKind.FORK),
                report.findings().toString());
    }

    @Test
    @DisplayName("AC 8: a GENESIS entry is reported as a break, not smoothed over")
    void genesisIsReportedAsABreak() {
        // A span removed for a reason outside this system leaves a gap. The chain must SAY it
        // restarts — a gap that looks continuous is the worst outcome (design §5).
        List<EvidenceLedgerEntry> entries = new ArrayList<>();
        entries.add(EvidenceLedgerEntry.of(DOMAIN, 10, SubjectKind.GENESIS,
                "prior entries removed under a legal requirement", null,
                "2026-08-24T00:00:00Z", null));
        entries.add(EvidenceLedgerEntry.of(DOMAIN, 11, SubjectKind.CAPTURE_COMPLETED,
                "intent-11", "mh1:11", "2026-08-24T00:00:01Z",
                entries.get(0).entryHash()));

        EvidenceChainVerifier.Report report = EvidenceChainVerifier.verify(entries);

        assertFalse(report.intact(), "a restarted chain reported as unbroken");
        assertTrue(hasKind(report, EvidenceChainVerifier.FindingKind.CHAIN_BREAK),
                report.findings().toString());
    }

    @Test
    @DisplayName("an entry that commits to nothing cannot be built")
    void anEmptyEntryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> EvidenceLedgerEntry.of(DOMAIN, 1, SubjectKind.CAPTURE_COMPLETED,
                        "intent-1", "  ", "2026-08-24T00:00:00Z", null),
                "an entry with no payload digest commits to nothing, and would still take a "
                        + "position in the chain");
        assertThrows(IllegalArgumentException.class,
                () -> EvidenceLedgerEntry.of("  ", 1, SubjectKind.CAPTURE_COMPLETED,
                        "intent-1", "mh1:x", "2026-08-24T00:00:00Z", null));
    }

    @Test
    @DisplayName("a checkpoint commits to its span AND to every checkpoint before it")
    void aCheckpointChains() {
        List<EvidenceLedgerEntry> first = chain(3);
        String rootA = EvidenceChainVerifier.merkleRootOf(first);
        EvidenceCheckpoint a = EvidenceCheckpoint.of(DOMAIN, 1, 3, rootA, null,
                "2026-08-24T00:00:00Z");
        EvidenceCheckpoint b = EvidenceCheckpoint.of(DOMAIN, 4, 6, rootA, a.checkpointHash(),
                "2026-08-24T01:00:00Z");

        assertTrue(a.selfVerifies());
        assertTrue(b.selfVerifies());

        // Rewriting the earlier checkpoint changes the later one's input, so the later hash no
        // longer follows from what is stored — which is the whole point of chaining them.
        EvidenceCheckpoint tamperedA = EvidenceCheckpoint.of(DOMAIN, 1, 3,
                "0".repeat(64), null, "2026-08-24T00:00:00Z");
        assertNotEquals(a.checkpointHash(), tamperedA.checkpointHash());
        assertNotEquals(b.checkpointHash(), EvidenceCheckpoint.computeHash(DOMAIN, 4, 6, rootA,
                tamperedA.checkpointHash(), "2026-08-24T01:00:00Z"));
    }

    @Test
    @DisplayName("an EMPTY period produces no checkpoint")
    void anEmptyPeriodHasNoCheckpoint() {
        // A checkpoint over nothing would let "no entries were recorded" be committed to by a
        // value, and an anchor would then attest to it.
        assertEquals(null, EvidenceChainVerifier.merkleRootOf(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EvidenceCheckpoint.of(DOMAIN, 1, 1, null, null, "2026-08-24T00:00:00Z"));
    }

    @Test
    @DisplayName("the report says what the walk cannot establish")
    void theLimitsTravelWithTheReport() {
        // This check is the chain against ITSELF. An attacker who rewrote every following
        // entry AND the checkpoints leaves it intact — that is what the external anchor (P2)
        // is for, and the report has to say so where it will be read.
        String limits = String.valueOf(
                EvidenceChainVerifier.verify(chain(2)).asMap().get("limits"));

        assertTrue(limits.contains("outside this database"), limits);
        assertTrue(limits.contains("after the last checkpoint"), limits);
    }
}
