package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts;

/**
 * The dead-letter writes are compare-and-swap: conditioned on the revision the writer READ.
 * {@code upsertDocument} adopted whatever revision was there at write time, so two savers
 * that both read revision 1 both wrote, and the second silently discarded the first's fields
 * — the lost-update class the ledger carried as R1, and the reason the retry door's "429 on a
 * lost race" was never real (both reservations re-found the row and both won).
 */
class DlqWritesAreCompareAndSwapTest {

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

    private static Document row(String id, String rev, int failureCount, String token) {
        Document d = new Document();
        d.setId(id);
        d.setRev(rev);
        d.put("type", IngestDeadLetterRecord.DOC_TYPE);
        d.put("dlqId", "dlq-any");
        d.put("failureCount", failureCount);
        d.put("hasContent", Boolean.FALSE);
        d.put("failedAt", "2026-09-14T00:00:00Z");
        d.put("originalRequestJson", "{}");
        if (token != null) d.put("payloadWriteToken", token);
        return d;
    }

    @SuppressWarnings("unchecked")
    private static ServiceCall<FindResult> findAnswering(List<Document> docs) {
        FindResult found = mock(FindResult.class);
        when(found.getDocs()).thenReturn(docs);
        Response<FindResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(found);
        ServiceCall<FindResult> call = mock(ServiceCall.class);
        when(call.execute()).thenReturn(resp);
        return call;
    }

    @SuppressWarnings("unchecked")
    private static ServiceCall<DocumentResult> writeAnswering(String id, String rev) {
        DocumentResult result = mock(DocumentResult.class);
        when(result.isOk()).thenReturn(Boolean.TRUE);
        when(result.getId()).thenReturn(id);
        when(result.getRev()).thenReturn(rev);
        Response<DocumentResult> resp = mock(Response.class);
        when(resp.getResult()).thenReturn(result);
        ServiceCall<DocumentResult> call = mock(ServiceCall.class);
        when(call.execute()).thenReturn(resp);
        return call;
    }

    @SuppressWarnings("unchecked")
    private static ServiceCall<DocumentResult> writeConflicting() {
        ServiceCall<DocumentResult> call = mock(ServiceCall.class);
        when(call.execute()).thenThrow(CouchConflicts.conflict());
        return call;
    }

    private static ExternalIngestRequest itemRequest() {
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-1");
        request.setFileName("a.pdf");
        return request;
    }

    @Test
    @DisplayName("a save writes against the revision it read")
    void aSaveWritesAgainstTheRevisionItRead() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(row("legacy-x", "1-a", 1, null)));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> post = writeAnswering("legacy-x", "2-b");
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);

        assertTrue(jobs.saveToDlqReporting(itemRequest(), "boom", null, false, false));

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("legacy-x", written.getValue().document().getId(),
                "the write did not target the row that was read");
        assertEquals("1-a", written.getValue().document().getRev(),
                "the write is not conditioned on the revision that was read");
        // The bookkeeping travels on the record only: @JsonIgnore must be honoured by this
        // branch's mapper, or the row would carry a stale copy of its own storage fields.
        for (String key : List.of("storedId", "storedRevision", "storedAttachments")) {
            assertFalse(written.getValue().document().getProperties().containsKey(key),
                    "the transient bookkeeping was persisted: " + key);
        }
    }

    @Test
    @DisplayName("a save that lost the race re-merges from a fresh read")
    void aSaveThatLostTheRaceReMergesFromAFreshRead() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> first = findAnswering(List.of(row("legacy-x", "1-a", 1, null)));
        ServiceCall<FindResult> fresh = findAnswering(List.of(row("legacy-x", "2-b", 5, null)));
        when(cloudant.postFind(any())).thenReturn(first, fresh);
        ServiceCall<DocumentResult> lost = writeConflicting();
        ServiceCall<DocumentResult> won = writeAnswering("legacy-x", "3-c");
        when(cloudant.postDocument(any())).thenReturn(lost, won);
        IngestJobService jobs = serviceOn(cloudant);

        assertTrue(jobs.saveToDlqReporting(itemRequest(), "boom", null, false, false),
                "a save that lost one race was reported as not recorded");

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant, times(2)).postDocument(written.capture());
        Document second = written.getAllValues().get(1).document();
        assertEquals("2-b", second.getRev(), "the re-merge is not conditioned on the fresh read");
        assertEquals(6, second.get("failureCount"),
                "the re-merge did not take the other writer's fields into account");
    }

    @Test
    @DisplayName("a save that keeps losing is not recorded, and says so")
    void aSaveThatKeepsLosingIsNotRecorded() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<FindResult> find = findAnswering(List.of(row("legacy-x", "1-a", 1, null)));
        when(cloudant.postFind(any())).thenReturn(find);
        ServiceCall<DocumentResult> lost = writeConflicting();
        when(cloudant.postDocument(any())).thenReturn(lost);
        IngestJobService jobs = serviceOn(cloudant);

        assertFalse(jobs.saveToDlqReporting(itemRequest(), "boom", null, false, false),
                "a save that never landed was reported as recorded");
        verify(cloudant, times(3)).postDocument(any());
    }

    @Test
    @DisplayName("a reservation is conditioned on the revision the door read")
    void aReservationIsConditionedOnTheRevisionRead() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<DocumentResult> post = writeAnswering("legacy-x", "2-b");
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId("d1");
        dlq.setStoredId("legacy-x");
        dlq.setStoredRevision("1-a");

        assertTrue(jobs.reserveDlqRetry(dlq));

        ArgumentCaptor<PostDocumentOptions> written = ArgumentCaptor.forClass(PostDocumentOptions.class);
        verify(cloudant).postDocument(written.capture());
        assertEquals("1-a", written.getValue().document().getRev(),
                "the reservation adopted whatever revision was there instead of the one read");
        assertEquals("2-b", dlq.getStoredRevision(), "the record does not carry its new revision");
        verify(cloudant, never()).postFind(any());
    }

    @Test
    @DisplayName("a reservation without the revision it read is refused, not attempted")
    void aReservationWithoutAReadRevisionIsRefused() {
        Cloudant cloudant = mock(Cloudant.class);
        ServiceCall<DocumentResult> post = writeAnswering("ingest_dlq:d1", "1-a");
        when(cloudant.postDocument(any())).thenReturn(post);
        IngestJobService jobs = serviceOn(cloudant);
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId("d1");

        assertThrows(IngestJobService.DlqRetryNotReservableException.class,
                () -> jobs.reserveDlqRetry(dlq),
                "a reservation with nothing to condition on was attempted anyway");
        verify(cloudant, never()).postDocument(any());
    }

    @Test
    @DisplayName("the confirming write is conditioned on the re-read, not on this save's own row")
    @SuppressWarnings("unchecked")
    void aConfirmingWriteIsConditionedOnTheReRead() {
        // After the attachment lands the row has moved on (the attachment bumps the revision,
        // and another save may have written). The confirming write re-reads and is conditioned
        // on THAT revision; conditioned on this save's own earlier write it would lose every
        // time the attachment landed, and the row would stay "assumed" for ever.
        Cloudant cloudant = mock(Cloudant.class);
        AtomicInteger finds = new AtomicInteger();
        AtomicReference<String> token = new AtomicReference<>();
        List<PostDocumentOptions> writes = new ArrayList<>();
        when(cloudant.postFind(any())).thenAnswer(inv -> finds.incrementAndGet() == 1
                ? findAnswering(List.of(row("legacy-x", "1-a", 1, null)))
                : findAnswering(List.of(row("legacy-x", "3-c", 2, token.get()))));
        when(cloudant.postDocument(any())).thenAnswer(inv -> {
            PostDocumentOptions options = inv.getArgument(0);
            writes.add(options);
            if (writes.size() == 1) token.set((String) options.document().get("payloadWriteToken"));
            return writeAnswering("legacy-x", writes.size() == 1 ? "2-b" : "4-d");
        });
        Document afterWrite = new Document();
        afterWrite.setId("legacy-x");
        afterWrite.setRev("2-b");
        Response<Document> getResp = mock(Response.class);
        when(getResp.getResult()).thenReturn(afterWrite);
        ServiceCall<Document> getCall = mock(ServiceCall.class);
        when(getCall.execute()).thenReturn(getResp);
        when(cloudant.getDocument(any())).thenReturn(getCall);
        DocumentResult attOk = mock(DocumentResult.class);
        when(attOk.isOk()).thenReturn(Boolean.TRUE);
        Response<DocumentResult> putResp = mock(Response.class);
        when(putResp.getResult()).thenReturn(attOk);
        ServiceCall<DocumentResult> putCall = mock(ServiceCall.class);
        when(putCall.execute()).thenReturn(putResp);
        when(cloudant.putAttachment(any())).thenReturn(putCall);
        IngestJobService jobs = serviceOn(cloudant);

        assertTrue(jobs.saveToDlqReporting(itemRequest(), "boom",
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, true));

        assertEquals(2, writes.size(), "the confirming write did not happen");
        assertEquals("1-a", writes.get(0).document().getRev());
        assertEquals("3-c", writes.get(1).document().getRev(),
                "the confirming write is conditioned on this save's own row, not on the re-read");
        assertEquals(Boolean.FALSE, writes.get(1).document().get("payloadPresenceAssumed"));
    }
}
