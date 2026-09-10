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
package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A DLQ retry that was REFUSED must not answer 200 "failed".
 *
 * <p>"200 + status:failed + retryCount" tells an operator to try again. An authorisation
 * refusal answers the same way every time, so the entry is retried forever and the reason
 * never surfaces above the body. This branch made that reachable: the write point re-asks the
 * delegation, and its repository confinement runs before the admin short-circuit — while this
 * door is bound to the DEFAULT repository ({@code AuthenticationFilter} maps {@code
 * /v1/admin/*} that way) and the replayed request carries its ORIGINAL one. Replaying a
 * delegated entry of another repository is therefore refused on every attempt.
 *
 * <p>What this measures is the CONTROLLER's decision. The refusal message is the test's own,
 * because the import service is stubbed here — but the two doors cannot drift apart, since
 * this one calls the very same {@link ExternalIngestController#classifyErrorStatus} the ingest
 * door calls. That the PRODUCT emits those messages is measured in {@code
 * CanonicalImportServiceTest}.
 */
class DlqRetryRefusalStatusTest {

    private ResponseEntity<?> retryWithResult(ExternalIngestResult stubbed) throws Exception {
        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any())).thenReturn(stubbed);
        return retryWith(row -> { }, importService, null);
    }

    private HttpServletRequest adminRequest() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getUsername()).thenReturn("admin");
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute("CallContext")).thenReturn(ctx);
        return http;
    }

    private ResponseEntity<?> retryWith(java.util.function.Consumer<IngestDeadLetterRecord> shapeRow,
            CanonicalImportService importService,
            java.util.function.Consumer<IngestJobService> extraStubbing) throws Exception {
        IngestDlqController controller = new IngestDlqController();

        IngestJobService jobService = mock(IngestJobService.class);
        IngestDeadLetterRecord row = new IngestDeadLetterRecord();
        row.setDlqId("dlq-1");
        row.setHasContent(false);
        row.setRetryCount(0);
        row.setOriginalRequestJson("{\"connectorId\":\"c1\",\"repositoryId\":\"canopy\","
                + "\"sourceObjectId\":\"obj1\"}");
        when(jobService.getDlqEntry("dlq-1")).thenReturn(row);
        // Faithful to the real one, which INCREMENTS the record it is handed and persists it.
        // A mock that only returns true made the response's retryCount look right while the
        // product was answering one more than it had stored.
        when(jobService.reserveDlqRetry(any())).thenAnswer(inv -> {
            IngestDeadLetterRecord r = inv.getArgument(0);
            r.setRetryCount(r.getRetryCount() + 1);
            return true;
        });

        shapeRow.accept(row);
        if (extraStubbing != null) extraStubbing.accept(jobService);

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);

        wire(controller, "ingestJobService", jobService);
        wire(controller, "canonicalImportService", importService);
        wire(controller, "connectorDefinitionService", connectorService);
        wire(controller, "httpRequest", adminRequest());

        return controller.retryDlqEntry("dlq-1");
    }

    private void wire(IngestDlqController controller, String field, Object value)
            throws Exception {
        Field f = IngestDlqController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    @DisplayName("a refused retry answers 403, not 200 'failed'")
    void aRefusedRetryIsNotAnswered200() throws Exception {
        ResponseEntity<?> res = retryWithResult(ExternalIngestResult.error("req-1",
                "this import is for repository canopy, which is not the repository this"
                        + " caller authenticated against"));

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "a permanent refusal was answered as a failed attempt, so the entry is "
                        + "retried forever and the reason never leaves the body");
        assertInstanceOf(Map.class, res.getBody());
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("not the repository this caller authenticated"),
                "the answer does not say why: " + res.getBody());
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("the entry is kept"),
                "the answer does not say the entry survived: " + res.getBody());
    }

    @Test
    @DisplayName("an entry whose bytes were never stored is not replayed content-less")
    void anEntryWhoseBytesWereDroppedIsNotReplayed() throws Exception {
        // payloadDropReason is written when encryption refused the bytes — a missing
        // NEMAKI_ENCRYPTION_KEY does exactly this — and NOTHING in the codebase read the
        // field. hasContent is false, so the restore was skipped, the import succeeded as
        // metadata-only, and the row was DELETED: a 0-byte document now stands in the
        // repository as the recovered item and the only record of the loss is gone. A review
        // traced the chain end to end.
        CanonicalImportService importService = mock(CanonicalImportService.class);
        ResponseEntity<?> res = retryWith(row -> {
            row.setHasContent(false);
            row.setPayloadDropReason("payload not stored: SECURITY ERROR:"
                    + " NEMAKI_ENCRYPTION_KEY is not set");
        }, importService, null);

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "an entry whose bytes were never stored was replayed without them");
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("never stored"),
                "the answer does not say why: " + res.getBody());
        org.mockito.Mockito.verify(importService, org.mockito.Mockito.never())
                .execute(any(), any());
    }

    @Test
    @DisplayName("an ASSUMED payload that a read disproves stops refusing the entry")
    void anAssumedPayloadDisprovedByAReadIsReplayed() throws Exception {
        // The assumption was a FIXED POINT. hasContent=true was set while the store could not
        // be asked; the retry then found no payload and answered 409; and every later save
        // re-set the flag, because the probe reported "no attachment block" as unanswerable
        // too. Every orchestrator saves with no bytes, so a metadata-only entry became
        // permanently un-retryable after one blip, with DELETE — destroying the only record
        // of the loss — the sole way out. Codex and a subagent derived it independently.
        //
        // loadDlqContent now REFUSES when it cannot see the row at all, so a null from it is
        // an answer: this entry carries no payload, and the assumption is disproven.
        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any()))
                .thenReturn(ExternalIngestResult.success("req-1", "obj-9", "1.0", false, null));
        ResponseEntity<?> res = retryWith(row -> {
            row.setHasContent(true);
            row.setPayloadPresenceAssumed(true);
        }, importService, jobService ->
                when(jobService.loadDlqContent("dlq-1")).thenReturn(null));

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "an assumption the store has now answered still refused the entry, and the "
                        + "only way out is deleting the only record of the loss");
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) res.getBody()).get("payloadPresenceAssumptionCleared"),
                "the answer does not say the assumption was settled by a read: " + res.getBody());
    }

    @Test
    @DisplayName("a row holding an EARLIER attempt's payload is not replayed with newer metadata")
    void anOlderPayloadIsNotPairedWithNewerMetadata() throws Exception {
        // The first version of this guard tested !hasContent, so it missed the inverse: the
        // row keeps attempt A's attachment (hasContent=true) while attempt B's bytes were
        // refused — and originalRequestJson on the row is B's. Replaying pairs A's bytes with
        // B's metadata and calls the hybrid the recovered item, then deletes the evidence row.
        // Codex named it in the round after the guard was written.
        CanonicalImportService importService = mock(CanonicalImportService.class);
        ResponseEntity<?> res = retryWith(row -> {
            row.setHasContent(true);
            row.setPayloadDropReason("payload not stored: NEMAKI_ENCRYPTION_KEY is not set");
        }, importService, null);

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "a payload from an earlier attempt was replayed under this attempt's metadata");
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("EARLIER attempt"),
                "the answer does not say the payload is not this attempt's: " + res.getBody());
        org.mockito.Mockito.verify(importService, org.mockito.Mockito.never())
                .execute(any(), any());
    }

    @Test
    @DisplayName("the last page carries no continuation token")
    void theLastPageHasNoNextOffset() throws Exception {
        // A client following nextOffset rather than reading hasMore walked an endless run of
        // empty pages. A review found it in the round that added the token.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.listDlqPage(100, 0, true)).thenReturn(
                new IngestJobService.DlqPage(java.util.List.of(), 0, false));
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        Map<?, ?> body = (Map<?, ?>) ((ResponseEntity<?>) controller.listDlq(100, 0)).getBody();

        assertEquals(Boolean.FALSE, body.get("hasMore"));
        assertNull(body.get("nextOffset"),
                "the last page still offered a continuation: " + body);
    }

    @Test
    @DisplayName("a delete the selector could not see is not answered as success")
    void aDeleteThatRemovedNothingIsNotSuccess() throws Exception {
        // deleteDlqEntry walks a Mango selector. A rebuilding index returns no row, nothing is
        // deleted, and the operator was told the entry is gone — while it is still there and
        // will be back in the next listing. The purge sibling was given this distinction a
        // round earlier; a review found the single delete still asserting it.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.deleteDlqEntry("dlq-1")).thenReturn(0);
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        ResponseEntity<?> res = (ResponseEntity<?>) controller.deleteDlqEntry("dlq-1");

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(),
                "a delete that removed nothing was answered as success");
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("nothing was deleted"),
                "the answer does not say the row survived: " + res.getBody());
    }

    @Test
    @DisplayName("a job listing that dropped a row says so")
    void aJobListingThatDroppedARowSaysSo() throws Exception {
        // A PARTIAL or FAILED run written by a newer node looked like it never happened. The
        // DLQ listing in the same controller says how many rows it could not decode; a review
        // found the job listing silent.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.listJobsPage(50)).thenReturn(
                new IngestJobService.JobPage(java.util.List.of(), 2));
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        Object body = ((ResponseEntity<?>) controller.listJobs(50)).getBody();

        assertInstanceOf(Map.class, body,
                "a listing that dropped rows still answered a bare array: " + body);
        assertEquals(2, ((Map<?, ?>) body).get("unreadableEntries"),
                "the answer does not say rows are missing from it: " + body);
    }

    @Test
    @DisplayName("a job listing that dropped nothing keeps its array shape")
    void aCleanJobListingIsStillAnArray() throws Exception {
        // The other direction: existing clients read an array. Wrapping every response would
        // break them for a condition that is not happening.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.listJobsPage(50)).thenReturn(
                new IngestJobService.JobPage(java.util.List.of(), 0));
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        assertInstanceOf(java.util.List.class,
                ((ResponseEntity<?>) controller.listJobs(50)).getBody(),
                "a clean listing stopped answering an array");
    }

    @Test
    @DisplayName("a reservation that could not be ATTEMPTED is 503, not 'someone else has it'")
    void aReservationThatCouldNotBeAttemptedIsNot429() throws Exception {
        // CouchDB unreachable returned the same false as losing a _rev conflict, and the door
        // then told the operator "another retry is already in progress" — a fact about a
        // concurrent request that nothing established, with a status telling them to wait.
        // assertDoesNotThrow, because what this measures is the CONTROLLER's catch: without
        // it the refusal escapes and the runner scores a broken harness rather than a firing.
        ResponseEntity<?> res = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                retryWith(row -> { }, mock(CanonicalImportService.class),
                // doThrow, not when(...).thenThrow: re-stubbing a method that already has an
                // Answer INVOKES that answer during stubbing, and the previous one
                // dereferences the record it is handed.
                jobService -> org.mockito.Mockito.doThrow(
                        new IngestJobService.DlqRetryNotReservableException(
                                "the retry of DLQ entry dlq-1 could not be reserved: no route"
                                        + " to host", null))
                        .when(jobService).reserveDlqRetry(any())),
                "the endpoint let the refusal escape instead of answering 503");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a reservation that could not be attempted was reported as a rival holding it");
        assertTrue(String.valueOf(((Map<?, ?>) res.getBody()).get("message"))
                        .contains("could not be reserved"),
                "the answer does not say what happened: " + res.getBody());
    }

    @Test
    @DisplayName("a purge that stopped part-way is not answered as a completed purge")
    void aPurgeThatStoppedIsNotSuccess() throws Exception {
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.purgeDlqOlderThan(any())).thenThrow(
                new IngestJobService.DlqPurgeIncompleteException(
                        "the dead-letter purge did not complete (no route to host); 7 entries"
                                + " were deleted before it stopped", 7, null));
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        ResponseEntity<?> res = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> (ResponseEntity<?>) controller.purgeDlq(30),
                "the endpoint let the refusal escape instead of answering 503");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a read that never ran was answered as a purge that found nothing old enough");
        assertEquals(7, ((Map<?, ?>) res.getBody()).get("deleted"),
                "what WAS deleted is a fact and has to travel with the refusal: " + res.getBody());
    }

    @Test
    @DisplayName("a page holding an undecodable row does not claim the queue ends there")
    void aPageWithAnUndecodableRowDoesNotClaimTheEnd() throws Exception {
        // The probe row asks "is there more". A row that could not be DECODED still occupied a
        // slot the store returned, so counting only decoded rows made the page answer
        // "hasMore: false" — and every later entry, each the only record that a source item
        // was lost, became unreachable. Two reviewers built the same scenario.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobService = mock(IngestJobService.class);
        java.util.List<IngestDeadLetterRecord> decoded = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            IngestDeadLetterRecord r = new IngestDeadLetterRecord();
            r.setDlqId("d-" + i);
            decoded.add(r);
        }
        // The service decodes exactly the page and answers hasMore from a probe row it does
        // NOT return. The controller must not re-derive either from the entry count.
        when(jobService.listDlqPage(100, 0, true))
                .thenReturn(new IngestJobService.DlqPage(decoded, 1, true));
        wire(controller, "ingestJobService", jobService);
        wire(controller, "httpRequest", adminRequest());

        ResponseEntity<?> res = (ResponseEntity<?>) controller.listDlq(100, 0);
        Map<?, ?> body = (Map<?, ?>) res.getBody();

        assertEquals(Boolean.TRUE, body.get("hasMore"),
                "the queue was declared finished because the row past the page could not be"
                        + " decoded");
        assertEquals(1, body.get("unreadableEntries"),
                "the answer does not say a row is missing from it: " + body);
        // Paging by 'count' would re-read the undecodable row for ever.
        assertEquals(100, body.get("nextOffset"),
                "the next page starts where this one ended, not where its entries ended: "
                        + body);
    }

    @Test
    @DisplayName("an ordinary failed retry still answers 200 with its retryCount")
    void anOrdinaryFailureIsStill200() throws Exception {
        // The other direction. Turning EVERY failed retry into a 4xx would break the callers
        // that read {"status":"failed","retryCount":N} — over-throwing is a defect here too.
        ResponseEntity<?> res = retryWithResult(
                ExternalIngestResult.error("req-1", "the upstream adapter returned no bytes"));

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "an ordinary failed retry stopped answering 200");
        assertInstanceOf(Map.class, res.getBody());
        assertEquals("failed", ((Map<?, ?>) res.getBody()).get("status"));
        // The row stores N+1 after the reservation. Reporting N+2 made the answer stronger
        // than the stored fact.
        assertEquals(1, ((Map<?, ?>) res.getBody()).get("retryCount"),
                "the answer disagrees with what reserveDlqRetry persisted");
    }
}
