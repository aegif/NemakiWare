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

import jp.aegif.nemaki.evidence.E1LeavesNoSilentGapTest.FakeJournal;
import jp.aegif.nemaki.evidence.E1LeavesNoSilentGapTest.FakeLedger;
import jp.aegif.nemaki.evidence.RecordContentTransitionV1.BytesNow;
import jp.aegif.nemaki.evidence.RecordContentTransitionV1.Transition;
import jp.aegif.nemaki.util.test.JavaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transitions of a version's bytes (W10 / W12 / W13 / W14 / deleteContentStream, and the
 * RESTORED state of W11) are recorded the way E1 records a write: a row opened before, a
 * statement chained after, and every place it can fail leaves something an operator can list.
 *
 * <p>The crash tests here were written WITH the implementation, not enumerated on paper first
 * (owner's decision, 2026-09-21): each one is a place the code above actually has.
 */
class TransitionsLeaveNoSilentGapTest {

    private static final String AT = "2026-09-22T00:00:00Z";
    private static final String DIGEST = "b".repeat(64);

    private static RecordContentTransitionV1 transition(String prior, Long from) {
        return new RecordContentTransitionV1("bedroom", "doc-1", "v-1", Transition.ARCHIVED,
                BytesNow.ARCHIVE_DB, prior, from, AT);
    }

    // ---------------------------------------------------------------- the type

    @Test
    @DisplayName("a transition statement has no contentDigest — the boundary with the state statement")
    void theTypeHasNoContentDigest() {
        Set<String> components = Arrays.stream(RecordContentTransitionV1.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertFalse(components.contains("contentDigest"),
                "RecordContentTransitionV1 grew a contentDigest. The design keeps 'what the "
                        + "bytes are' and 'what happened to the bytes' as two types so no reader "
                        + "has to decide what a null digest means");
        assertEquals(Set.of("repositoryId", "objectId", "versionObjectId", "transition",
                        "bytesNow", "priorContentDigest", "priorStatementEntrySequence",
                        "recordedAt"),
                transition(null, null).toDocument().keySet(),
                "the shipped document's fields moved; §5.3 lists these and a verifier reads them");
    }

    @Test
    @DisplayName("the prior digest and its source entry are set together or not at all")
    void thePriorIsPaired() {
        assertThrows(IllegalArgumentException.class, () -> transition(DIGEST, null),
                "a copied digest with no source entry is a value nobody can check");
        assertThrows(IllegalArgumentException.class, () -> transition(null, 41L),
                "a source entry with no digest names an entry for no reason");
        assertEquals(DIGEST, transition(DIGEST, 41L).priorContentDigest());
        assertNull(transition(null, null).priorContentDigest(), "'not known' is null, not empty");
        assertThrows(IllegalArgumentException.class, () -> transition("B".repeat(64), 41L),
                "an upper-case digest would hash to different canonical bytes");
    }

    @Test
    @DisplayName("null fields are written as null, so 'not known' survives the trip to a package")
    void nullsAreWrittenNotOmitted() {
        Map<String, Object> doc = transition(null, null).toDocument();
        assertTrue(doc.containsKey("priorContentDigest") && doc.get("priorContentDigest") == null,
                "priorContentDigest was omitted rather than written as null. A reader that finds "
                        + "the key absent cannot tell 'not known' from 'an older writer'");
        // And the digest is over the null-tagged form: two documents differing only in a
        // prior are different statements.
        assertFalse(transition(null, null).digest().equals(transition(DIGEST, 41L).digest()));
    }

    // ---------------------------------------------------------------- recording

    @Test
    @DisplayName("a transition is chained under its own kind, and the row closes with the transition document")
    void aTransitionIsChainedUnderItsOwnKind() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.ARCHIVE, AT);
        RecordContentStateRecorder.Result result = recorder.recordTransition(pending,
                Transition.ARCHIVED, BytesNow.ARCHIVE_DB);

        assertEquals(RecordContentStateRecorder.Outcome.CHAINED, result.outcome(), result.detail());
        assertEquals(List.of(EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION), ledger.kinds,
                "the entry was appended under " + ledger.kinds + ". A transition chained as "
                        + "RECORD_CONTENT_STATE would be read as 'the bytes are these' by every "
                        + "reader of that kind, including the verifier's content binding");
        assertEquals("ARCHIVED", journal.statements.get("v-1").get("transition"),
                "the journal row did not close with the transition document, so no package can "
                        + "ship it");
        assertTrue(journal.unresolved(10).isEmpty());
    }

    @Test
    @DisplayName("the prior digest is COPIED from the ledger's last state statement, with its entry")
    void thePriorIsCopiedFromTheLedgerNotComputed() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        // The state statement E1 recorded when the bytes were written, at sequence 7.
        RecordContentStateRecorder.Pending write = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.CREATE_DOCUMENT, AT);
        recorder.recordAndClose(write, new RecordContentStatementV1("bedroom", "doc-1", "v-1",
                "stream-1", DIGEST, 12L, RecordContentStatementV1.CommitmentKind.CAPTURED, null, AT));

        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.ARCHIVE, AT);
        recorder.recordTransition(pending, Transition.ARCHIVED, BytesNow.ARCHIVE_DB);

        Map<String, Object> stored = journal.statements.get("v-1");
        assertEquals(DIGEST, stored.get("priorContentDigest"),
                "the transition does not carry the digest the ledger holds for these bytes. "
                        + "Computing one instead — from bytes that may be gone, or read at a "
                        + "different moment — is a different observation passed off as the "
                        + "earlier statement");
        assertEquals(7L, stored.get("priorStatementEntrySequence"),
                "the copy does not name the entry it came from, so a reader cannot check it "
                        + "against its source");
    }

    @Test
    @DisplayName("a second transition carries the prior forward through the first")
    void aSecondTransitionCarriesThePriorForward() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        RecordContentStateRecorder.Pending write = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.CREATE_DOCUMENT, AT);
        recorder.recordAndClose(write, new RecordContentStatementV1("bedroom", "doc-1", "v-1",
                "stream-1", DIGEST, 12L, RecordContentStatementV1.CommitmentKind.CAPTURED, null, AT));
        recorder.recordTransition(recorder.openBeforeWriting("bedroom", "v-1", "v-1",
                ContentWriteJournal.WriteKind.ARCHIVE, AT), Transition.ARCHIVED, BytesNow.ARCHIVE_DB);

        // Now the newest statement for v-1 is the ARCHIVED transition. A destroy after it
        // still names the state statement at 7 — copying to the archive did not change the bytes.
        recorder.recordTransition(recorder.openBeforeWriting("bedroom", "v-1", "v-1",
                ContentWriteJournal.WriteKind.DESTROY_ARCHIVE, AT), Transition.ARCHIVE_DESTROYED,
                BytesNow.NONE);

        Map<String, Object> stored = journal.statements.get("v-1");
        assertEquals("ARCHIVE_DESTROYED", stored.get("transition"));
        assertEquals(DIGEST, stored.get("priorContentDigest"));
        assertEquals(7L, stored.get("priorStatementEntrySequence"),
                "the second transition points at the first transition's entry rather than at the "
                        + "state statement the digest came from");
    }

    @Test
    @DisplayName("with no prior statement, the prior is null — not empty, not computed")
    void noPriorWhenTheLedgerHasNone() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);

        recorder.recordTransition(recorder.openBeforeWriting("bedroom", "v-1", "v-1",
                ContentWriteJournal.WriteKind.CONTENT_REMOVED, AT), Transition.CONTENT_REMOVED,
                BytesNow.ARCHIVE_DB);

        Map<String, Object> stored = journal.statements.get("v-1");
        assertTrue(stored.containsKey("priorContentDigest"));
        assertNull(stored.get("priorContentDigest"));
        assertNull(stored.get("priorStatementEntrySequence"));
    }

    @Test
    @DisplayName("a journal that cannot be asked gives no prior, and the transition still says so")
    void noPriorWhenTheJournalCannotBeAsked() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        journal.active = false;

        assertNull(recorder.priorFor("bedroom", "v-1"),
                "an inactive journal answered a prior. 'Could not ask' has to reach the statement "
                        + "as null, not as whatever a stale read held");
    }

    @Test
    @DisplayName("bytes moved and the ledger refusing leaves an OPEN row naming the transition path")
    void aRefusedAppendLeavesTheTransitionRowOpen() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.COLD_TRANSFER, AT);
        ledger.outcome = EvidenceLedgerService.AppendOutcome.UNAVAILABLE;

        RecordContentStateRecorder.Result result = recorder.recordTransition(pending,
                Transition.MOVED_TO_COLD, BytesNow.COLD);

        assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_OPEN, result.outcome());
        List<ContentWriteJournal.Unresolved> open = journal.unresolved(10);
        assertEquals(1, open.size(), "the bytes moved and nothing records it; the row is how an "
                + "operator finds out");
        assertEquals(ContentWriteJournal.WriteKind.COLD_TRANSFER, open.get(0).kind());
    }

    // ---------------------------------------------------------------- abandon

    @Test
    @DisplayName("an abandoned row is neither open nor a statement")
    void anAbandonedRowIsNeitherOpenNorAStatement() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.COLD_TRANSFER, AT);

        assertTrue(recorder.abandon(pending, "the cold write was undone"));

        assertTrue(journal.unresolved(10).isEmpty(),
                "an undone cold move is still listed as a gap. Nothing was left behind; a row "
                        + "that says otherwise is over-refusal");
        assertNull(journal.statementFor("bedroom", "v-1"),
                "abandoning wrote a statement. There is nothing to state");
        assertEquals("the cold write was undone", journal.abandoned.get(pending.intentId()));
        assertTrue(ledger.appended.isEmpty(), "abandoning appended to the ledger");
    }

    @Test
    @DisplayName("an abandon the journal cannot perform leaves the row open — the safe side")
    void anAbandonThatCannotHappenLeavesTheRowOpen() {
        FakeLedger ledger = new FakeLedger();
        FakeJournal journal = new FakeJournal();
        RecordContentStateRecorder recorder = E1LeavesNoSilentGapTest.recorderWith(ledger, journal);
        RecordContentStateRecorder.Pending pending = recorder.openBeforeWriting("bedroom", "v-1",
                "v-1", ContentWriteJournal.WriteKind.COLD_TRANSFER, AT);
        journal.refuseAbandon = true;

        assertFalse(recorder.abandon(pending, "the cold write was undone"));
        assertEquals(1, journal.unresolved(10).size(),
                "the journal could not close the row and the recorder reported it closed");
    }

    // ---------------------------------------------------------------- the wiring, read from source

    private static final List<Path> WIRED_IN = List.of(
            Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java"),
            Path.of("src/main/java/jp/aegif/nemaki/businesslogic/impl/delegate/ArchiveServiceDelegate.java"),
            Path.of("src/main/java/jp/aegif/nemaki/archive/RetentionScheduler.java"));

    private static String productSource() throws Exception {
        StringBuilder all = new StringBuilder();
        for (Path source : WIRED_IN) {
            all.append(JavaSource.withoutComments(JavaSource.read(source.toString()))).append('\n');
        }
        return all.toString();
    }

    /**
     * Each transition is closed with the bytesNow the design assigns it, at the call site.
     *
     * <p>W14's UNKNOWN is the one that matters most: the destroy path does not ask cold
     * storage, so COLD there would be a claim nothing checked (owner's decision: not checked
     * in this batch, UNKNOWN stays). Read from the product, so changing the call site without
     * changing this table goes red.
     */
    @Test
    @DisplayName("every transition is closed with the bytesNow the design assigns it")
    void everyTransitionSaysWhereTheBytesAre() throws Exception {
        Map<Transition, BytesNow> designed = Map.of(
                Transition.ARCHIVED, BytesNow.ARCHIVE_DB,
                Transition.COPIED_TO_COLD, BytesNow.ARCHIVE_DB,
                Transition.MOVED_TO_COLD, BytesNow.COLD,
                Transition.ARCHIVE_DESTROYED, BytesNow.NONE,
                Transition.ARCHIVE_DESTROYED_LEAVING_COLD_BLOB, BytesNow.UNKNOWN,
                Transition.CONTENT_REMOVED, BytesNow.ARCHIVE_DB);
        String source = productSource();
        for (Transition transition : Transition.values()) {
            // The call: Transition.X, <whitespace> BytesNow.Y — the two arguments of the close.
            Matcher call = Pattern.compile("Transition\\." + transition.name()
                    + ",\\s*(?:jp\\.aegif\\.nemaki\\.evidence\\.RecordContentTransitionV1\\.)?BytesNow\\.([A-Z_]+)")
                    .matcher(source);
            assertTrue(call.find(), "no call site closes a row with Transition." + transition
                    + ". A transition value with no producer is a vocabulary nothing writes");
            assertEquals(designed.get(transition).name(), call.group(1),
                    "Transition." + transition + " is closed with BytesNow." + call.group(1)
                            + " and the design says " + designed.get(transition) + ". For W14 "
                            + "that is the difference between 'not checked' and a claim");
            assertFalse(call.find(), "Transition." + transition + " is closed at two call sites; "
                    + "this lock reads one and would miss the other");
        }
    }

    @Test
    @DisplayName("a restore is recorded as RESTORED — not as a first capture, not as an update")
    void aRestoreIsRecordedAsRestored() throws Exception {
        String delegate = JavaSource.withoutComments(JavaSource.read(WIRED_IN.get(1).toString()));
        assertTrue(delegate.contains("CommitmentKind.RESTORED"),
                "ArchiveServiceDelegate no longer records a restore as RESTORED. CAPTURED would "
                        + "read as 'the version's first bytes' and UPDATED as 'replaced in place'; "
                        + "both are false of bytes put back from the archive");
        assertFalse(delegate.contains("CommitmentKind.CAPTURED")
                        || delegate.contains("CommitmentKind.UPDATED"),
                "the archive delegate records a state statement under a kind that is not RESTORED");
    }

    @Test
    @DisplayName("a cold-transfer row is abandoned only after the undo verifiably succeeded")
    void abandonFollowsAVerifiedUndoOnly() throws Exception {
        String scheduler = JavaSource.withoutComments(JavaSource.read(WIRED_IN.get(2).toString()));
        Matcher abandons = Pattern.compile("abandonColdTransfer\\(coldRow").matcher(scheduler);
        int count = 0;
        while (abandons.find()) {
            count++;
            String before = scheduler.substring(Math.max(0, abandons.start() - 200), abandons.start());
            assertTrue(before.contains("if (coldUndone)"),
                    "an abandon of the cold-transfer row is not guarded by the undo having "
                            + "succeeded. An undo that failed leaves a blob whose existence the "
                            + "open row is the only record of");
        }
        assertEquals(2, count, "the scheduler abandons the row at " + count + " site(s); the two "
                + "undo paths (refused disposition, failed local delete) are the ones there are");
    }

    /**
     * The interface default returns null — "not reported" — which the delegate treats as a
     * row left open. Correct for an implementation that cannot report, and a silent loss of
     * every RESTORED statement if the one real implementation stopped overriding it.
     */
    @Test
    @DisplayName("the CouchDB DAO reports what a restore wrote back, rather than inheriting 'not reported'")
    void theDaoReportsWhatItWroteBack() throws Exception {
        String dao = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java"));
        assertTrue(dao.contains("RestoredBytes restoreDocumentWithArchiveRecording("),
                "ContentDaoServiceImpl no longer overrides restoreDocumentWithArchiveRecording. "
                        + "The default answers null (not reported), so every restore's row stays "
                        + "open and no RESTORED statement reaches the chain — silently");
        String delegate = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/ArchiveDaoDelegate.java"));
        assertTrue(delegate.contains("new java.security.DigestInputStream(body, digestOfRestored)"),
                "the digest of restored bytes is no longer taken under the PUT that writes them "
                        + "back; a digest taken any other way is a different observation");
    }

    @Test
    @DisplayName("every BytesNow value has a producer")
    void everyBytesNowHasAProducer() throws Exception {
        String source = productSource();
        for (BytesNow where : BytesNow.values()) {
            assertTrue(source.contains("BytesNow." + where.name()),
                    "BytesNow." + where + " is written by no path. A value nothing produces is "
                            + "read by a verifier as possible, and a fixture would measure a "
                            + "world that cannot occur");
        }
    }
}
