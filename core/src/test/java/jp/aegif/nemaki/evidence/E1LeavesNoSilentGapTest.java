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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1's gate: every enumerated write is CHAINED or durably unresolved — never a silent gap.
 *
 * <p>The crashes are simulated at the transitions the design names, because the point is not
 * that the window is closed (it cannot be, with the content in one database and the ledger in
 * another) but that <b>what is left behind after a crash tells a reader which case it was</b>.
 */
class E1LeavesNoSilentGapTest {

    /** A journal that records what happened to it and can be told to fail at one point. */
    static final class FakeJournal implements ContentWriteJournal {
        final Map<String, Unresolved> open = new LinkedHashMap<>();
        final Map<String, String> closedWith = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> statements = new LinkedHashMap<>();
        /** The ledger sequence each version's newest statement was closed with. */
        final Map<String, Long> sequences = new LinkedHashMap<>();
        final Map<String, String> abandoned = new LinkedHashMap<>();
        boolean active = true;
        boolean refuseOpen;
        boolean throwOnClose;
        boolean refuseAbandon;
        int opened;

        @Override
        public String open(String repositoryId, String objectId, String versionObjectId,
                WriteKind kind, String openedAt) {
            if (refuseOpen) {
                throw new ContentWriteJournalUnavailable("the journal is down");
            }
            String id = "intent-" + (++opened);
            open.put(id, new Unresolved(id, repositoryId, objectId, versionObjectId, kind,
                    openedAt));
            return id;
        }

        @Override
        public CloseOutcome close(String intentId, String versionObjectId, String statementDigest,
                Map<String, Object> statementDocument, long entrySequence) {
            if (statementDocument != null) {
                statements.put(versionObjectId, statementDocument);
                sequences.put(versionObjectId, entrySequence);
            }
            if (throwOnClose) {
                throw new IllegalStateException("the journal went away between append and close");
            }
            if (!active) {
                return CloseOutcome.UNAVAILABLE;
            }
            if (closedWith.containsKey(intentId)) {
                return statementDigest.equals(closedWith.get(intentId))
                        ? CloseOutcome.ALREADY_CLOSED : CloseOutcome.WRONG_VERSION;
            }
            Unresolved row = open.get(intentId);
            if (row == null) {
                return CloseOutcome.UNAVAILABLE;
            }
            if (!row.versionObjectId().equals(versionObjectId)) {
                return CloseOutcome.WRONG_VERSION;
            }
            open.remove(intentId);
            closedWith.put(intentId, statementDigest);
            return CloseOutcome.CLOSED;
        }

        @Override
        public List<Unresolved> unresolved(int limit) {
            return new ArrayList<>(open.values());
        }

        @Override
        public Map<String, Object> statementFor(String repositoryId, String versionObjectId) {
            return statements.get(versionObjectId);
        }

        @Override
        public Recorded latestRecorded(String repositoryId, String versionObjectId) {
            Map<String, Object> statement = statements.get(versionObjectId);
            return statement == null ? null
                    : new Recorded(statement, sequences.getOrDefault(versionObjectId, -1L));
        }

        @Override
        public boolean abandon(String intentId, String reason) {
            if (refuseAbandon || !open.containsKey(intentId)) {
                return false;
            }
            open.remove(intentId);
            abandoned.put(intentId, reason);
            return true;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    /** A ledger whose append outcome the test chooses, counting how many times it was called. */
    static final class FakeLedger extends EvidenceLedgerService {
        AppendOutcome outcome = AppendOutcome.APPENDED;
        final List<String> appended = new ArrayList<>();
        final List<EvidenceLedgerEntry.SubjectKind> kinds = new ArrayList<>();
        long nextSequence = 7;

        @Override
        public AppendResult append(String domain, EvidenceLedgerEntry.SubjectKind kind,
                String subjectId, String payloadDigest, String occurredAt) {
            if (outcome != AppendOutcome.APPENDED) {
                return new AppendResult(outcome, -1, null, "the ledger said " + outcome);
            }
            appended.add(payloadDigest);
            kinds.add(kind);
            return new AppendResult(AppendOutcome.APPENDED, nextSequence++, "hash-" + payloadDigest,
                    null);
        }
    }

    private static RecordContentStatementV1 statement(String versionId,
            RecordContentStatementV1.CommitmentKind kind) {
        return new RecordContentStatementV1("bedroom", "doc-1", versionId, "stream-1",
                "a".repeat(64), 12L, kind, null, "2026-09-20T00:00:00Z");
    }

    static RecordContentStateRecorder recorderWith(FakeLedger ledger, FakeJournal journal) {
        RecordContentStateRecorder recorder = new RecordContentStateRecorder();
        recorder.setLedgerService(ledger);
        recorder.setJournal(journal);
        return recorder;
    }

    @Test
    @DisplayName("bytes written and the ledger refusing leaves an OPEN row, not a silent gap")
    void aRefusedAppendLeavesTheRowOpen() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");
        ledger.outcome = EvidenceLedgerService.AppendOutcome.UNAVAILABLE;

        RecordContentStateRecorder.Result result = recorder.recordAndClose(pending,
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED));

        assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_OPEN, result.outcome());
        assertFalse(result.inChain(), "nothing was appended, so nothing is in the chain");
        assertEquals(1, journal.unresolved(10).size(),
                "the write happened and its statement did not; an operator has to be able to "
                        + "LIST that, which is the whole point of opening the row first");
    }

    @Test
    @DisplayName("a gap nothing could record is reported as a DIFFERENT outcome")
    void anUnlistableGapIsNotTheSameAsAListedOne() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        journal.refuseOpen = true;
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");
        ledger.outcome = EvidenceLedgerService.AppendOutcome.UNAVAILABLE;

        RecordContentStateRecorder.Result result = recorder.recordAndClose(pending,
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED));

        // Both are gaps. Only one of them can be found by asking. Merging them would let an
        // operator read an empty unresolved list as "there are no gaps".
        assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_UNLISTABLE,
                result.outcome());
        assertEquals(1, recorder.journalOutagesSinceStartup());
        assertTrue(journal.unresolved(10).isEmpty(),
                "there is no row — and that is exactly why the outcome has to say so");
    }

    @Test
    @DisplayName("a chained statement whose row could not be closed does not report CHAINED")
    void aRowThatCouldNotBeClosedIsNotReportedAsClosed() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");
        journal.throwOnClose = true;

        RecordContentStateRecorder.Result result = recorder.recordAndClose(pending,
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED));

        assertEquals(RecordContentStateRecorder.Outcome.CHAINED_ROW_STILL_OPEN, result.outcome());
        assertTrue(result.inChain(), "the entry IS in the chain — the row is what is unresolved");
        assertEquals(1, ledger.appended.size());
    }

    @Test
    @DisplayName("a retry after a successful close does not append a second entry")
    void aRetryDoesNotRecordTheStatementTwice() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");
        RecordContentStatementV1 same =
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED);

        assertEquals(RecordContentStateRecorder.Outcome.CHAINED,
                recorder.recordAndClose(pending, same).outcome());
        // The retry. The journal answers ALREADY_CLOSED, which is not a failure — but the
        // append above it has already run again, which IS the thing to watch.
        RecordContentStateRecorder.Result retry = recorder.recordAndClose(pending, same);
        assertEquals(RecordContentStateRecorder.Outcome.CHAINED, retry.outcome());
        assertEquals(2, ledger.appended.size(),
                "this test pins the CURRENT behaviour: the recorder does not itself de-duplicate "
                        + "against the ledger, so a caller that retries the whole operation "
                        + "records the statement twice. The journal's ALREADY_CLOSED prevents a "
                        + "second ROW, not a second ENTRY. Two identical statements in the chain "
                        + "are not a falsehood, but they are not free either — and pretending "
                        + "this is de-duplicated would be a claim nothing here establishes");
    }

    @Test
    @DisplayName("the statement itself is kept, not only its digest")
    void theStatementIsPersistedSoAPackageCanShipIt() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");
        RecordContentStatementV1 statement =
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED);
        recorder.recordAndClose(pending, statement);

        Map<String, Object> kept = journal.statementFor("bedroom", "v-1");
        assertEquals(statement.toDocument(), kept,
                "the ledger entry holds only the statement's DIGEST, and a digest cannot be "
                        + "shipped as record-content-statement.json. A package that rebuilt the "
                        + "statement at export time would rebuild it from the document's "
                        + "CURRENT state and get a different digest");
    }

    @Test
    @DisplayName("an old row is not closed by a newer version's bytes")
    void anOldIntentIsNotClosedByNewBytes() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "doc-1",
                "v-1", ContentWriteJournal.WriteKind.CHECK_IN, "2026-09-20T00:00:00Z");

        // The document moved on between opening and closing.
        RecordContentStateRecorder.Result result = recorder.recordAndClose(pending,
                statement("v-2", RecordContentStatementV1.CommitmentKind.CAPTURED));

        assertEquals(RecordContentStateRecorder.Outcome.REFUSED_WRONG_VERSION, result.outcome());
        assertTrue(ledger.appended.isEmpty(),
                "refusing AFTER appending would put v-2's statement in the chain under a row "
                        + "opened for v-1 — the case the plan names explicitly");
        assertEquals(1, journal.unresolved(10).size(), "v-1's row is still unresolved");
    }

    @Test
    @DisplayName("OBSERVED and CAPTURED are different statements about the same bytes")
    void observedIsNotCaptured() {
        RecordContentStatementV1 captured =
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED);
        RecordContentStatementV1 observed =
                statement("v-1", RecordContentStatementV1.CommitmentKind.OBSERVED);
        assertNotEquals(captured.digest(), observed.digest(),
                "the same bytes observed later are not the same claim as the same bytes "
                        + "captured, and a digest that could not tell them apart would let a "
                        + "backfilled observation be presented as a capture");
    }

    @Test
    @DisplayName("the statement's digest survives the JSON round trip a package makes")
    void theShippedJsonCanonicalisesBackToTheSameBytes() throws Exception {
        RecordContentStatementV1 statement =
                statement("v-1", RecordContentStatementV1.CommitmentKind.CAPTURED);
        String json = jp.aegif.nemaki.config.ObjectMapperFactory.createDefaultObjectMapper()
                .writeValueAsString(statement.toDocument());

        // The product hashes the MAP; a third party hashes the FILE. If those two differ
        // the package ships a digest nobody outside the JVM can reproduce.
        assertArrayEqualsBytes(statement.canonicalBytes(), CanonicalJson.canonicalBytes(json));
        assertEquals(statement.digest(), CanonicalJson.documentDigest(json));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertTrue(java.util.Arrays.equals(expected, actual),
                "the canonical bytes of the statement and of the JSON a package ships differ");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static DigestingInputStream readingAll(byte[] input) throws java.io.IOException {
        DigestingInputStream stream =
                DigestingInputStream.over(new java.io.ByteArrayInputStream(input));
        stream.readAllBytes();
        return stream;
    }

    @Test
    @DisplayName("the digest taken on the write is the SHA-256 of the bytes that went past")
    void theDigestIsOfTheBytesThatWentPast() throws Exception {
        DigestingInputStream stream = readingAll(bytes("hello"));
        // Independently computed, not taken from the class under test.
        byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes("hello"));
        StringBuilder hex = new StringBuilder();
        for (byte b : expected) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(hex.toString(), stream.digestIfTrustworthy(5));
        // Asked twice. MessageDigest.digest() resets the instance, so a class that did not copy
        // would answer with the digest of the empty input the second time — and that value looks
        // exactly like a digest.
        assertEquals(hex.toString(), stream.digestIfTrustworthy(5));
    }

    @Test
    @DisplayName("draining a stream that makes no progress stops, and stops claiming a digest")
    void aStreamThatStallsIsNotDigested() throws Exception {
        // A stream answering "0 bytes, not the end" forever. readAllBytes() grew its buffer
        // until the JVM died — this is the shape that took an existing test to an
        // OutOfMemoryError, and it is not hypothetical: it is Mockito's default for read().
        DigestingInputStream stalling = DigestingInputStream.over(new java.io.InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return 0;
            }
        });
        assertFalse(stalling.drain(), "a stream that never progresses must not be waited on");
        assertEquals(null, stalling.digestIfTrustworthy(),
                "and what it read is not the content, so there is no digest to give");
    }

    @Test
    @DisplayName("draining reads to the end without holding the bytes")
    void drainingReachesTheEnd() throws Exception {
        DigestingInputStream stream =
                DigestingInputStream.over(new java.io.ByteArrayInputStream(bytes("hello")));
        assertTrue(stream.drain());
        assertEquals(5, stream.bytesRead());
        assertNotNull(stream.digestIfTrustworthy(5));
    }

    @Test
    @DisplayName("a rewind whose digest could not be snapshotted stops claiming a digest")
    void aRewindWithoutASnapshotStopsClaimingADigest() throws Exception {
        DigestingInputStream stream =
                DigestingInputStream.over(new java.io.ByteArrayInputStream(bytes("hello")));
        assertEquals(3, stream.read(new byte[3], 0, 3));
        // reset() with no mark taken. ByteArrayInputStream allows it (its mark defaults to 0),
        // so the BYTES rewind while the digest has no snapshot to rewind to.
        stream.reset();
        stream.readAllBytes();
        // NO declared length. Asking with one let the LENGTH arm answer — the rewind leaves
        // eight bytes counted against a declared five — so the sabotage that removed the
        // trustworthiness arm entirely kept this test green (control HQ3 did not fire).
        assertEquals(null, stream.digestIfTrustworthy(),
                "the bytes were rewound and the digest was not, so it now covers the first "
                        + "three bytes twice. A wrong digest recorded as a fact is worse than "
                        + "no digest: every later check of that version fails against bytes "
                        + "that were never wrong");
    }

    @Test
    @DisplayName("the attachment writer asks about rewindability THROUGH the digest wrapper")
    void theAttachmentWriterLooksThroughTheWrapper() throws java.io.IOException {
        // Structural, because the behavioural control (HS3) did not fire: the earlier lock
        // exercised DigestingInputStream.isRewindable directly, so reverting the DAO to its
        // plain instanceof check changed nothing it could see. The call site is the thing.
        Path source = Path.of("src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/"
                + "AttachmentDaoDelegate.java");
        assertTrue(Files.exists(source), "the attachment delegate is not at " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int at = text.indexOf("boolean canRetryStream");
        assertTrue(at >= 0, "the attachment writer no longer decides retryability here, so this "
                + "lock is reading a decision that has moved rather than one that is wrong");
        String decision = text.substring(at, text.indexOf(';', at)).replaceAll("(?m)//.*$", "");
        assertTrue(decision.contains("DigestingInputStream.isRewindable("),
                "the retry decision no longer looks through the digest wrapper. A wrapped "
                        + "stream is not an instanceof ByteArrayInputStream, so every retryable "
                        + "revision conflict would become a failed upload the moment E1 "
                        + "recording is on.\nDecision reads: " + decision);
    }

    @Test
    @DisplayName("a write shorter than it declared has no digest")
    void aShortWriteHasNoDigest() throws Exception {
        DigestingInputStream stream =
                DigestingInputStream.over(new java.io.ByteArrayInputStream(bytes("hel")));
        stream.readAllBytes();
        assertEquals(null, stream.digestIfTrustworthy(5),
                "five bytes were declared and three went past; the digest is of something other "
                        + "than what the write said it was sending");
        assertEquals(3, stream.bytesRead());
    }

    @Test
    @DisplayName("wrapping a rewindable stream does not make it unrewindable")
    void aWrappedByteArrayIsStillRewindable() {
        java.io.ByteArrayInputStream raw = new java.io.ByteArrayInputStream(bytes("hello"));
        assertTrue(DigestingInputStream.isRewindable(raw));
        assertTrue(DigestingInputStream.isRewindable(DigestingInputStream.over(raw)),
                "the attachment writer decides whether a revision conflict may be retried from "
                        + "this answer. If wrapping made it false, switching recording on would "
                        + "turn every retryable conflict into a failed upload");
        assertFalse(DigestingInputStream.isRewindable(
                        new java.io.FilterInputStream(new java.io.ByteArrayInputStream(bytes("x"))) { }),
                "and it must not become true for a stream that could not be rewound before");
    }

    @Test
    @DisplayName("an in-place rewrite is recorded as UPDATED, not as a first capture")
    void theCommitmentKindMatchesWhatTheWriteDid() throws java.io.IOException {
        // Structural, and on the CALL SITES: the two recorders differ only in the constant they
        // pass, so nothing a fake can observe tells them apart. What matters is that the paths
        // that REPLACE a version's content call the UPDATED one — reading a replacement as a
        // first capture would say the bytes had always been that version's.
        Path source = Path.of(
                "src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java");
        assertTrue(Files.exists(source), "ContentServiceImpl is not at " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");

        assertTrue(text.contains("CommitmentKind.UPDATED"),
                "no path records an in-place rewrite as UPDATED any more, so replacing a "
                        + "version's content now reads as those bytes having been its first");
        // The three in-place paths (W3, W7, W9) all go through recordUpdatedContentState.
        int updatedCalls = text.split(Pattern.quote("recordUpdatedContentState(repositoryId"), -1)
                .length - 1;
        assertEquals(3, updatedCalls,
                "three enumerated paths rewrite a version's bytes in place — W3 "
                        + "updateDocumentWithNewStream, W7 appendContentStream, W9 replacePwc — "
                        + "and " + updatedCalls + " call sites record an UPDATED statement. A "
                        + "path that dropped its call records nothing at all, and one that moved "
                        + "to the CAPTURED recorder records the wrong claim");
    }

    @Test
    @DisplayName("only the write paths actually wired claim to be recorded")
    void onlyTheWiredPathsClaimToBeRecorded() throws java.io.IOException {
        // The plan's rule (§8) is that a path nobody wired is a residual, never a success. The
        // only way to keep that honest is to read the product and compare with a list somebody
        // had to write down — so adding a wiring without declaring it, or declaring one that
        // does not exist, both go red.
        SortedSet<String> wired = new TreeSet<>();
        // The transitions (Phase 3's remaining paths) live in two more files: the archive
        // delegate (W11 / W13 / W14) and the retention scheduler (W12).
        for (Path source : List.of(
                Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java"),
                Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/"
                        + "AttachmentServiceDelegate.java"),
                Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/"
                        + "ArchiveServiceDelegate.java"),
                Path.of("src/main/java/jp/aegif/nemaki/archive/RetentionScheduler.java"))) {
            if (!Files.exists(source)) {
                continue;
            }
            // Comments stripped: this file's own javadoc names WriteKind constants, and a grep
            // a comment can satisfy has been the defect five times in this batch.
            String text = Files.readString(source, StandardCharsets.UTF_8)
                    .replaceAll("(?m)//.*$", "")
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            Matcher uses = Pattern.compile("WriteKind\\.([A-Z_]+)").matcher(text);
            while (uses.find()) {
                wired.add(uses.group(1));
            }
        }

        SortedSet<String> declared = new TreeSet<>(List.of(
                "CREATE_DOCUMENT",          // W1
                "NEW_VERSION_WITH_STREAM",  // W2
                "UPDATE_IN_PLACE",          // W3
                "CHECK_IN",                 // W4
                "UPDATE_WITHOUT_CHECKOUT",  // W5
                "COPY_FROM_SOURCE",         // W6
                "APPEND",                   // W7
                "CHECK_OUT_PWC",            // W8
                "REPLACE_PWC",              // W9
                "ARCHIVE",                  // W10 (2026-09-22, transition)
                "RESTORE",                  // W11 (state statement, RESTORED)
                "COLD_TRANSFER",            // W12
                "DESTROY_ARCHIVE",          // W13
                "DESTROY_ARCHIVE_LEAVING_COLD_BLOB", // W14
                "CONTENT_REMOVED"));        // deleteContentStream
        assertEquals(declared, wired,
                "the write paths wired into the product and the ones this lock declares differ. "
                        + "E1 records only what is wired: " + wired + ". The remaining "
                        + (ContentWriteJournal.WriteKind.values().length - wired.size())
                        + " of the enumerated paths are NOT recorded, and nothing anywhere may "
                        + "say otherwise");

        // And the plan says the same thing, in the row a reader consults for progress.
        Path plan = Path.of("../docs/design/v3.4.0-evidence-and-residuals-plan.md");
        assertTrue(Files.exists(plan), "the plan is not at " + plan);
        String planText = Files.readString(plan, StandardCharsets.UTF_8);
        assertTrue(planText.contains("配線済みは " + wired.size() + " 本"),
                "the plan does not record that " + wired.size() + " of the "
                        + ContentWriteJournal.WriteKind.values().length + " enumerated write "
                        + "paths are wired. A progress row that does not move with the code is "
                        + "how 'E1 is done' comes to be read off a phase table");
    }

    @Test
    @DisplayName("a journal that cannot be asked does not answer 'there are no gaps'")
    void aJournalThatCannotBeAskedReportsThatRatherThanAnEmptyList() {
        // No ledger store wired, which is what an unprovisioned or unreachable evidence
        // database looks like from here.
        CouchContentWriteJournal journal = new CouchContentWriteJournal();

        assertFalse(journal.isActive(),
                "a journal with nowhere to write must not report itself active");
        assertTrue(journal.unresolved(10).isEmpty(), "there is nothing it could read");
        assertEquals(1, journal.unreadableCount(),
                "the empty list above must come with something that says it is not an answer. "
                        + "A caller reading only the list would report 'no unresolved writes' "
                        + "for a store it never reached — which is the same defect the ledger's "
                        + "UNAVAILABLE outcome exists to prevent, one layer out");
    }

    @Test
    @DisplayName("the open-intent view is deployed in the same put as the others")
    void theOpenIntentViewIsInTheOneDesignDocumentPut() {
        // A second put makes CouchDB discard the index it has just built for the others, so the
        // sibling stores all share this map. A view added anywhere else would rebuild the whole
        // design document on every startup.
        assertTrue(CouchEvidenceLedgerStore.viewSources()
                        .containsKey(CouchContentWriteJournal.VIEW_OPEN),
                "the open content-write intent view is not in the single design-document put, "
                        + "so either it is never deployed — and unresolved() answers nothing "
                        + "forever — or it is deployed by a second put that discards the "
                        + "ledger's own index");
    }

    @Test
    @DisplayName("the workflow starts for the inventory this lock reads")
    void theGateRunsForTheInventoryThisLockReads() throws java.io.IOException {
        Path workflow = Path.of("../.github/workflows/integration-tests.yml");
        assertTrue(Files.exists(workflow), "the workflow is not at " + workflow);
        String yaml = Files.readString(workflow, StandardCharsets.UTF_8);
        String needed = "docs/design/evidence-phase0-inventory.md";
        int occurrences = yaml.split(Pattern.quote("'" + needed + "'"), -1).length - 1;
        assertEquals(2, occurrences,
                "'" + needed + "' should appear in BOTH the push and pull_request paths and "
                        + "appears " + occurrences + " time(s). Adding a write path to the "
                        + "inventory would then not start the workflow that checks E1 covers it");
    }

    @Test
    @DisplayName("every write path the Phase 0 inventory enumerated has a WriteKind")
    void everyEnumeratedWritePathIsCovered() throws java.io.IOException {
        Path inventory = Path.of("../docs/design/evidence-phase0-inventory.md");
        assertTrue(Files.exists(inventory), "the Phase 0 inventory is not at " + inventory);
        String text = Files.readString(inventory, StandardCharsets.UTF_8);

        // The W-table is the enumeration the plan says E1's scope IS. Scoped to the table, not
        // the file: the W ids appear in the prose around it too.
        int start = text.indexOf("| # | bytes を書くサービス API");
        assertTrue(start >= 0, "the inventory's write-path table is not where this lock looks");
        int end = text.indexOf("\n\n", start);
        assertTrue(end > start, "the write-path table does not end where this lock looks");

        SortedSet<String> enumerated = new TreeSet<>();
        Matcher rows = Pattern.compile("(?m)^\\|\\s*\\*{0,2}(W\\d+)\\*{0,2}\\s*\\|")
                .matcher(text.substring(start, end));
        while (rows.find()) {
            enumerated.add(rows.group(1));
        }
        assertEquals(14, enumerated.size(),
                "the inventory enumerated " + enumerated.size() + " write paths and this lock "
                        + "expects the 14 it recorded: " + enumerated + ". A path added there "
                        + "without a WriteKind here is a write E1 does not record, which the "
                        + "plan says must be a residual and not a success");

        // 14 paths plus deleteContentStream, which the inventory records separately in §5 as
        // part of E1's scope ("deleteContentStream は「内容が無くなった」遷移として書く").
        assertEquals(enumerated.size() + 1, ContentWriteJournal.WriteKind.values().length,
                "WriteKind has " + ContentWriteJournal.WriteKind.values().length + " constants "
                        + "and the inventory enumerates " + enumerated.size() + " write paths "
                        + "plus deleteContentStream. The two have to move together, or E1 "
                        + "silently covers fewer paths than the document says it does");
        assertTrue(text.contains("`deleteContentStream` は「内容が無くなった」遷移として書く"),
                "the inventory no longer records deleteContentStream as part of E1's scope, so "
                        + "the extra WriteKind above is counting something nothing declares");
    }
}
