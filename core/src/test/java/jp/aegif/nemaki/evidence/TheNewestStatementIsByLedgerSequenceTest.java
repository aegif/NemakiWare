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
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The journal's "newest statement for a version" is the one with the highest ledger sequence.
 *
 * <p>The rows are keyed by a random intent id, so the order the view returns them in is the
 * order of UUIDs — nothing to do with time. The first {@code statementFor} took the last row
 * and called it the newest; for a version written twice (W3 / W7 / W9 rewrite in place, or a
 * transition after a state) that shipped whichever statement's id sorted last. Found while
 * wiring the transitions (2026-09-22): the prior a transition copies is "the newest state
 * statement", and reading it by row order would have copied the wrong digest half the time.
 */
class TheNewestStatementIsByLedgerSequenceTest {

    private static CouchContentWriteJournal journalOver(CloudantClientWrapper client) {
        CouchEvidenceLedgerStore store = new CouchEvidenceLedgerStore();
        store.useClientForTests(client);
        CouchContentWriteJournal journal = new CouchContentWriteJournal();
        journal.setLedgerStore(store);
        return journal;
    }

    private static Map<String, Object> row(String digest, Object sequence) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("contentDigest", digest);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("statement", statement);
        props.put("entrySequence", sequence);
        return props;
    }

    @SuppressWarnings("unchecked")
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

    @Test
    @DisplayName("the newest statement is the one with the highest ledger sequence, whatever the row order")
    void theHighestSequenceWinsNotTheLastRow() {
        // Row order is the OPPOSITE of time: the newest (sequence 90) comes first.
        CouchContentWriteJournal journal = journalOver(clientReturning(
                row("c".repeat(64), 90L), row("a".repeat(64), 10L), row("b".repeat(64), 50L)));

        ContentWriteJournal.Recorded latest = journal.latestRecorded("bedroom", "v-1");

        assertEquals(90L, latest.entrySequence(),
                "the journal took row " + latest.entrySequence() + " as the newest. Rows are in "
                        + "UUID order; only the ledger sequence is time, and a transition copies "
                        + "its prior from whichever statement this returns");
        assertEquals("c".repeat(64), latest.statement().get("contentDigest"));
        assertEquals("c".repeat(64), journal.statementFor("bedroom", "v-1").get("contentDigest"),
                "statementFor and latestRecorded disagree about which statement is newest");
    }

    /**
     * The first version of this test SKIPPED such a row and asserted the other one was the
     * newest — codifying the defect (Codex review, P1): the view emits closed rows only, so a
     * statement with no readable sequence is a row whose place in time cannot be read, and
     * calling any other row "newest" is an older statement passed off as the latest.
     */
    @Test
    @DisplayName("a statement row with no readable sequence makes 'newest' undeterminable — null, not the other row")
    void aRowWithoutASequenceMakesTheNewestUndeterminable() {
        assertNull(journalOver(clientReturning(row("a".repeat(64), 10L), row("z".repeat(64), null)))
                        .latestRecorded("bedroom", "v-1"),
                "a statement whose sequence could not be read was skipped and another row was "
                        + "reported as the newest; a transition would copy that row's digest as "
                        + "the prior of bytes it may not describe");
        assertNull(journalOver(clientReturning(row("z".repeat(64), null)))
                        .latestRecorded("bedroom", "v-1"),
                "a version whose only row has no readable sequence answered a statement");
        assertNull(journalOver(clientReturning(row("a".repeat(64), 10L), row("z".repeat(64), "10")))
                        .latestRecorded("bedroom", "v-1"),
                "a sequence stored as text is not a readable sequence");
    }

    @Test
    @DisplayName("a statement row that cannot be read as a document makes 'newest' undeterminable too")
    void anUnreadableStatementMakesTheNewestUndeterminable() {
        // The sequence half was fixed first and this half was left skipping (Codex, second
        // review). Same view, same shape, same wrong answer: the view emits rows whose
        // statement is truthy, so a statement that is not a document is one this version
        // cannot read — not one that is absent.
        Map<String, Object> unreadable = new LinkedHashMap<>();
        unreadable.put("statement", "not a document");
        unreadable.put("entrySequence", 90L);

        assertNull(journalOver(clientReturning(row("a".repeat(64), 10L), unreadable))
                        .latestRecorded("bedroom", "v-1"),
                "the row at sequence 90 could not be read and the row at 10 was reported as the "
                        + "newest; a transition would copy a digest that is two writes old");
    }

    @Test
    @DisplayName("recordedAt answers the cited sequence, not the first row it meets")
    void recordedAtIsTheCitedSequenceNotTheFirstRow() {
        CouchContentWriteJournal journal = journalOver(clientReturning(
                row("c".repeat(64), 90L), row("a".repeat(64), 10L), row("b".repeat(64), 50L)));

        ContentWriteJournal.Recorded at10 = journal.recordedAt("bedroom", "v-1", 10L);
        assertEquals(10L, at10.entrySequence());
        assertEquals("a".repeat(64), at10.statement().get("contentDigest"),
                "the transition cites entry 10 and the package would carry a different "
                        + "statement as its prior");
        assertNull(journal.recordedAt("bedroom", "v-1", 77L),
                "a sequence no row was closed with answered a statement");
    }

    @Test
    @DisplayName("abandoning closes the row with a reason and no statement")
    void abandoningClosesWithoutAStatement() {
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("intentId", "i-1");
        open.put("versionObjectId", "v-1");
        open.put("closedAt", null);
        Document existing = mock(Document.class);
        when(existing.getProperties()).thenReturn(open);
        when(existing.getRev()).thenReturn("1-abc");
        DocumentResult ok = mock(DocumentResult.class);
        when(ok.isOk()).thenReturn(true);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(anyString())).thenReturn(existing);
        ArgumentCaptor<Map<String, Object>> written = ArgumentCaptor.forClass(Map.class);
        when(client.update(written.capture())).thenReturn(ok);

        assertTrue(journalOver(client).abandon("i-1", "the cold write was undone"));

        Map<String, Object> row = written.getValue();
        assertTrue(row.get("closedAt") != null, "the row is still open — it stays in the open view");
        assertEquals("the cold write was undone", row.get("abandonedReason"));
        assertFalse(row.containsKey("statement") && row.get("statement") != null,
                "abandoning wrote a statement, so the statements view would list it");
    }

    @Test
    @DisplayName("a row already closed with a statement cannot be abandoned")
    void aClosedRowIsNotUnsaid() {
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("closedAt", "2026-09-22T00:00:00Z");
        closed.put("statement", Map.of("contentDigest", "a".repeat(64)));
        Document existing = mock(Document.class);
        when(existing.getProperties()).thenReturn(closed);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(anyString())).thenReturn(existing);

        assertFalse(journalOver(client).abandon("i-1", "too late"),
                "a row closed with a statement was reported abandoned; the statement stands");
    }

    @Test
    @DisplayName("a journal that cannot read the row does not report it abandoned")
    void anUnreadableRowIsNotAbandoned() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(anyString())).thenThrow(new RuntimeException("connection reset"));

        assertFalse(journalOver(client).abandon("i-1", "undone"),
                "'could not ask' was reported as 'closed', which is the confusion the journal "
                        + "exists to prevent");
        assertNull(journalOver(client).latestRecorded("bedroom", "v-1"));
    }

    /** Fixture check: the mocked update is what the real write would do. */
    @Test
    @DisplayName("fixture: a refused update leaves the row open")
    void aRefusedUpdateLeavesTheRowOpen() {
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("closedAt", null);
        Document existing = mock(Document.class);
        when(existing.getProperties()).thenReturn(open);
        when(existing.getRev()).thenReturn("1-abc");
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(anyString())).thenReturn(existing);
        when(client.update(any())).thenThrow(new RuntimeException("409"));

        assertFalse(journalOver(client).abandon("i-1", "undone"));
    }
}
