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
            throw new AssertionError(e);
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
            throw new AssertionError(e);
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
}
