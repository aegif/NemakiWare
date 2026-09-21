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

import jp.aegif.nemaki.evidence.RecordContentTransitionV1.BytesNow;
import jp.aegif.nemaki.evidence.RecordContentTransitionV1.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version whose newest statement is a transition is exported with it, and with the prior it
 * cites (spec §4.2 {@code prior/}, §5.3b), so a verifier can check the copy against its source.
 */
class TransitionPackagesAreExportedTest {

    private static final String AT = "2026-09-22T00:00:00Z";

    private static final RecordContentStatementV1 PRIOR = new RecordContentStatementV1("bedroom",
            "doc-1", "doc-1", "att-1", "a".repeat(64), 12L,
            RecordContentStatementV1.CommitmentKind.CAPTURED, null, "2026-09-20T00:00:00Z");

    private static RecordContentTransitionV1 transition(String priorDigest, Long from) {
        return new RecordContentTransitionV1("bedroom", "doc-1", "doc-1",
                Transition.ARCHIVE_DESTROYED, BytesNow.NONE, priorDigest, from, AT);
    }

    private static EvidenceLedgerEntry entry(long sequence, EvidenceLedgerEntry.SubjectKind kind,
            String payloadDigest) {
        return new EvidenceLedgerEntry("record-content", sequence, kind, "doc-1", payloadDigest,
                AT, null, "hash-" + sequence);
    }

    /** A journal whose newest statement is {@code newest} and whose entry 1 holds the prior. */
    private static ContentWriteJournal journalHolding(Map<String, Object> newest,
            Map<String, Object> atOne) {
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
            public Recorded latestRecorded(String repositoryId, String versionObjectId) {
                return newest == null ? null : new Recorded(newest, 2L);
            }

            @Override
            public Recorded recordedAt(String repositoryId, String versionObjectId,
                    long entrySequence) {
                return entrySequence == 1L && atOne != null ? new Recorded(atOne, 1L) : null;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }

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
                List<EvidenceLedgerEntry> out = new ArrayList<>();
                for (EvidenceLedgerEntry e : entries) {
                    if (e.sequence() >= from && e.sequence() <= to) {
                        out.add(e);
                    }
                }
                return out;
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
            EvidenceLedgerStore store) {
        EvidenceBundleAssembler assembler = new EvidenceBundleAssembler();
        assembler.setJournal(journal);
        assembler.setLedgerStore(store);
        return assembler;
    }

    private static SortedSet<String> write(EvidenceBundle bundle, Path dir) throws Exception {
        new EvidenceBundleWriter(bundle).writeTo(dir);
        SortedSet<String> found = new TreeSet<>();
        Path root = dir.resolve(EvidenceBundleWriter.DIRECTORY);
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> found.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return found;
    }

    @Test
    @DisplayName("a transition is read back as a transition, and the prior it cites is fetched by the cited sequence")
    void aTransitionIsAssembledWithItsPrior() {
        RecordContentTransitionV1 transition = transition(PRIOR.contentDigest(), 1L);
        EvidenceBundle bundle = assembler(
                journalHolding(transition.toDocument(), PRIOR.toDocument()),
                storeWith(List.of(
                        entry(1L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, PRIOR.digest()),
                        entry(2L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION,
                                transition.digest()))))
                .assemble("bedroom", "doc-1", "doc-1");

        assertTrue(bundle.statementIsTransition(), "the newest statement is a transition and was "
                + "read back as " + bundle.statement());
        assertEquals(transition.digest(), bundle.statement().digest());
        assertNotNull(bundle.entry(), "the entry that commits to the transition was not found");
        assertEquals(EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION,
                bundle.entry().subjectKind());
        assertNotNull(bundle.prior(), "the transition cites entry 1 and the package would not "
                + "carry it, so no verifier could check the copied digest against its source");
        assertEquals(PRIOR.digest(), bundle.prior().statement().digest());
        assertEquals(1L, bundle.prior().entry().sequence());
    }

    @Test
    @DisplayName("a transition bundle supports PACKAGE_INTEGRITY_V1 only — P1 cannot be VERIFIED for it")
    void aTransitionBundleDoesNotClaimP1() {
        RecordContentTransitionV1 transition = transition(null, null);
        EvidenceCheckpoint covering = new EvidenceCheckpoint("record-content", 2L, 2L, "root",
                null, AT, "cp-hash");
        EvidenceLedgerEntry entry = entry(2L,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION, transition.digest());
        EvidenceBundle bundle = new EvidenceBundle("bedroom", "doc-1", "doc-1", transition, entry,
                new EvidenceBundle.InclusionProof(MerkleTree.hashLeaf(entry.entryHash()),
                        List.of(), null),
                covering, List.of(covering), covering, Map.of(), AT);

        assertEquals("PACKAGE_INTEGRITY_V1", bundle.highestProfileSupported(),
                "a transition claims no bytes, so P1's content binding is NOT_PRESENT for it by "
                        + "definition. An export that REQUIRES P1 has to be refused, not handed a "
                        + "package that answers INDETERMINATE");
    }

    @Test
    @DisplayName("the prior is written under prior/ with its canonical forms, and the manifest lists them")
    void thePriorIsWrittenUnderPrior(@TempDir Path dir) throws Exception {
        RecordContentTransitionV1 transition = transition(PRIOR.contentDigest(), 1L);
        EvidenceLedgerEntry priorEntry = entry(1L,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, PRIOR.digest());
        EvidenceLedgerEntry entry = entry(2L,
                EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION, transition.digest());
        EvidenceBundle bundle = new EvidenceBundle("bedroom", "doc-1", "doc-1", transition, entry,
                new EvidenceBundle.InclusionProof(null, null, "no checkpoint yet"), null,
                List.of(), null, Map.of(), AT, new EvidenceBundle.Prior(PRIOR, priorEntry));

        SortedSet<String> found = write(bundle, dir);

        for (String expected : List.of("prior/record-content-statement.json",
                "prior/record-content-statement.c14n", "prior/ledger-entry.json",
                "prior/ledger-entry.c14n")) {
            assertTrue(found.contains(expected), expected + " was not written; found " + found);
        }
        String manifest = Files.readString(dir.resolve(EvidenceBundleWriter.DIRECTORY)
                .resolve("bundle-manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("prior/record-content-statement.json")
                        && manifest.contains("prior/ledger-entry.json"),
                "the manifest does not list the prior files, so a reader checking the manifest "
                        + "against the package finds files nobody committed to");
        String shipped = Files.readString(dir.resolve(EvidenceBundleWriter.DIRECTORY)
                .resolve("record-content-statement.json"), StandardCharsets.UTF_8);
        assertTrue(shipped.contains("\"transition\"") && !shipped.contains("contentDigest"),
                "the transition was not shipped as the transition document: " + shipped);
    }

    @Test
    @DisplayName("an entry that commits to the document under the other kind is not shipped beside it")
    void aKindMismatchShipsNoEntry() {
        RecordContentTransitionV1 transition = transition(null, null);
        EvidenceBundle bundle = assembler(
                journalHolding(transition.toDocument(), null),
                storeWith(List.of(entry(2L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE,
                        transition.digest()))))
                .assemble("bedroom", "doc-1", "doc-1");

        assertTrue(bundle.statementIsTransition());
        assertNull(bundle.entry(), "the ledger says STATE over a document with no content "
                + "digest; shipping the pair hands the recipient a contradiction this node wrote");
    }

    @Test
    @DisplayName("a cited prior the ledger does not commit to is not shipped — and neither is one the journal cannot find")
    void aPriorThatIsNotAPairIsNotShipped() {
        RecordContentTransitionV1 transition = transition(PRIOR.contentDigest(), 1L);
        // Entry 1 commits to a DIFFERENT statement than the journal holds at sequence 1.
        EvidenceBundle mismatched = assembler(
                journalHolding(transition.toDocument(), PRIOR.toDocument()),
                storeWith(List.of(
                        entry(1L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, "f".repeat(64)),
                        entry(2L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION,
                                transition.digest()))))
                .assemble("bedroom", "doc-1", "doc-1");
        assertNull(mismatched.prior(), "a prior the cited entry does not commit to was shipped; "
                + "the verifier would fail it, and the contradiction is this node's to log");

        EvidenceBundle unfound = assembler(
                journalHolding(transition.toDocument(), null),
                storeWith(List.of(
                        entry(1L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE, PRIOR.digest()),
                        entry(2L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION,
                                transition.digest()))))
                .assemble("bedroom", "doc-1", "doc-1");
        assertNull(unfound.prior(), "the journal holds no statement at the cited sequence and a "
                + "prior was shipped anyway");
        assertNotNull(unfound.entry(), "the transition's own entry is unaffected by a missing prior");
    }

    @Test
    @DisplayName("a state statement is still read back as a state statement, with no prior")
    void aStateStatementIsUnchanged() {
        EvidenceBundle bundle = assembler(
                journalHolding(PRIOR.toDocument(), null),
                storeWith(List.of(entry(1L, EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE,
                        PRIOR.digest()))))
                .assemble("bedroom", "doc-1", "doc-1");

        assertTrue(bundle.statement() instanceof RecordContentStatementV1);
        assertNotNull(bundle.entry());
        assertNull(bundle.prior());
    }
}
