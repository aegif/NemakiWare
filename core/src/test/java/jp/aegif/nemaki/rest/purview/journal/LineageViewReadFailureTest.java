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
package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A view that could not be read must not answer with an empty list.
 *
 * <p>The capture boundary's entire visible surface is a listing of unresolved ingests. An empty
 * listing reads as "every ingest completed" — so a read failure rendered as an empty list is a
 * failure that presents itself as reassurance, at exactly the moment an operator is looking
 * (external review).
 *
 * <p>This drives {@code CouchLineageJournalStore.queryRawView} itself. The capture store's own
 * tests use a fake support, so they would keep passing if this behaviour were reverted — a test
 * that checks the double rather than the thing.
 */
class LineageViewReadFailureTest {

    private static CouchLineageJournalStore storeWith(CloudantClientPool pool) {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        try {
            java.lang.reflect.Field f =
                    CouchLineageJournalStore.class.getDeclaredField("connectorPool");
            f.setAccessible(true);
            f.set(store, pool);
        } catch (Exception e) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken(
                    "the store fixture could not be wired, so nothing was checked", e);
        }
        return store;
    }

    @Test
    @DisplayName("a view query that cannot reach the database throws rather than returning empty")
    void unreadableViewThrows() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThrows(RuntimeException.class,
                () -> storeWith(pool).queryRawView("lineage_capture", "unresolved_by_opened_at",
                        Map.of()));
    }

    @Test
    @DisplayName("a view that answers nothing at all is not read as an empty result")
    void nullAnswerIsNotEmpty() {
        // This is the case that matters most and the one a pool-throws test cannot reach. The
        // client wrapper collapses NotFoundException — what CouchDB returns for a MISSING DESIGN
        // DOCUMENT — into a null ViewResult. Reading that as an empty list rendered "the views
        // were never deployed" as "nothing is unresolved" (external review).
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(null);
        setField(store, "lineageClient", client);

        assertThrows(RuntimeException.class,
                () -> store.queryRawView("lineage_capture", "unresolved_by_opened_at", Map.of()));
    }

    @Test
    @DisplayName("a result that carries NO rows is not read as an empty result either")
    void aResultWithNullRowsIsNotEmpty() {
        // The test above covers a null ViewResult, and the one below covers a view that answered
        // with an empty row list. Between them sat a third answer nothing drove: a non-null
        // result whose getRows() is null. The code refused the first in five lines of comment
        // and then returned List.of() for this one, three lines later — and both existing tests
        // stayed green over it.
        //
        // An answered view with nothing in it carries an EMPTY list. A null one has not told us
        // there is nothing; it has not told us anything.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult answered =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(answered.getRows()).thenReturn(null);
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(answered);
        setField(store, "lineageClient", client);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> store.queryRawView("lineage_capture", "unresolved_by_opened_at", Map.of()));
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(thrown.getMessage()).contains("NOT a finding"),
                "the refusal does not say what it is not: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a document-fetching view read asks for reduce=false")
    void documentReadsTurnTheReduceOff() {
        // Found by reading the running deployment's log, not by a test: three capture views
        // gained a _count reduce, and CouchDB refuses `include_docs` on a reduce query
        // ("query_parse_error: `include_docs` is invalid for reduce"). Every document-fetching
        // read of those views had been failing every five minutes — the sweeper never swept,
        // the unresolved listing never listed, and the evidence report's custody section
        // reported unavailable.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(result);
        setField(store, "lineageClient", client);

        store.queryRawView("lineage_capture", "open_by_opened_at", Map.of("limit", 10));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> params =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(client)
                .queryView(anyString(), anyString(), params.capture());
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                params.getValue().get("reduce"),
                "the query did not ask for reduce=false; CouchDB rejects include_docs on a "
                        + "reduce view, so any view with a _count reduce becomes unreadable");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,
                params.getValue().get("include_docs"),
                "the query stopped asking for documents, which is all this method is for");
        org.junit.jupiter.api.Assertions.assertEquals(10, params.getValue().get("limit"),
                "the caller's own parameters were dropped");
    }

    @Test
    @DisplayName("a view that answers with no rows IS an empty result — the control")
    void emptyRowsIsEmpty() {
        // Without this, throwing unconditionally would pass the test above while making an
        // ordinary empty listing impossible.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(result);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertTrue(
                store.queryRawView("lineage_capture", "unresolved_by_opened_at", Map.of())
                        .isEmpty());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f =
                    CouchLineageJournalStore.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken(
                    "the fixture could not be driven, so nothing was checked", e);
        }
    }

    // ── Deleting ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a retention delete is conditional on the revision it read")
    void deleteIsConditional() {
        // The capture store's own tests mock LineageStoreSupport, so they cannot see which client
        // call this makes. The unconditional delete refetches the latest revision and deletes
        // THAT — so a row that changed between a retention scan's read and its delete would be
        // destroyed in its new state (external review).
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(client.deleteIfRevisionMatches("lineage_capture:i-1", "2-a")).thenReturn(true);
        setField(store, "lineageClient", client);

        java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("_id", "lineage_capture:i-1");
        raw.put("_rev", "2-a");

        org.junit.jupiter.api.Assertions.assertTrue(store.deleteRaw(raw));
        org.mockito.Mockito.verify(client).deleteIfRevisionMatches("lineage_capture:i-1", "2-a");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a revision that has moved on is reported as not deleted")
    void staleRevisionIsNotDeleted() {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(client.deleteIfRevisionMatches(anyString(), anyString())).thenReturn(false);
        setField(store, "lineageClient", client);

        java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("_id", "lineage_capture:i-1");
        raw.put("_rev", "2-a");

        org.junit.jupiter.api.Assertions.assertFalse(store.deleteRaw(raw));
    }

    @Test
    @DisplayName("the failure names the view, so an operator knows which index to look at")
    void failureNamesTheView() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> storeWith(pool).queryRawView("lineage_capture", "unresolved_by_opened_at",
                        Map.of()));

        String message = String.valueOf(thrown.getMessage())
                + String.valueOf(thrown.getCause() == null ? "" : thrown.getCause().getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                message.contains("unresolved_by_opened_at") || message.contains("lineage_capture")
                        || message.contains("provisioning"),
                "the message should point at what could not be read. Got: " + message);
    }

    @Test
    @DisplayName("the COUNTING reads refuse the same answers queryRawView refuses")
    void theCountingReadsRefuseAnUnansweredView() {
        // queryRawView was corrected for "a result with no rows is not an empty result". Its
        // two neighbours in the same file were not: countRawView returned 0 and reduceCount
        // returned an EXACT 0L for the same conditions. A count is the shape most readily
        // believed, and reduceCount's answer is the one number here that is NOT a lower bound —
        // its caller reports it as exact.
        //
        // reduceCount already HAS a value for "cannot answer": null, which sends the caller to
        // the bounded scan. Two of its three arms did not use it.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult answered =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(answered.getRows()).thenReturn(null);
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(answered);
        setField(store, "lineageClient", client);

        assertThrows(RuntimeException.class,
                () -> store.countRawView("lineage_capture", "unresolved_by_opened_at", 100),
                "countRawView reported an unanswered view as a count of 0");
        org.junit.jupiter.api.Assertions.assertNull(
                store.reduceCount("lineage_capture", "unresolved_by_opened_at"),
                "reduceCount reported an unanswered view as an EXACT count of 0, when null "
                        + "already means 'cannot answer' and sends the caller to a scan");
    }

    @Test
    @DisplayName("an answered, empty view still counts 0 for both — the control")
    void anAnsweredEmptyViewStillCountsZero() {
        // Without this, refusing every answer would satisfy the test above and a repository
        // with genuinely nothing unresolved could never report so.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(empty);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertEquals(0,
                store.countRawView("lineage_capture", "unresolved_by_opened_at", 100),
                "an answered, empty view was reported as unreadable");
        org.junit.jupiter.api.Assertions.assertEquals(0L,
                store.reduceCount("lineage_capture", "unresolved_by_opened_at"),
                "an empty reduce is a legitimate zero and was reported as 'cannot answer'");
    }

    @Test
    @DisplayName("a journal that could not be REACHED is not a journal with nothing in it")
    void anUnreachableJournalIsNotAnEmptyOne() {
        // ensureClientForRead returned a plain boolean, and false covered both "no journal
        // database has ever been created" and "it could not be reached". Eighteen read methods
        // turn that false into an empty list, a zero or a null — so one transient outage made
        // all eighteen answer "there are no events", "no repository has anything pending",
        // "every processType is at 0". One catch, eighteen confident negatives.
        //
        // isActive() deliberately still answers false for both: the projection loop and the
        // purge scheduler poll it, and an exception out of a scheduleAtFixedRate task cancels
        // every later run. Skipping a tick is the right answer to "cannot reach it"; a READ
        // saying "there is nothing" is not.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(anyString())).thenThrow(new RuntimeException("connection refused"));
        setField(store, "connectorPool", pool);

        assertThrows(RuntimeException.class, () -> store.findAll(10, 0),
                "an unreachable journal answered as though it held no events");
        org.junit.jupiter.api.Assertions.assertFalse(store.isActive(),
                "isActive threw instead of answering false — the schedulers that poll it would "
                        + "be cancelled by the first transient outage");
    }

    @Test
    @DisplayName("an idempotency check that could not run does not answer 'not stored'")
    void anUncheckableEventKeyIsNotAnAbsentOne() {
        // append uses eventKeyExists as its idempotency check, so a false it cannot stand
        // behind writes a SECOND row for one event into an append-only journal. It answered
        // false for a read that failed and for a view that returned nothing.
        //
        // Refusing is affordable here and it is not in CustodyLedgerRecorder: the emitter is
        // fail-open with a dead-letter SINK, so an exception means the event is written to a
        // file for later rather than lost. A dead-letter row can be replayed; a duplicate in an
        // append-only journal cannot be removed.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult noRows =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(noRows.getRows()).thenReturn(null);
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(noRows);
        setField(store, "lineageClient", client);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> invokeEventKeyExists(store, "evt-1"),
                "a check that could not run answered 'this event is not stored', and append "
                        + "writes a second row on the strength of that");
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(thrown.getMessage()).contains("NOT a finding"),
                "the refusal does not say what it is not: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an event that really is not stored still answers false — the control")
    void anAbsentEventKeyStillAnswersFalse() throws Exception {
        // Without this, refusing every check would satisfy the test above and no event could
        // ever be appended.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(empty);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                invokeEventKeyExists(store, "evt-1"),
                "an event that genuinely is not stored was reported as uncheckable");
    }

    private static Object invokeEventKeyExists(CouchLineageJournalStore store, String key) {
        try {
            java.lang.reflect.Method m = CouchLineageJournalStore.class
                    .getDeclaredMethod("eventKeyExists", String.class);
            m.setAccessible(true);
            return m.invoke(store, key);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken(
                    "eventKeyExists is not there, so nothing was checked", e);
        }
    }

    @Test
    @DisplayName("the journal LISTINGS refuse an unread view instead of reporting no events")
    void theListingsRefuseAnUnreadView() {
        // queryRowsFromView was the last read in this class to answer "there is nothing" for
        // three different questions: a view that did not answer, a row whose document did not
        // come back, and any exception at all — the last behind an ERROR log and an empty list,
        // so the log said one thing and the return value said another. Only the return value
        // reaches a caller.
        //
        // Every path through it is driven: findAll (range overload), the projector's
        // by_target_status listing, and the stale-claim reclaim all go through this one helper,
        // and each of them reads an empty list as a fact about the journal.
        for (Object answer : new Object[] { null, viewWithNoRows(), "throw" }) {
            CouchLineageJournalStore store = new CouchLineageJournalStore();
            jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                    mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
            if ("throw".equals(answer)) {
                when(client.queryView(anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyMap()))
                        .thenThrow(new RuntimeException("connection reset"));
            } else {
                when(client.queryView(anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyMap()))
                        .thenReturn((com.ibm.cloud.cloudant.v1.model.ViewResult) answer);
            }
            setField(store, "lineageClient", client);

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> store.findAll(10, 0),
                    "an unread view was reported as a journal with no events");
            org.junit.jupiter.api.Assertions.assertTrue(
                    String.valueOf(thrown.getMessage()).contains("NOT a finding"),
                    "the refusal does not say what it is not: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a journal that really holds nothing still lists empty — the control")
    void anEmptyJournalStillListsEmpty() {
        // Without this, refusing every listing would satisfy the test above and a brand-new
        // deployment could never read its own journal.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(empty);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertTrue(store.findAll(10, 0).isEmpty(),
                "a journal that genuinely holds no events was refused");
        org.junit.jupiter.api.Assertions.assertEquals(0, store.lastUnreadableRowCount());
    }

    @Test
    @DisplayName("a loss in the v1 arm survives a clean v2 arm")
    void aLossInOneArmSurvivesTheOther() {
        // The listings read TWO views and merge them. The counter was reset per READ, so a
        // docless row in the v1 arm was erased by the clean v2 read that followed and the
        // endpoint reported nothing lost.
        //
        // The first version of this test stubbed ONE unconditional answer for both views, so
        // the second arm always restored the expected number and losing the first arm stayed
        // green — the fixture hid the fan-out it was meant to cover. The arms are distinct
        // here: v1 loses a row, v2 is clean, and the answer must still be 1.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult docless =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        com.ibm.cloud.cloudant.v1.model.ViewResultRow doclessRow =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(doclessRow.getDoc()).thenReturn(null);
        when(docless.getRows()).thenReturn(java.util.List.of(doclessRow));
        com.ibm.cloud.cloudant.v1.model.ViewResult clean =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(clean.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), org.mockito.ArgumentMatchers.eq("by_occurred_at"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(docless);
        when(client.queryView(anyString(), org.mockito.ArgumentMatchers.eq("v2_by_occurred_at"),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(clean);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertTrue(store.findAll(10, 0).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, store.lastUnreadableRowCount(),
                "the v1 arm's loss was erased by the clean v2 read that followed it");
    }

    @Test
    @DisplayName("losses in BOTH arms are added, not overwritten")
    void lossesInBothArmsAreAdded() {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult docless =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        com.ibm.cloud.cloudant.v1.model.ViewResultRow doclessRow =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(doclessRow.getDoc()).thenReturn(null);
        when(docless.getRows()).thenReturn(java.util.List.of(doclessRow));
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(docless);
        setField(store, "lineageClient", client);

        store.findAll(10, 0);
        org.junit.jupiter.api.Assertions.assertEquals(2, store.lastUnreadableRowCount(),
                "two arms each lost a row and the total reported one");
    }

    @Test
    @DisplayName("a fresh listing does not inherit the previous one's losses — the control")
    void aFreshListingStartsFromZero() {
        // The accrual is a ThreadLocal on a singleton and tests share a thread with real
        // requests in a container. Without a reset at the entry point, one lossy listing would
        // make every later listing on that thread claim losses it did not have.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult docless =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        com.ibm.cloud.cloudant.v1.model.ViewResultRow doclessRow =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(doclessRow.getDoc()).thenReturn(null);
        when(docless.getRows()).thenReturn(java.util.List.of(doclessRow));
        com.ibm.cloud.cloudant.v1.model.ViewResult clean =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(clean.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(docless);
        setField(store, "lineageClient", client);
        store.findAll(10, 0);

        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(clean);
        store.findAll(10, 0);

        org.junit.jupiter.api.Assertions.assertEquals(0, store.lastUnreadableRowCount(),
                "a clean listing inherited the previous listing's losses");
    }

    private static com.ibm.cloud.cloudant.v1.model.ViewResult viewWithNoRows() {
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(null);
        return result;
    }

    @Test
    @DisplayName("the COUNTING reads and the record read refuse rather than fabricate")
    void theRemainingReadsRefuseRatherThanFabricate() {
        // Three more in the same class, found by review AFTER the first pass through it:
        // queryTargetStatusCount collapsed FOUR shapes into 0 (its number is the projector's
        // backlog ceiling — a fabricated 0 means the ceiling never fires and the journal grows
        // without bound); countByProcessType returned an empty map, which /stats renders as
        // "totalEvents: 0"; findByRecordId returned null, which the endpoint renders as 404
        // "Event not found" — a statement about the journal from a failure on this node.
        //
        // Its sibling countNonTerminalByTarget already refused when the journal was
        // UNREACHABLE, so ONE method answered two different ways about the same failure.
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult noRows =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(noRows.getRows()).thenReturn(null);
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(noRows);
        when(client.get(org.mockito.ArgumentMatchers.<Class<Object>>any(), anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("connection reset"));
        setField(store, "lineageClient", client);

        assertThrows(RuntimeException.class, () -> store.countByProcessType(),
                "a view that did not answer was rendered as 'this repository has journalled "
                        + "nothing'");
        assertThrows(RuntimeException.class, () -> store.findByRecordId("evt-1"),
                "a failed document read was rendered as 'Event not found'");
        assertThrows(RuntimeException.class,
                () -> invokeTargetStatusCount(store, "purview", "PENDING"),
                "a backlog nobody could count was rendered as 0, which is the value that stops "
                        + "the projector's ceiling from ever firing");
    }

    @Test
    @DisplayName("genuinely empty answers are still empty for all three — the control")
    void genuinelyEmptyAnswersAreStillEmpty() {
        CouchLineageJournalStore store = new CouchLineageJournalStore();
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(empty.getRows()).thenReturn(java.util.List.of());
        when(client.queryView(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(empty);
        when(client.get(org.mockito.ArgumentMatchers.<Class<Object>>any(), anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        setField(store, "lineageClient", client);

        org.junit.jupiter.api.Assertions.assertTrue(store.countByProcessType().isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(store.findByRecordId("evt-1"),
                "an event that genuinely is not stored was refused");
        org.junit.jupiter.api.Assertions.assertEquals(0L,
                invokeTargetStatusCount(store, "purview", "PENDING"),
                "a target with genuinely nothing pending was refused");
    }

    private static long invokeTargetStatusCount(CouchLineageJournalStore store, String target,
            String status) {
        try {
            java.lang.reflect.Method m = CouchLineageJournalStore.class.getDeclaredMethod(
                    "queryTargetStatusCount", String.class, String.class);
            m.setAccessible(true);
            return (long) m.invoke(store, target, status);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new jp.aegif.nemaki.util.test.HarnessBroken(
                    "queryTargetStatusCount is gone, so nothing was checked", e);
        }
    }
}
