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

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R229: the newest statement of a version is the one with the highest ledger sequence — unless a row of the same content was
 * written later than it.
 *
 * <p>The ledger sequence is the order statements were RECORDED in. Two writes of one content row made on two replicas are
 * recorded in either order: the content made for a version that had none was stored, another replica appended to it and
 * recorded that, and the first statement was recorded after — the statement of bytes the append had replaced became the
 * newest (closing batch 38 review, Codex P1-1). In-place rewrites have the same shape (3.4.0). The store gives every write of a
 * row a revision of a higher generation, and that is the order the writes were made in. Across rows — content removed and made
 * again, a transition, a restore — the order stays the ledger's (the owner's choice, 2026-11-04).
 */
class ALaterWriteOfTheSameRowIsTheNewestTest {

    private static CouchContentWriteJournal journalOver(CloudantClientWrapper client) {
        CouchEvidenceLedgerStore store = new CouchEvidenceLedgerStore();
        store.useClientForTests(client);
        CouchContentWriteJournal journal = new CouchContentWriteJournal();
        journal.setLedgerStore(store);
        return journal;
    }

    /** A closed row as the journal stores it: its statement (about content {@code row}, or none), sequence and generation. */
    private static Map<String, Object> row(String digest, String row, long sequence, Object generation) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("contentDigest", digest);
        if (row != null) {
            statement.put("contentStreamId", row);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("statement", statement);
        props.put("entrySequence", sequence);
        if (generation != null) {
            props.put("writtenGeneration", generation);
        }
        return props;
    }

    @SafeVarargs
    private static CloudantClientWrapper clientReturning(Map<String, Object>... documents) {
        List<ViewResultRow> rows = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            Document doc = mock(Document.class);
            when(doc.getProperties()).thenReturn(document);
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getDoc()).thenReturn(doc);
            rows.add(row);
        }
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(rows);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), anyMap())).thenReturn(result);
        return client;
    }

    private static Object newestDigest(Map<String, Object>... rows) {
        return journalOver(clientReturning(rows)).latestRecorded("bedroom", "v-1").statement().get("contentDigest");
    }

    @Test
    @DisplayName("a write of the same row stored later is the newest, though it was recorded first (R229)")
    void aLaterWriteOfTheSameRowIsTheNewest() {
        // The append stored its bytes at generation 5 and was recorded at 19; the content it appended to, stored at 4, was
        // recorded at 20.
        assertEquals("appended", newestDigest(row("appended", "att-1", 19, 5L), row("made", "att-1", 20, 4L)),
                "the statement recorded last was taken for the newest, though a later write of its row replaced its bytes");
    }

    @Test
    @DisplayName("a write of another row is ordered by the ledger, whatever its generation (R229 — across rows the order is the ledger's)")
    void aWriteOfAnotherRowIsOrderedByTheLedger() {
        // Generations are of one row each: 9 on att-1 says nothing about 2 on att-2.
        assertEquals("again", newestDigest(row("before", "att-1", 10, 9L), row("again", "att-2", 12, 2L)),
                "a write of another row with a higher generation was taken for the newest over one recorded after it");
        assertEquals("before", newestDigest(row("before", "att-1", 12, 9L), row("again", "att-2", 10, 2L)),
                "a write of another row recorded earlier was taken for the newest");
    }

    @Test
    @DisplayName("a newest row with no generation — a restore, a transition, a 3.4.0 row — keeps the ledger's order (R229)")
    void aNewestRowWithNoGenerationKeepsTheLedgersOrder() {
        // A restore of att-1 recorded after the writes of att-1: it was placed by the ledger, not by the row's revision.
        assertEquals("restored", newestDigest(row("written", "att-1", 20, 5L), row("restored", "att-1", 30, null)),
                "a row placed by the ledger was overtaken by an earlier write of its content");
        // A transition names no content row.
        assertEquals("archived", newestDigest(row("written", "att-1", 20, 5L), row("archived", null, 30, null)),
                "a transition recorded after the writes was not the newest");
        // Rows closed before the generation was kept: the ledger's order, as in 3.4.0.
        assertEquals("third", newestDigest(row("first", "att-1", 10, null), row("third", "att-1", 30, null),
                row("second", "att-1", 20, null)), "rows with no generation were not ordered by the ledger");
    }

    @Test
    @DisplayName("a generation that cannot be read is a refusal, not a row of no generation")
    void aGenerationThatCannotBeReadIsARefusal() {
        assertThrows(ContentWriteJournal.ContentWriteJournalUnavailable.class,
                () -> journalOver(clientReturning(row("a", "att-1", 10, 4L), row("b", "att-1", 11, "5")))
                        .latestRecorded("bedroom", "v-1"),
                "a row whose place among its content's writes cannot be read was read as one placed by the ledger");
    }

    @Test
    @DisplayName("the journal keeps the generation a row was closed with, and closes a row given none without one")
    @SuppressWarnings("unchecked")
    void theJournalKeepsTheGenerationARowWasClosedWith() {
        for (Long generation : java.util.Arrays.asList(7L, null)) {
            CloudantClientWrapper client = mock(CloudantClientWrapper.class);
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("versionObjectId", "v-1");
            open.put("closedAt", null);
            Document existing = mock(Document.class);
            when(existing.getProperties()).thenReturn(open);
            when(existing.getRev()).thenReturn("1-open");
            when(client.get(CouchContentWriteJournal.documentId("intent-1"))).thenReturn(existing);
            com.ibm.cloud.cloudant.v1.model.DocumentResult ok = mock(com.ibm.cloud.cloudant.v1.model.DocumentResult.class);
            when(ok.isOk()).thenReturn(Boolean.TRUE);
            org.mockito.ArgumentCaptor<Map<String, Object>> written = org.mockito.ArgumentCaptor.forClass(Map.class);
            when(client.update(written.capture())).thenReturn(ok);
            CouchContentWriteJournal journal = journalOver(client);

            ContentWriteJournal.CloseOutcome closed = generation == null
                    ? journal.close("intent-1", "v-1", "d".repeat(64), Map.of("contentDigest", "d"), 42L)
                    : journal.close("intent-1", "v-1", "d".repeat(64), Map.of("contentDigest", "d"), 42L, generation);

            assertEquals(ContentWriteJournal.CloseOutcome.CLOSED, closed, "the row was not closed");
            assertEquals(generation, written.getValue().get("writtenGeneration"),
                    () -> "the row was closed with a generation other than " + generation + " — the newest statement of its "
                            + "content is read from it");
        }
    }

    // ------------------------------------------------------------------------------------------------- the recorder

    private static RecordContentStateRecorder recorderOver(ContentWriteJournal journal, EvidenceLedgerService ledger) {
        RecordContentStateRecorder recorder = new RecordContentStateRecorder();
        recorder.setJournal(journal);
        recorder.setLedgerService(ledger);
        return recorder;
    }

    private static EvidenceLedgerService appending() {
        EvidenceLedgerService ledger = mock(EvidenceLedgerService.class);
        when(ledger.append(anyString(), any(), anyString(), anyString(), anyString())).thenReturn(
                new EvidenceLedgerService.AppendResult(EvidenceLedgerService.AppendOutcome.APPENDED, 42, null, null));
        return ledger;
    }

    private static ContentWriteJournal journal() {
        ContentWriteJournal journal = mock(ContentWriteJournal.class);
        when(journal.isActive()).thenReturn(true);
        when(journal.close(anyString(), anyString(), anyString(), anyMap(), anyLong(), anyLong()))
                .thenReturn(ContentWriteJournal.CloseOutcome.CLOSED);
        when(journal.close(anyString(), anyString(), anyString(), anyMap(), anyLong()))
                .thenReturn(ContentWriteJournal.CloseOutcome.CLOSED);
        return journal;
    }

    private static RecordContentStatementV1 statement() {
        return new RecordContentStatementV1("bedroom", "v-1", "v-1", "att-1", "a".repeat(64), 7,
                RecordContentStatementV1.CommitmentKind.UPDATED, null, "2026-11-04T00:00:00Z");
    }

    private static final RecordContentStateRecorder.Pending PENDING = new RecordContentStateRecorder.Pending(
            "intent-1", "bedroom", "v-1", "v-1", ContentWriteJournal.WriteKind.UPDATE_IN_PLACE);

    @Test
    @DisplayName("the row is closed with the generation of the revision the write left its content's row at (R229)")
    void theRowIsClosedWithTheGenerationOfTheWrittenRevision() {
        ContentWriteJournal journal = journal();
        RecordContentStateRecorder.Result result = recorderOver(journal, appending())
                .recordAndClose(PENDING.writtenAt("7-0a1b"), statement());
        assertEquals(RecordContentStateRecorder.Outcome.CHAINED, result.outcome(), "the write was not recorded: " + result);
        verify(journal).close(eq("intent-1"), eq("v-1"), anyString(), anyMap(), eq(42L), eq(7L));
        verify(journal, never()).close(anyString(), anyString(), anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("a revision that cannot be read as a place leaves the statement unrecorded and the row open (R229)")
    void aRevisionThatCannotBeReadLeavesTheStatementUnrecorded() {
        for (String revision : List.of("0a1b", "-1-x", "0-x", "x-0a1b")) {
            ContentWriteJournal journal = journal();
            EvidenceLedgerService ledger = appending();
            RecordContentStateRecorder recorder = recorderOver(journal, ledger);
            RecordContentStateRecorder.Result result = recorder.recordAndClose(PENDING.writtenAt(revision), statement());
            assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_OPEN, result.outcome(),
                    () -> "a statement with the unreadable place " + revision + " was " + result.outcome());
            verify(ledger, never()).append(anyString(), any(), anyString(), anyString(), anyString());
            assertEquals(1, recorder.unrecordedWritesSinceStartup(), () -> "the unrecorded write " + revision
                    + " was not counted");
        }
    }

    @Test
    @DisplayName("a write with no revision to place it — a restore, a transition — closes its row as before")
    void aWriteWithNoRevisionClosesItsRowAsBefore() {
        ContentWriteJournal journal = journal();
        RecordContentStateRecorder.Result result = recorderOver(journal, appending()).recordAndClose(
                new RecordContentStateRecorder.Pending("intent-1", "bedroom", "v-1", "v-1",
                        ContentWriteJournal.WriteKind.RESTORE), statement());
        assertEquals(RecordContentStateRecorder.Outcome.CHAINED, result.outcome(), "the write was not recorded: " + result);
        verify(journal).close(eq("intent-1"), eq("v-1"), anyString(), anyMap(), eq(42L));
    }
}
