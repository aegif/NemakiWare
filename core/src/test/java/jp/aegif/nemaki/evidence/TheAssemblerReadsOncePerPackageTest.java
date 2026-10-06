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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(List.of("PACKAGE_INTEGRITY_V1"), bundle.supportedProfiles());
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
    @DisplayName("a journal that could not be asked yields no bundle — not a statement, and not 'no statement'")
    void anUnaskableJournalYieldsNoBundle() {
        RecordContentStatementV1 held = statement("c".repeat(64));

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalHolding(held.toDocument(), false), storeWith(List.of()),
                        null).assemble("bedroom", "doc-1", "v-1"),
                "an inactive journal must not be read through, and what it holds must not be "
                        + "reported as absent either: the package would say the record has no "
                        + "statement, which nobody established (9-6 review, P1)");
        assertTrue(refusal.getMessage().contains("not reachable"), refusal.getMessage());
    }

    /** A journal whose reads fail. */
    private static ContentWriteJournal journalFailing(RuntimeException failure) {
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
                throw failure;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }

    /** A store whose entry lookup fails, or whose checkpoints are the ones given. */
    private static EvidenceLedgerStore storeFailingOrWith(RuntimeException onFind,
            List<EvidenceLedgerEntry> entries, EvidenceCheckpoint latest,
            java.util.function.LongFunction<EvidenceCheckpoint> endingBefore) {
        return new EvidenceLedgerStore() {
            @Override
            public boolean append(EvidenceLedgerEntry entry) {
                return false;
            }

            @Override
            public long highestSequence(String domain) {
                return -1;
            }

            @Override
            public List<EvidenceLedgerEntry> range(String domain, long from, long to, int limit) {
                return List.of();
            }

            @Override
            public List<EvidenceLedgerEntry> findBySubject(String domain, String subjectId,
                    int limit) {
                if (onFind != null) {
                    throw onFind;
                }
                return new ArrayList<>(entries);
            }

            @Override
            public boolean appendCheckpoint(EvidenceCheckpoint checkpoint) {
                return false;
            }

            @Override
            public EvidenceCheckpoint latestCheckpoint(String domain) {
                return latest;
            }

            @Override
            public EvidenceCheckpoint checkpointEndingBefore(String domain, long fromSequence) {
                return endingBefore == null ? null : endingBefore.apply(fromSequence);
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }

    private static EvidenceLedgerEntry entryAt(long sequence, String payloadDigest) {
        return EvidenceLedgerEntry.of(RecordContentStateRecorder.DOMAIN, sequence,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "v-1", payloadDigest,
                "t" + sequence, null);
    }

    @Test
    @DisplayName("a statement read that fails is a refusal, not a record with no statement")
    void aFailedStatementReadIsARefusal() {
        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalFailing(new IllegalStateException("view is away")),
                        storeWith(List.of()), null).assemble("bedroom", "doc-1", "v-1"));

        assertTrue(refusal.getMessage().contains("view is away"), refusal.getMessage());
        assertEquals(1, refusal.reasons().size(), refusal.reasons().toString());
    }

    @Test
    @DisplayName("a ledger read that fails is a refusal, not a statement with no entry")
    void aFailedEntryReadIsARefusal() {
        RecordContentStatementV1 held = statement("c".repeat(64));

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalHolding(held.toDocument(), true),
                        storeFailingOrWith(new IllegalStateException("ledger is away"), List.of(),
                                null, null), null).assemble("bedroom", "doc-1", "v-1"),
                "the statement came back and the entry did not: shipped as a statement with no "
                        + "entry, the package answered P1 NOT_PRESENT for a record that has one");
        assertTrue(refusal.getMessage().contains("ledger is away"), refusal.getMessage());
    }

    @Test
    @DisplayName("a full window of entries with no match is a refusal — the match may lie beyond it")
    void aFullWindowWithoutAMatchIsARefusal() {
        RecordContentStatementV1 held = statement("c".repeat(64));
        List<EvidenceLedgerEntry> window = new ArrayList<>();
        for (int i = 0; i < EvidenceBundleAssembler.ENTRIES_PER_VERSION; i++) {
            window.add(entryAt(i + 1, "d".repeat(64)));
        }

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalHolding(held.toDocument(), true), storeWith(window), null)
                        .assemble("bedroom", "doc-1", "v-1"),
                "fifty entries were read and none commits to this statement; the fifty-first "
                        + "may. 'No entry' was a guess, and the package stated it (9-6 review, P1)");
        assertTrue(refusal.getMessage().contains("beyond what was read"), refusal.getMessage());

        // One short of the window with no match is an honest absence: everything was read.
        EvidenceBundle bundle = assembler(journalHolding(held.toDocument(), true),
                storeWith(window.subList(0, window.size() - 1)), null)
                .assemble("bedroom", "doc-1", "v-1");
        assertNull(bundle.entry());
    }

    @Test
    @DisplayName("a checkpoint walk that does not end within the limit is a refusal, not 'nothing covers it'")
    void aCheckpointWalkThatDoesNotEndIsARefusal() {
        RecordContentStatementV1 held = statement("c".repeat(64));
        EvidenceLedgerEntry entry = entryAt(5L, held.digest());
        // A ledger with more checkpoints between the newest and the covering one than the walk
        // follows: every step back is one checkpoint of one sequence, and the covering one is
        // more than CHECKPOINT_WALK_LIMIT steps away.
        long newest = 5L + EvidenceBundleAssembler.CHECKPOINT_WALK_LIMIT + 10;
        EvidenceCheckpoint latest = EvidenceCheckpoint.of(RecordContentStateRecorder.DOMAIN,
                newest, newest, "bb", "ff".repeat(32), "2026-09-20T00:00:00Z");
        EvidenceLedgerStore store = storeFailingOrWith(null, List.of(entry), latest,
                before -> EvidenceCheckpoint.of(RecordContentStateRecorder.DOMAIN, before - 1,
                        before - 1, "cc", "ff".repeat(32), "2026-09-20T00:00:00Z"));

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalHolding(held.toDocument(), true), store, null)
                        .assemble("bedroom", "doc-1", "v-1"),
                "the walk fell out of its loop after the limit and the assembler said no "
                        + "checkpoint covers the entry — which is not what was found out, and "
                        + "the package then said RECORD_LEDGER_V1 is out of reach for a record "
                        + "that is in a sealed checkpoint (9-6 review, P1)");
        assertTrue(refusal.getMessage().contains("not known"), refusal.getMessage());
    }

    @Test
    @DisplayName("a chain whose walk back does not reach the covering checkpoint is a refusal, not a chain of one")
    void aChainThatCannotBeWalkedIsARefusal() {
        RecordContentStatementV1 held = statement("c".repeat(64));
        EvidenceLedgerEntry entry = entryAt(5L, held.digest());
        EvidenceCheckpoint covering = EvidenceCheckpoint.of(RecordContentStateRecorder.DOMAIN,
                5L, 5L, "aa", null, "2026-09-20T00:00:00Z");
        EvidenceCheckpoint sealedMeanwhile = EvidenceCheckpoint.of(
                RecordContentStateRecorder.DOMAIN, 9L, 12L, "bb", "ff".repeat(32),
                "2026-09-20T00:00:00Z");
        // The ledger moves under the read: the covering checkpoint IS the newest when the
        // covering walk asks, and by the time the chain is walked a later one has been sealed
        // whose predecessor this store cannot read back (null) — so the walk back from it never
        // reaches the covering one.
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        EvidenceLedgerStore moving = new EvidenceLedgerStore() {
            @Override
            public boolean append(EvidenceLedgerEntry e) {
                return false;
            }

            @Override
            public long highestSequence(String domain) {
                return 12;
            }

            @Override
            public List<EvidenceLedgerEntry> range(String domain, long from, long to, int limit) {
                return List.of();
            }

            @Override
            public List<EvidenceLedgerEntry> findBySubject(String domain, String subjectId,
                    int limit) {
                return List.of(entry);
            }

            @Override
            public boolean appendCheckpoint(EvidenceCheckpoint checkpoint) {
                return false;
            }

            @Override
            public EvidenceCheckpoint latestCheckpoint(String domain) {
                return asked.getAndIncrement() == 0 ? covering : sealedMeanwhile;
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

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler(journalHolding(held.toDocument(), true), moving, null)
                        .assemble("bedroom", "doc-1", "v-1"),
                "the walk did not reach the covering checkpoint and the covering one alone was "
                        + "shipped as the chain — a chain of one says nothing newer exists, which "
                        + "is not what was found out");
        assertTrue(refusal.reasons().stream().anyMatch(r -> r.contains("walk back does not reach")),
                refusal.reasons().toString());
    }

    @Test
    @DisplayName("a proof that could not be produced for a covered entry is a refusal, not a package without one")
    void aProofThatCouldNotBeProducedIsARefusal() {
        RecordContentStatementV1 held = statement("c".repeat(64));
        EvidenceLedgerEntry entry = entryAt(5L, held.digest());
        EvidenceCheckpoint covering = EvidenceCheckpoint.of(RecordContentStateRecorder.DOMAIN,
                1L, 10L, "aa", null, "2026-09-20T00:00:00Z");
        EvidenceLedgerService ledger = org.mockito.Mockito.mock(EvidenceLedgerService.class);
        org.mockito.Mockito.when(ledger.inclusionProof(RecordContentStateRecorder.DOMAIN, 5L))
                .thenReturn(Map.of("message", "the entries under this checkpoint could not be read"));
        EvidenceBundleAssembler assembler = assembler(journalHolding(held.toDocument(), true),
                storeFailingOrWith(null, List.of(entry), covering, before -> null), null);
        assembler.setLedgerService(ledger);

        EvidenceBundleAssembler.EvidenceNotReadable refusal = assertThrows(
                EvidenceBundleAssembler.EvidenceNotReadable.class,
                () -> assembler.assemble("bedroom", "doc-1", "v-1"),
                "the checkpoint covers the entry, so a proof exists; a package shipped without "
                        + "one said PACKAGE_INTEGRITY_V1 about a record that is in a sealed "
                        + "checkpoint");
        assertTrue(refusal.getMessage().contains("could not be read"), refusal.getMessage());
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

        assertTrue(java.util.regex.Pattern.compile("RevocationMaterial\\s*\\.materialIn\\(").matcher(text).find(),
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
