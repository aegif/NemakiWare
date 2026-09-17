package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;
import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;

/**
 * The ingest store's reads through {@code IngestJobService.findRawDocs}, where three answers
 * from the SDK used to collapse into one.
 *
 * <ul>
 *   <li>A {@code _find} response without a document list is the store NOT answering.
 *       {@code NemakiConfFind} defines it that way and refuses; this service turned it into
 *       "no rows" — an empty listing, a 404 on retry, a purge that deleted nothing and said
 *       success.</li>
 *   <li>The SDK raises a {@code _rev} race as {@code ConflictException}; it never answers
 *       {@code ok=false}. The purge caught every RuntimeException as "the row moved", so a
 *       timeout on one delete was counted as a moved row and the purge answered success; the
 *       retry reservation caught the conflict in its could-not-ask arm and answered 503 for a
 *       settled "someone else holds it".</li>
 * </ul>
 */
class IngestStoreAnswersAreNotAbsenceTest {

    private static IngestJobService serviceOn(Cloudant cloudant) {
        IngestJobService jobs = new IngestJobService();
        CloudantClientWrapper wrapper = mock(CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);
        return jobs;
    }

    @SuppressWarnings("unchecked")
    private static ServiceCall<FindResult> findAnswering(List<Document> docs) {
        FindResult found = mock(FindResult.class);
        when(found.getDocs()).thenReturn(docs);
        ServiceCall<FindResult> call = mock(ServiceCall.class);
        Response<FindResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(found);
        when(call.execute()).thenReturn(resp);
        return call;
    }

    /** A 503 as the SDK would raise it — see {@link CouchConflicts} for why not a mock. */
    private static ServiceResponseException serverError() {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://couchdb:5984/nemaki_conf/doc").build();
        okhttp3.Response response = new okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body(okhttp3.ResponseBody.create("{\"error\":\"unavailable\"}",
                        okhttp3.MediaType.parse("application/json")))
                .build();
        return new ServiceResponseException(503, response);
    }

    /** A 404 as the SDK would raise it — any 404, a missing database included. */
    private static com.ibm.cloud.sdk.core.service.exception.NotFoundException notFound() {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://couchdb:5984/nemaki_conf/doc").build();
        okhttp3.Response response = new okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body(okhttp3.ResponseBody.create("{\"error\":\"not_found\"}",
                        okhttp3.MediaType.parse("application/json")))
                .build();
        return new com.ibm.cloud.sdk.core.service.exception.NotFoundException(response);
    }

    /** A write the store answered without throwing, affirmatively or not. */
    @SuppressWarnings("unchecked")
    private static ServiceCall<DocumentResult> writeAnswering(boolean ok) {
        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(ok);
        when(result.getId()).thenReturn("ingest_dlq:d1");
        ServiceCall<DocumentResult> call = mock(ServiceCall.class);
        Response<DocumentResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(result);
        when(call.execute()).thenReturn(resp);
        return call;
    }

    private static Document oldEntry() {
        Document doc = new Document();
        doc.setId("ingest_dlq:old");
        doc.setRev("3-abc");
        doc.put("type", IngestDeadLetterRecord.DOC_TYPE);
        doc.put("dlqId", "old");
        doc.put("failedAt", "2020-01-01T00:00:00Z");
        return doc;
    }

    @Test
    @DisplayName("a _find without a document list is not an empty listing")
    void aFindWithoutADocumentListIsNotAnEmptyListing() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(null);
        when(cloudant.postFind(any())).thenReturn(find);
        IngestJobService jobs = serviceOn(cloudant);

        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.listDlqPage(10, 0, true),
                "a response with no document list was listed as an empty queue");
        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.listJobsPage(10),
                "a response with no document list was listed as no jobs");
    }

    @Test
    @DisplayName("an empty document list is still an empty listing")
    void anEmptyDocumentListIsStillAnEmptyListing() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        IngestJobService jobs = serviceOn(cloudant);

        IngestJobService.DlqPage page = jobs.listDlqPage(10, 0, true);

        assertEquals(0, page.entries().size());
        assertFalse(page.hasMore(), "an answered empty page claimed there was more");
    }

    @Test
    @DisplayName("a purge delete that failed is not a row that moved")
    @SuppressWarnings("unchecked")
    void aPurgeDeleteThatFailedIsNotAMovedRow() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = mock(ServiceCall.class);
        when(delete.execute()).thenThrow(serverError());
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        IngestJobService.DlqPurgeIncompleteException stopped = assertThrows(
                IngestJobService.DlqPurgeIncompleteException.class,
                () -> jobs.purgeDlqOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z")),
                "a delete the store refused was counted as a row that had moved, and the"
                        + " purge answered success");
        assertEquals(0, stopped.getDeletedBeforeStopping());
    }

    @Test
    @DisplayName("a purge conflict is a row that moved, not a failed purge")
    @SuppressWarnings("unchecked")
    void aPurgeConflictIsAMovedRowNotAFailure() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = mock(ServiceCall.class);
        when(delete.execute()).thenThrow(CouchConflicts.conflict());
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        int deleted = jobs.purgeDlqOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z"));

        assertEquals(0, deleted, "a row that changed since the walk was counted as purged");
    }

    @Test
    @DisplayName("losing the reservation race is an answer, not could-not-ask")
    @SuppressWarnings("unchecked")
    void aLostReservationRaceIsNotCouldNotAsk() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = mock(ServiceCall.class);
        when(post.execute()).thenThrow(CouchConflicts.conflict());
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId("d1");
        dlq.setStoredId("ingest_dlq:d1");
        dlq.setStoredRevision("1-a");

        boolean reserved = assertDoesNotThrow(() -> jobs.reserveDlqRetry(dlq),
                "the loser of a _rev race was told the reservation could not be attempted");
        assertFalse(reserved, "the loser of a _rev race was told it had won");
    }

    @Test
    @DisplayName("the DLQ controller answers 503 when the store did not answer")
    void theDlqControllerAnswers503WhenTheStoreDidNotAnswer() throws Exception {
        java.lang.reflect.Method handler = null;
        for (java.lang.reflect.Method m : IngestDlqController.class.getMethods()) {
            org.springframework.web.bind.annotation.ExceptionHandler eh =
                    m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (eh != null && List.of(eh.value())
                    .contains(IngestJobService.IngestStoreDidNotAnswerException.class)) {
                handler = m;
            }
        }
        assertTrue(handler != null,
                "no @ExceptionHandler on IngestDlqController maps the store not answering —"
                        + " Spring answers 500 for it");
        Object answer = handler.invoke(new IngestDlqController(),
                new IngestJobService.IngestStoreDidNotAnswerException("no document list"));
        assertEquals(503, ((org.springframework.http.ResponseEntity<?>) answer)
                .getStatusCode().value());
    }
    @Test
    @DisplayName("a reservation the store did not accept is not 'a rival holds it'")
    void aReservationTheStoreDidNotAcceptIsNotARival() {
        // ok=false without an exception. The arm answered false, which the controller reports
        // as 429 "concurrent request" — a fact about a rival nothing established.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = writeAnswering(false);
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId("d1");
        dlq.setStoredId("ingest_dlq:d1");
        dlq.setStoredRevision("1-a");

        assertThrows(IngestJobService.DlqRetryNotReservableException.class,
                () -> jobs.reserveDlqRetry(dlq),
                "a write the store did not accept was reported as losing to a rival");
    }

    @Test
    @DisplayName("a purge delete the store did not confirm is not counted")
    void aPurgeDeleteThatWasNotConfirmedIsNotCounted() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = writeAnswering(false);
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        IngestJobService.DlqPurgeIncompleteException stopped = assertThrows(
                IngestJobService.DlqPurgeIncompleteException.class,
                () -> jobs.purgeDlqOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z")),
                "a delete the store answered ok=false was counted as deleted");
        assertEquals(0, stopped.getDeletedBeforeStopping());
    }

    @Test
    @DisplayName("a purge delete that answered 404 is not 'already gone'")
    void aPurgeDeleteThatAnswered404IsNotCounted() {
        // The SDK's NotFoundException is any 404 — a missing database included — so it is
        // not read as a settled "someone deleted it first"; the purge refuses like it does
        // for every other non-conflict failure, and a re-run settles it.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        @SuppressWarnings("unchecked")
        ServiceCall<DocumentResult> delete = mock(ServiceCall.class);
        when(delete.execute()).thenThrow(notFound());
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        assertThrows(IngestJobService.DlqPurgeIncompleteException.class,
                () -> jobs.purgeDlqOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z")),
                "a 404 on the delete was read as 'the row was already gone' and the purge"
                        + " answered success");
    }

    @Test
    @DisplayName("a confirmed purge delete is counted")
    void aConfirmedPurgeDeleteIsCounted() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = writeAnswering(true);
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        assertEquals(1, jobs.purgeDlqOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z")),
                "a delete the store confirmed was not counted");
    }
    @Test
    @DisplayName("a single delete the store did not confirm is not counted")
    void aSingleDeleteTheStoreDidNotConfirmIsNotCounted() {
        // The endpoint answered success, and the retry door "resolved", on execute() returning.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = writeAnswering(false);
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        assertEquals(new IngestJobService.DlqDeletion(0, 1), jobs.deleteDlqEntry("old"),
                "a delete the store answered ok=false was counted as deleted");
    }

    @Test
    @DisplayName("a confirmed single delete is counted")
    void aConfirmedSingleDeleteIsCounted() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = writeAnswering(true);
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        assertEquals(new IngestJobService.DlqDeletion(1, 0), jobs.deleteDlqEntry("old"),
                "a confirmed delete was not counted");
    }

    @Test
    @DisplayName("a twin-row delete reports the half the store did not confirm")
    void aTwinRowDeleteReportsTheUnconfirmedHalf() {
        Cloudant cloudant = mock(Cloudant.class);
        Document twin = oldEntry();
        twin.setId("ingest_dlq:old-2");
        twin.setRev("1-b");
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry(), twin));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> first = writeAnswering(true);
        ServiceCall<DocumentResult> second = writeAnswering(false);
        when(cloudant.deleteDocument(any())).thenReturn(first, second);
        IngestJobService jobs = serviceOn(cloudant);

        assertEquals(new IngestJobService.DlqDeletion(1, 1), jobs.deleteDlqEntry("old"),
                "the unconfirmed twin was folded into a confirmed count");
    }

    private static ExternalIngestRequest itemRequest() {
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-1");
        return request;
    }

    @Test
    @DisplayName("a delete the store did not answer is the typed refusal, not a raw failure")
    @SuppressWarnings("unchecked")
    void aDeleteTheStoreDidNotAnswerIsATypedRefusal() {
        // Raw, it reached the DELETE endpoint as a Spring 500 (R27).
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> delete = mock(ServiceCall.class);
        when(delete.execute()).thenThrow(new RuntimeException("connection reset"));
        when(cloudant.deleteDocument(any())).thenReturn(delete);
        IngestJobService jobs = serviceOn(cloudant);

        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.deleteDlqEntry("old"),
                "a store that did not answer the delete escaped as a raw failure");
    }

    @Test
    @DisplayName("a webhook delivery record is written with its own mark")
    void aWebhookDeliveryRecordSaveWritesTheMark() {
        // The mark used to be a prefix on sourceObjectId — the caller's string (R10).
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = writeAnswering(true);
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);

        assertTrue(jobs.saveWebhookDeliveryRecordToDlq(itemRequest(),
                "[transient] webhook deliveries were accepted but not fetched"));

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals(Boolean.TRUE, written.getValue().document().get("webhookDeliveryRecord"),
                "the record row was written without its mark");
    }

    @Test
    @DisplayName("a query the store did not answer is the typed refusal, not a raw failure")
    @SuppressWarnings("unchecked")
    void aQueryTheStoreDidNotAnswerIsATypedRefusal() {
        // Raw, it reached the DELETE endpoint as a Spring 500 and the retry door's cleanup as
        // 500 "Retry failed" (R40).
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = mock(ServiceCall.class);
        when(find.execute()).thenThrow(new RuntimeException("connection reset"));
        when(cloudant.postFind(any())).thenReturn(find);
        IngestJobService jobs = serviceOn(cloudant);

        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.listDlqPage(10, 0, true),
                "a selector the store did not answer escaped as a raw failure");
        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.deleteDlqEntry("old"),
                "a delete whose selector the store did not answer escaped as a raw failure");
    }

    @Test
    @DisplayName("an unwired ingest store is the typed refusal, not an IllegalStateException")
    void anUnwiredStoreIsATypedRefusal() {
        IngestJobService jobs = new IngestJobService();
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(null);
        jobs.setConnectorPool(pool);

        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.listDlqPage(10, 0, true),
                "an unwired node answered with an untyped failure");
    }

    @Test
    @DisplayName("a new dead-letter row is written under a deterministic id")
    void aNewDlqRowGetsADeterministicId() {
        // The selector answering nothing is also what a rebuilding index answers for a row
        // that is there; a generated id then wrote a twin. A deterministic id makes that
        // second write a conflict instead (R23 — no compare-and-swap, that is R1).
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = writeAnswering(true);
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-1");

        jobs.saveToDlq(request, "boom", null);

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        Document doc = written.getValue().document();
        assertEquals("ingest_dlq:" + doc.get("dlqId"), doc.getId(),
                "a row the selector did not show was written under a generated id — a twin, not a conflict");
    }

    @Test
    @DisplayName("a job row keeps its generated id — the control; jobs are outside R23")
    void aJobRowKeepsAGeneratedId() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of());
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = writeAnswering(true);
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);

        jobs.createJob("p1", "c1", "bedroom");

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals(null, written.getValue().document().getId(),
                "a job row was given a deterministic id — R23 is about dead-letter rows only");
    }

    // ── R13: an offset without an order is a boundary over an unspecified sequence ──

    /** A 400 as the SDK raises it when the sort has no index — the body CouchDB 3.3.3 sends. */
    private static ServiceResponseException noUsableIndex() {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://couchdb:5984/nemaki_conf/_find").build();
        okhttp3.Response response = new okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(400)
                .message("Bad Request")
                .body(okhttp3.ResponseBody.create(
                        "{\"error\":\"no_usable_index\",\"reason\":\"No index exists for this"
                                + " sort, try indexing by the sort fields.\"}",
                        okhttp3.MediaType.parse("application/json")))
                .build();
        return new ServiceResponseException(400, response);
    }

    @Test
    @DisplayName("the dead-letter page is ordered by a key that never changes for a row")
    void theDeadLetterPageIsOrderedByAKeyThatNeverChanges() {
        Cloudant cloudant = mock(Cloudant.class);
        // Built BEFORE the when(): findAnswering stubs mocks of its own, and calling it inside
        // an unfinished when() leaves the outer stubbing open — which fails the whole class.
        ServiceCall<FindResult> find = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenReturn(find);
        IngestJobService jobs = serviceOn(cloudant);

        IngestJobService.DlqPage page = jobs.listDlqPage(10, 20, true);

        ArgumentCaptor<com.ibm.cloud.cloudant.v1.model.PostFindOptions> asked =
                ArgumentCaptor.forClass(com.ibm.cloud.cloudant.v1.model.PostFindOptions.class);
        verify(cloudant).postFind(asked.capture());
        // (type, dlqId), not _id: dlqId is unique per row, never changes, is carried by rows
        // written before the deterministic _id (R23), and idx_type_dlqId already serves it.
        assertEquals(List.of(java.util.Map.of("type", "asc"), java.util.Map.of("dlqId", "asc")),
                asked.getValue().sort(),
                "the page was asked for without an order — this offset can hand back a row an"
                        + " earlier page showed and pass over one no page shows");
        assertEquals(Long.valueOf(20L), asked.getValue().skip(),
                "the offset stopped travelling with the order");
        assertEquals(Long.valueOf(11L), asked.getValue().limit(),
                "the probe row stopped travelling with the order");
        assertTrue(page.stablyOrdered(), "an ordered page did not say it was ordered");
    }

    @Test
    @DisplayName("a page that could not be ordered is not passed off as ordered")
    void aPageThatCouldNotBeOrderedIsNotPassedOffAsOrdered() {
        // The store has no index for the sort. Refusing the whole listing would deny the
        // operator the only record that these items were lost, so the page is still served —
        // but it must not read like an ordered one.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> unsorted = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenAnswer(call -> {
            com.ibm.cloud.cloudant.v1.model.PostFindOptions asked = call.getArgument(0);
            if (asked.sort() != null) throw noUsableIndex();
            return unsorted;
        });
        IngestJobService jobs = serviceOn(cloudant);

        IngestJobService.DlqPage page = assertDoesNotThrow(() -> jobs.listDlqPage(10, 0, true),
                "a store that cannot serve the sort took the listing away entirely");
        assertEquals(1, page.entries().size(), "the fallback lost the page");
        assertFalse(page.stablyOrdered(),
                "a page the store could not order was reported as ordered — the caller then"
                        + " pages with an offset that can repeat and pass over rows");
    }

    @Test
    @DisplayName("a store that did not answer the ordered query is still a refusal")
    void aStoreThatDidNotAnswerTheOrderedQueryIsStillARefusal() {
        // The over-throw guard's mirror: only 400 no_usable_index may become an unordered page.
        // Absorbing anything else would turn a store that did not answer into a page that looks
        // whole — the defect this whole class exists for.
        //
        // Only the SORTED query fails here. Failing both would let the fallback's own refusal
        // satisfy this assertion, so the lock would stay green with the guard deleted — measured:
        // it did, and the control did not fire. The unsorted read must SUCCEED for the failure
        // to belong to the guard alone.
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> unsorted = findAnswering(List.of(oldEntry()));
        when(cloudant.postFind(any())).thenAnswer(call -> {
            com.ibm.cloud.cloudant.v1.model.PostFindOptions asked = call.getArgument(0);
            if (asked.sort() != null) throw serverError();
            return unsorted;
        });
        IngestJobService jobs = serviceOn(cloudant);

        assertThrows(IngestJobService.IngestStoreDidNotAnswerException.class,
                () -> jobs.listDlqPage(10, 0, true),
                "a transport failure on the ordered query became an unordered page");
    }
}
