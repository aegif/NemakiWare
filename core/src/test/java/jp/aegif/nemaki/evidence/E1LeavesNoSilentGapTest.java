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
    private static final class FakeJournal implements ContentWriteJournal {
        private final Map<String, Unresolved> open = new LinkedHashMap<>();
        private final Map<String, String> closedWith = new LinkedHashMap<>();
        private boolean active = true;
        private boolean refuseOpen;
        private boolean throwOnClose;
        private int opened;

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
                long entrySequence) {
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
        public boolean isActive() {
            return active;
        }
    }

    /** A ledger whose append outcome the test chooses, counting how many times it was called. */
    private static final class FakeLedger extends EvidenceLedgerService {
        private AppendOutcome outcome = AppendOutcome.APPENDED;
        private final List<String> appended = new ArrayList<>();
        private long nextSequence = 7;

        @Override
        public AppendResult append(String domain, EvidenceLedgerEntry.SubjectKind kind,
                String subjectId, String payloadDigest, String occurredAt) {
            if (outcome != AppendOutcome.APPENDED) {
                return new AppendResult(outcome, -1, null, "the ledger said " + outcome);
            }
            appended.add(payloadDigest);
            return new AppendResult(AppendOutcome.APPENDED, nextSequence++, "hash-" + payloadDigest,
                    null);
        }
    }

    private static RecordContentStatementV1 statement(String versionId,
            RecordContentStatementV1.CommitmentKind kind) {
        return new RecordContentStatementV1("bedroom", "doc-1", versionId, "stream-1",
                "a".repeat(64), 12L, kind, null, "2026-09-20T00:00:00Z");
    }

    private static RecordContentStateRecorder recorderWith(FakeLedger ledger, FakeJournal journal) {
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
