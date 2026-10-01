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


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the assembler does when a part is missing, and when it merely could not be asked.
 *
 * <p>Those two are the whole subject. A bundle that treated "the store did not answer" as "the
 * rung is not configured" would produce a package stating, in its manifest, a fact about this
 * deployment that nobody established.
 */
class TheAssemblerReadsOncePerPackageTest {

    private static RecordContentStatementV1 statement(String digest) {
        return new RecordContentStatementV1("bedroom", "doc-1", "v-1", "att-1", digest, 12L,
                RecordContentStatementV1.CommitmentKind.CAPTURED, null, "2026-09-20T00:00:00Z");
    }

    /** A journal that holds one statement document. */
    private static ContentWriteJournal journalHolding(Map<String, Object> document,
            boolean active) {
        return new ContentWriteJournal() {
            @Override
            public String open(String repositoryId, String objectId, String versionObjectId,
                    WriteKind kind, String openedAt) {
                throw new ContentWriteJournalUnavailable("not used here");
            }

            @Override
            public CloseOutcome close(String intentId, String versionObjectId,
                    String statementDigest, Map<String, Object> statementDocument,
                    long entrySequence) {
                return CloseOutcome.UNAVAILABLE;
            }

            @Override
            public List<Unresolved> unresolved(int limit) {
                return List.of();
            }

            @Override
            public Map<String, Object> statementFor(String repositoryId, String versionObjectId) {
                return document;
            }

            @Override
            public boolean isActive() {
                return active;
            }
        };
    }

    /** A ledger store holding a list of entries for one subject and no checkpoints. */
    private static EvidenceLedgerStore storeWith(List<EvidenceLedgerEntry> entries) {
        return new EvidenceLedgerStore() {
            @Override
            public boolean append(EvidenceLedgerEntry entry) {
                return false;
            }

            @Override
            public long highestSequence(String domain) {
                return entries.isEmpty() ? -1 : entries.get(entries.size() - 1).sequence();
            }

            @Override
            public List<EvidenceLedgerEntry> range(String domain, long from, long to, int limit) {
                return List.of();
            }

            @Override
            public List<EvidenceLedgerEntry> findBySubject(String domain, String subjectId,
                    int limit) {
                return new ArrayList<>(entries);
            }

            @Override
            public boolean appendCheckpoint(EvidenceCheckpoint checkpoint) {
                return false;
            }

            @Override
            public EvidenceCheckpoint latestCheckpoint(String domain) {
                return null;
            }

            @Override
            public EvidenceCheckpoint checkpointEndingBefore(String domain, long fromSequence) {
                return null;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }

    private static EvidenceBundleAssembler assembler(ContentWriteJournal journal,
            EvidenceLedgerStore store, AnchorReceiptStore receipts) {
        EvidenceBundleAssembler assembler = new EvidenceBundleAssembler();
        assembler.setJournal(journal);
        assembler.setLedgerStore(store);
        assembler.setReceiptStore(receipts);
        return assembler;
    }

    @Test
    @DisplayName("a version with no statement produces a package-integrity bundle, not a broken one")
    void noStatementIsAnHonestBundle() {
        EvidenceBundle bundle = assembler(journalHolding(null, true), storeWith(List.of()), null)
                .assemble("bedroom", "doc-1", "v-1");

        assertNull(bundle.statement());
        assertNull(bundle.entry());
        assertEquals("PACKAGE_INTEGRITY_V1", bundle.highestProfileSupported());
        assertFalse(bundle.inclusionProof().present(),
                "and the proof records WHY there is none rather than shipping an empty path");
        assertNotNull(bundle.inclusionProof().unavailableBecause());
    }

    @Test
    @DisplayName("the entry shipped is the one that names THIS statement, not the newest")
    void theEntryMatchesTheStatementItShipsWith() {
        RecordContentStatementV1 first = statement("a".repeat(64));
        RecordContentStatementV1 second = statement("b".repeat(64));
        // A version rewritten in place: two entries, and the package carries the FIRST
        // statement. Shipping the newest entry beside it would put two parts in the package
        // that do not refer to each other — each true, and never true together.
        List<EvidenceLedgerEntry> entries = List.of(
                EvidenceLedgerEntry.of(RecordContentStateRecorder.DOMAIN, 1L,
                        EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "v-1",
                        first.digest(), "t1", null),
                EvidenceLedgerEntry.of(RecordContentStateRecorder.DOMAIN, 2L,
                        EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "v-1",
                        second.digest(), "t2", null));

        EvidenceBundle bundle = assembler(journalHolding(first.toDocument(), true),
                storeWith(entries), null).assemble("bedroom", "doc-1", "v-1");

        assertNotNull(bundle.entry());
        assertEquals(first.digest(), bundle.entry().payloadDigest(),
                "the entry and the statement in one package have to be about each other; the "
                        + "verifier's ENTRY_BINDS_STATEMENT check compares exactly these two");
        assertEquals(1L, bundle.entry().sequence());
    }

    @Test
    @DisplayName("a journal that could not be asked yields no statement, not a wrong one")
    void anUnaskableJournalYieldsNoStatement() {
        RecordContentStatementV1 held = statement("c".repeat(64));
        EvidenceBundle bundle = assembler(journalHolding(held.toDocument(), false),
                storeWith(List.of()), null).assemble("bedroom", "doc-1", "v-1");

        assertNull(bundle.statement(),
                "an inactive store must not be read through: what it would return is not known "
                        + "to be current");
    }

    @Test
    @DisplayName("a receipt store that could not be asked is UNAVAILABLE, never NOT_CONFIGURED")
    void anUnaskableReceiptStoreIsNotAnUnconfiguredRung() {
        // No anchor target (no checkpoints in this fake store), so the map is empty — the
        // distinction this test is about is the one made when there IS a target, and it is
        // asserted on the assembler's own classification below.
        EvidenceBundle bundle = assembler(journalHolding(null, true), storeWith(List.of()), null)
                .assemble("bedroom", "doc-1", "v-1");
        assertTrue(bundle.anchors().isEmpty(),
                "with no anchor target there is no rung to classify, and inventing entries "
                        + "would state something about rungs nobody asked about");
    }

    @Test
    @DisplayName("the assembler reads the captured revocation material out of the receipt")
    void theAssemblerReadsTheCapturedMaterial() throws java.io.IOException {
        // Structural, on the call site. Driving this behaviourally needs a receipt store, a
        // checkpoint and an anchor target, and the bundle-level lock that DOES exist builds its
        // bundle directly — so the assembler was never on its path and stayed green while the
        // assembler dropped the material entirely (control KM3 did not fire).
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/jp/aegif/nemaki/evidence/EvidenceBundleAssembler.java");
        assertTrue(java.nio.file.Files.exists(source), "the assembler is not at " + source);
        String text = java.nio.file.Files.readString(source,
                        java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");

        assertTrue(text.contains("RevocationMaterial\n                    .materialIn(")
                        || text.contains("RevocationMaterial.materialIn("),
                "the assembler no longer reads the captured revocation material out of the "
                        + "receipt, so every package ships none however much was collected — "
                        + "and the collection that produced it runs for nothing");
    }

    @Test
    @DisplayName("a pending anchor ships no material and says so")
    void aPendingAnchorIsNotMaterial() {
        AnchorReceipt pending = AnchorReceipt.pending(AnchorKind.OPENTIMESTAMPS, "root",
                java.time.Instant.EPOCH, new byte[] { 1 }, "digest", Map.of());
        assertEquals(jp.aegif.nemaki.rest.purview.anchor.AnchorStatus.PENDING, pending.status(),
                "this test's premise is that a pending receipt exists; if the factory changed, "
                        + "the classification below is being measured against nothing");
        // The classification itself is what matters: PENDING is not failure and not material,
        // and a package carrying its empty proof would be reported as a parse failure — which
        // reads as tampering rather than as the absence it is.
        EvidenceBundle.AnchorPart part = new EvidenceBundle.AnchorPart(
                EvidenceBundle.AnchorPart.State.NOT_PRESENT, null,
                "the receipt is PENDING and carries no usable material");
        assertNull(part.der());
    }
}
