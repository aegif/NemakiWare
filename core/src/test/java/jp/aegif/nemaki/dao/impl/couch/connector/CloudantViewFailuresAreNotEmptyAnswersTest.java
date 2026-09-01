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
package jp.aegif.nemaki.dao.impl.couch.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.PostViewOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

/**
 * The wrapper's count and paged reads throw on failure instead of answering 0 / empty.
 *
 * <h2>The unreachable guards these swallows created</h2>
 *
 * <p>{@code ArchiveDaoDelegate}'s counts and paged listings were made fail-closed a round
 * earlier — "0 is not 'the trash is empty'" — but those throws sat one layer ABOVE these
 * catches, which answered 0 (or an empty page with {@code totalRows} 0) for ANY failure first.
 * The guards never fired for a real CouchDB failure; the admin trash rendered
 * {@code totalItems: 0} as a clean 200. Found by the round-32 sibling sweep: the fix reached
 * the delegate, not the wrapper under it. (The paged catch also wrapped its per-row decode
 * loop, so one undecodable archive used to empty the whole page.)
 *
 * <p>The undeployed-view (NotFound) arms keep a STARTUP-phase grace. It used to be inferred
 * from the thread's name, which is why the typed-get test below runs on a request-named
 * thread; the window is declared now ({@code StartupPhase}), so these tests measure the
 * strict side simply by not opening it.
 */
class CloudantViewFailuresAreNotEmptyAnswersTest {

    @SuppressWarnings("unchecked")
    private CloudantClientWrapper wrapperWhosePostViewFails() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<ViewResult> call = mock(ServiceCall.class);
        when(cloudant.postView(any(PostViewOptions.class))).thenReturn(call);
        when(call.execute()).thenThrow(new RuntimeException("connection reset"));
        return new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("a failed count throws — 0 is not 'there are none'")
    void aFailedCountThrows() {
        CloudantClientWrapper wrapper = wrapperWhosePostViewFails();

        assertThrows(CmisRuntimeException.class,
                () -> wrapper.queryViewCount("_repo", "archivesByArchivedAt"),
                "the count answered 0 for a failure, and the fail-closed guard one layer up "
                        + "never fired");
    }

    @Test
    @DisplayName("a failed keyed count throws the same way — the twin")
    void aFailedKeyedCountThrows() {
        CloudantClientWrapper wrapper = wrapperWhosePostViewFails();

        assertThrows(CmisRuntimeException.class,
                () -> wrapper.queryViewCountByKey("_repo", "searchableArchives", "document"));
    }

    @Test
    @DisplayName("a failed paged read throws — an empty page is not an empty trash")
    void aFailedPagedReadThrows() {
        CloudantClientWrapper wrapper = wrapperWhosePostViewFails();

        assertThrows(CmisRuntimeException.class,
                () -> wrapper.queryViewPaged("_repo", "archivesByArchivedAt",
                        jp.aegif.nemaki.model.couch.CouchArchive.class, 0, 10, false));
    }

    @Test
    @DisplayName("a failed keyed paged read throws — the twin")
    void aFailedKeyedPagedReadThrows() {
        CloudantClientWrapper wrapper = wrapperWhosePostViewFails();

        assertThrows(CmisRuntimeException.class,
                () -> wrapper.queryViewPagedWithKey("_repo", "searchableArchives", "document",
                        jp.aegif.nemaki.model.couch.CouchArchive.class, 0, 10, false));
    }

    @Test
    @DisplayName("a typed get throws on failure — null stays the absence answer")
    void aFailedTypedGetThrows() {
        // The untyped get() was fixed a round earlier; this typed overload kept
        // flattening failures into null — which is how "the RSS token could not be read"
        // reached the admin as "Token not found" and getDocument-family reads served
        // failures as absence.
        Cloudant cloudant = mock(Cloudant.class);
        when(cloudant.getDocument(any(com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.class)))
                .thenThrow(new RuntimeException("connection reset"));
        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());

        // On a thread the startup heuristic classifies as startup (surefire runs on
        // "main"), the untyped fetch grace-nulls and this catch never sees the failure —
        // so the call runs on an explicitly request-named thread, like production traffic.
        Throwable thrown = onRequestThread(
                () -> wrapper.get(jp.aegif.nemaki.model.couch.CouchRssToken.class, "token-1"));
        org.junit.jupiter.api.Assertions.assertInstanceOf(CmisRuntimeException.class, thrown,
                String.valueOf(thrown));
    }

    private static Throwable onRequestThread(Runnable body) {
        final Throwable[] captured = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                captured[0] = e;
            }
        }, "request-worker-1");
        t.start();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return captured[0];
    }

    @Test
    @DisplayName("a paged row without its document refuses the page")
    @SuppressWarnings("unchecked")
    void aDocumentlessPagedRowRefusesThePage() {
        // The paged loops silently skipped rows whose doc (or properties) was missing —
        // one such archive row shortened the trash page with no exception anywhere.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<ViewResult> call = mock(ServiceCall.class);
        Response<ViewResult> response = mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.ViewResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(row.getDoc()).thenReturn(null);
        ViewResult result = mock(ViewResult.class);
        when(result.getTotalRows()).thenReturn(1L);
        when(result.getRows()).thenReturn(java.util.List.of(row));
        when(response.getResult()).thenReturn(result);
        when(call.execute()).thenReturn(response);
        when(cloudant.postView(any(PostViewOptions.class))).thenReturn(call);
        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());

        assertThrows(CmisRuntimeException.class,
                () -> wrapper.queryViewPaged("_repo", "archivesByArchivedAt",
                        jp.aegif.nemaki.model.couch.CouchArchive.class, 0, 10, false));
    }

    @SuppressWarnings("unchecked")
    private static CloudantClientWrapper wrapperWithBulkRows(
            com.ibm.cloud.cloudant.v1.model.DocsResultRow... rows) {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<com.ibm.cloud.cloudant.v1.model.AllDocsResult> call = mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.AllDocsResult> response = mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.AllDocsResult result =
                mock(com.ibm.cloud.cloudant.v1.model.AllDocsResult.class);
        when(result.getRows()).thenReturn(java.util.List.of(rows));
        when(response.getResult()).thenReturn(result);
        when(call.execute()).thenReturn(response);
        when(cloudant.postAllDocs(any(com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.class)))
                .thenReturn(call);
        return new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("a bulk row with neither a document nor an error refuses the batch")
    void anUnexplainedBulkRowRefuses() {
        // The layer UNDER the DAO's fail-closed bulk read: it skipped every row it could
        // not use, so the DAO's throw never fired for the real failure mode and the
        // incremental sync read the missing ids as documents that do not exist.
        com.ibm.cloud.cloudant.v1.model.DocsResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(row.getKey()).thenReturn("doc-1");
        when(row.getDoc()).thenReturn(null);
        when(row.getError()).thenReturn(null);

        // The MESSAGE, not just the type: a later completeness check (added for the
        // requested-id-with-no-row case) throws for this input too, so asserting only
        // "something threw" let a revert of THIS guard stay green — the runner reported it
        // as a control that protects nothing.
        CmisRuntimeException thrown = assertThrows(CmisRuntimeException.class,
                () -> wrapperWithBulkRows(row).getBulkDocuments(java.util.List.of("doc-1")),
                "a row that answered neither way shortened the map silently");
        assertTrue(thrown.getMessage().contains("carries neither a document nor an error"),
                "a different guard answered for this row, so the row-shape refusal itself is "
                        + "unmeasured: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a bulk row whose error is NOT absence refuses the batch")
    void aFailedBulkRowRefuses() {
        com.ibm.cloud.cloudant.v1.model.DocsResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(row.getKey()).thenReturn("doc-1");
        when(row.getError()).thenReturn("internal_server_error");

        CmisRuntimeException thrown = assertThrows(CmisRuntimeException.class,
                () -> wrapperWithBulkRows(row).getBulkDocuments(java.util.List.of("doc-1")));
        assertTrue(thrown.getMessage().contains("internal_server_error"),
                "the row's own error was not what refused the batch: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a failed bulk BATCH refuses instead of continuing with the next one")
    @SuppressWarnings("unchecked")
    void aFailedBulkBatchRefuses() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<com.ibm.cloud.cloudant.v1.model.AllDocsResult> call = mock(ServiceCall.class);
        when(call.execute()).thenThrow(new RuntimeException("connection reset"));
        when(cloudant.postAllDocs(any(com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.class)))
                .thenReturn(call);
        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());

        CmisRuntimeException thrown = assertThrows(CmisRuntimeException.class,
                () -> wrapper.getBulkDocuments(java.util.List.of("doc-1")),
                "'continue with next batch' made a failed batch look like a batch of "
                        + "absent ids");
        assertTrue(thrown.getMessage().contains("bulk read batch"),
                "the batch failure was not what refused: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a requested id with NO row at all refuses — the other way the map is short")
    void aRequestedIdWithNoRowRefuses() {
        // The rows that came back were checked; an id that produced no row at all was still
        // simply missing from the map, and the caller reads a missing entry as absence.
        // CouchDB answers every requested key (with a not_found row when it is gone), so a
        // key with no row means the answer was truncated.
        com.ibm.cloud.cloudant.v1.model.DocsResultRow present =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(present.getId()).thenReturn("doc-1");
        when(present.getDoc()).thenReturn(
                mock(com.ibm.cloud.cloudant.v1.model.Document.class));

        assertThrows(CmisRuntimeException.class,
                () -> wrapperWithBulkRows(present)
                        .getBulkDocuments(java.util.List.of("doc-1", "doc-missing-entirely")),
                "an id the answer never mentioned stayed silently out of the map");
    }

    @Test
    @DisplayName("not_found and deleted rows are still skipped — the absence control")
    void absenceRowsAreStillSkipped() {
        com.ibm.cloud.cloudant.v1.model.DocsResultRow missing =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(missing.getKey()).thenReturn("gone-1");
        when(missing.getError()).thenReturn("not_found");
        com.ibm.cloud.cloudant.v1.model.DocsResultRow present =
                mock(com.ibm.cloud.cloudant.v1.model.DocsResultRow.class);
        when(present.getId()).thenReturn("doc-1");
        when(present.getDoc()).thenReturn(
                mock(com.ibm.cloud.cloudant.v1.model.Document.class));

        java.util.Map<String, com.ibm.cloud.cloudant.v1.model.Document> bulk =
                wrapperWithBulkRows(missing, present)
                        .getBulkDocuments(java.util.List.of("gone-1", "doc-1"));

        assertEquals(1, bulk.size(),
                "the refusal arms broke the ordinary 'this id is not there' answer: " + bulk);
        org.junit.jupiter.api.Assertions.assertTrue(bulk.containsKey("doc-1"));
    }

    @Test
    @DisplayName("an ordinary count still answers — the control")
    @SuppressWarnings("unchecked")
    void anOrdinaryCountStillAnswers() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<ViewResult> call = mock(ServiceCall.class);
        Response<ViewResult> response = mock(Response.class);
        ViewResult result = mock(ViewResult.class);
        when(result.getTotalRows()).thenReturn(7L);
        when(response.getResult()).thenReturn(result);
        when(call.execute()).thenReturn(response);
        when(cloudant.postView(any(PostViewOptions.class))).thenReturn(call);
        CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudant, "bedroom",
                new tools.jackson.databind.ObjectMapper());

        assertEquals(7L, wrapper.queryViewCount("_repo", "archivesByArchivedAt"),
                "the refusal arms broke the ordinary count");
    }
}
