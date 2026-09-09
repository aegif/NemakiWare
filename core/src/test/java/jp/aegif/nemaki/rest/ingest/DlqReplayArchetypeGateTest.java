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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A DLQ replay refuses an archetype-less connector instead of falling back to the plain path.
 *
 * <p>The fallback was D1's last remnant: replaying a chat item through the generic
 * {@code execute()} emitted an event carrying the request's {@code chat.*} facts while the chat
 * aspect was never attached — "the event asserts, the object lacks", manufactured by the
 * product's own recovery tool (data-model D1, audit #21). A null archetype is a
 * connector-definition defect; replaying through the defect turns one broken row into a
 * permanently mismatched object.
 */
class DlqReplayArchetypeGateTest {

    private ExternalIngestResult dispatch(SourceArchetype archetype,
            CanonicalImportService canonicalImportService) throws Exception {
        IngestDlqController controller = new IngestDlqController();

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(archetype);
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);

        for (String[] wire : new String[][]{
                {"connectorDefinitionService"}, {"canonicalImportService"}}) {
            Field f = IngestDlqController.class.getDeclaredField(wire[0]);
            f.setAccessible(true);
            f.set(controller, wire[0].equals("connectorDefinitionService")
                    ? connectorService : canonicalImportService);
        }

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setConnectorId("c1");
        request.setRepositoryId("bedroom");
        request.setSourceObjectId("m-1");

        Method m = IngestDlqController.class.getDeclaredMethod("dispatchByArchetype",
                org.apache.chemistry.opencmis.commons.server.CallContext.class,
                ExternalIngestRequest.class);
        m.setAccessible(true);
        return (ExternalIngestResult) m.invoke(controller,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), request);
    }

    @Test
    @DisplayName("a null archetype is refused with the fix named — not routed to plain execute")
    void nullArchetypeIsRefused() throws Exception {
        CanonicalImportService importService = mock(CanonicalImportService.class);

        ExternalIngestResult result = dispatch(null, importService);

        assertFalse(result.isSuccess(),
                "an archetype-less replay went through anyway — the object it produces asserts "
                        + "facts its aspects never receive");
        assertTrue(String.join(" ", result.errors()).contains("sourceArchetype"),
                "the refusal does not tell the operator what to fix: " + result.errors());
        verify(importService, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a FILE_SHARE connector still routes to the plain path — the control")
    void fileShareStillRoutes() throws Exception {
        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any()))
                .thenReturn(ExternalIngestResult.skipped("r", "already"));

        ExternalIngestResult result = dispatch(SourceArchetype.FILE_SHARE, importService);

        verify(importService).execute(any(), any());
        assertTrue(result.skipped(), "the archetype gate must not refuse legitimate replays");
    }

    @Test
    @DisplayName("a retry whose connector row cannot be read answers 503, not 500 — the "
            + "controller's own catch-all had made its handler unreachable")
    void aRetryWhoseConnectorCannotBeReadIsNotOurBug() throws Exception {
        // retryDlqEntry wraps everything after the reservation in catch(Exception) -> 500
        // "Retry failed: ...". The one call in this controller that can raise a typed refusal
        // sits inside it, so the @ExceptionHandler added for exactly this condition was dead
        // code and the caller was told "our bug" for something a retry fixes. Two reviews
        // found it in the same round, one of them by tracing every catch between the throw
        // and the handler.
        IngestDlqController controller = new IngestDlqController();

        IngestJobService jobs = mock(IngestJobService.class);
        jp.aegif.nemaki.rest.ingest.IngestDeadLetterRecord dlq =
                new jp.aegif.nemaki.rest.ingest.IngestDeadLetterRecord();
        dlq.setOriginalRequestJson("{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\","
                + "\"sourceObjectId\":\"m-1\"}");
        when(jobs.getDlqEntry("d-1")).thenReturn(dlq);
        when(jobs.reserveDlqRetry(dlq)).thenReturn(true);

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        when(connectorService.get("c1")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c1 exists but could not be read as that connector"));

        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN))
                .thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);

        for (Object[] wire : new Object[][]{
                {"ingestJobService", jobs},
                {"connectorDefinitionService", connectorService},
                {"canonicalImportService", mock(CanonicalImportService.class)},
                {"httpRequest", request}}) {
            Field f = IngestDlqController.class.getDeclaredField((String) wire[0]);
            f.setAccessible(true);
            f.set(controller, wire[1]);
        }

        org.junit.jupiter.api.Assertions.assertThrows(
                ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> controller.retryDlqEntry("d-1"),
                "the retry swallowed a read refusal as a 500 'Retry failed'");
    }

    @Test
    @DisplayName("a retry whose stored payload cannot be read does not run content-less")
    void aRetryWhosePayloadCannotBeReadDoesNotRunContentLess() throws Exception {
        // loadDlqContent answered null both for "this entry has no attachment" and for a read
        // that FAILED — a rotated key, a ciphertext this node cannot decrypt (the refusal that
        // exists so ciphertext is never fed to a retry), a timed-out attachment read. The
        // retry then imported the entry with NO content, the import succeeded as
        // metadata-only, and the DLQ row — the only record that the source item was lost —
        // was deleted. A review found it.
        IngestDlqController controller = new IngestDlqController();

        IngestJobService jobs = mock(IngestJobService.class);
        jp.aegif.nemaki.rest.ingest.IngestDeadLetterRecord dlq =
                new jp.aegif.nemaki.rest.ingest.IngestDeadLetterRecord();
        dlq.setOriginalRequestJson("{\"connectorId\":\"c1\",\"repositoryId\":\"bedroom\","
                + "\"sourceObjectId\":\"m-1\"}");
        dlq.setHasContent(true);
        when(jobs.getDlqEntry("d-1")).thenReturn(dlq);
        when(jobs.reserveDlqRetry(dlq)).thenReturn(true);
        when(jobs.loadDlqContent("d-1")).thenThrow(
                new IngestJobService.DlqContentUnreadableException(
                        "the stored payload of DLQ entry d-1 could not be read: bad key", null));

        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN))
                .thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);

        CanonicalImportService importService = mock(CanonicalImportService.class);
        for (Object[] wire : new Object[][]{
                {"ingestJobService", jobs},
                {"connectorDefinitionService", mock(ConnectorDefinitionService.class)},
                {"canonicalImportService", importService},
                {"httpRequest", request}}) {
            Field f = IngestDlqController.class.getDeclaredField((String) wire[0]);
            f.setAccessible(true);
            f.set(controller, wire[1]);
        }

        org.springframework.http.ResponseEntity<?> res = controller.retryDlqEntry("d-1");

        org.junit.jupiter.api.Assertions.assertEquals(503, res.getStatusCode().value(),
                "a payload that could not be read was answered as an entry with none");
        verify(importService, never()).execute(any(), any());
        verify(jobs, never()).deleteDlqEntry(any());
    }

    @Test
    @DisplayName("the payload read itself refuses rather than answering 'there is none'")
    void thePayloadReadRefusesRatherThanAnsweringNone() {
        // The controller lock above mocks the service, so it measures the controller's arm
        // and not the service's throw — the control on the throw stayed green, which is how
        // the pair was found. This drives the service: with nothing wired, the read cannot
        // answer, and "there is no payload" is not the answer.
        IngestJobService jobs = new IngestJobService();

        IngestJobService.DlqContentUnreadableException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IngestJobService.DlqContentUnreadableException.class,
                        () -> jobs.loadDlqContent("d-1"),
                        "a payload read that could not answer returned 'there is none'");
        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("could not be read"),
                "the refusal does not say what happened: " + refused.getMessage());
    }

    @Test
    @DisplayName("a stored DLQ entry that cannot be read is 503 from the ENDPOINT, not 404")
    void aStoredButUnreadableEntryIsNotReportedAsAbsent() throws Exception {
        // The listing skips such rows on purpose — one broken row must not hide the queue —
        // but the single-entry read answered null and the endpoint turned that into
        // 404 "DLQ entry not found". This class says three times that the entry is the only
        // record a source item was lost.
        //
        // Driven through the ENDPOINT, not the handler. The first version called
        // dlqEntryCouldNotBeRead directly, which is a tautology over a three-line method: it
        // could not tell whether the refusal reaches the handler at all, and this same file
        // already records a round where a catch-all made a handler dead code. Two reviewers
        // named it.
        IngestDlqController controller = new IngestDlqController();
        IngestJobService jobs = mock(IngestJobService.class);
        when(jobs.getDlqEntry("d-1")).thenThrow(
                new IngestJobService.DlqEntryUnreadableException(
                        "DLQ entry d-1 is stored but could not be read"));

        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN))
                .thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        for (Object[] wire : new Object[][]{
                {"ingestJobService", jobs}, {"httpRequest", request}}) {
            Field f = IngestDlqController.class.getDeclaredField((String) wire[0]);
            f.setAccessible(true);
            f.set(controller, wire[1]);
        }

        IngestJobService.DlqEntryUnreadableException escaped =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IngestJobService.DlqEntryUnreadableException.class,
                        () -> controller.retryDlqEntry("d-1"),
                        "the endpoint swallowed the refusal instead of letting the handler"
                                + " answer 503");
        org.junit.jupiter.api.Assertions.assertEquals(503,
                controller.dlqEntryCouldNotBeRead(escaped).getStatusCode().value(),
                "the handler answered something other than a retry");
    }

    @Test
    @DisplayName("the entry read itself refuses rather than answering 'there is none'")
    void theEntryReadRefusesRatherThanAnsweringNone() {
        // The controller lock above mocks the service, so it measures the handler. This
        // drives the service: with nothing wired, whether the entry exists cannot be
        // established, and null is what the endpoint turns into 404 "not found" — for the
        // row this class calls the only record that a source item was lost.
        IngestJobService jobs = new IngestJobService();

        IngestJobService.DlqEntryUnreadableException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IngestJobService.DlqEntryUnreadableException.class,
                        () -> jobs.getDlqEntry("d-1"),
                        "a read that could not answer reported the entry as absent");
        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("could not be established")
                        || refused.getMessage().contains("could not be read"),
                "the refusal does not say what happened: " + refused.getMessage());
    }

    @Test
    @DisplayName("writing over an unreadable DLQ row does not clear its payload flag")
    void writingOverAnUnreadableRowKeepsTheAttachedPayload() throws Exception {
        // The catch that keeps the WRITE path alive sets `existing = null`, and null is the
        // answered-nothing value that decides hasContent. So the row was rewritten saying
        // "no payload" while the upsert carried its attachment forward, and the next retry
        // then imported content-less and DELETED the row — the loss chain this class exists
        // to prevent, reopened through the flag. Two reviewers found it in the round that
        // added the catch.
        IngestJobService jobs = new IngestJobService();

        com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(org.mockito.ArgumentMatchers.anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);

        // The stored row: it carries an attachment and a field the record cannot decode, so
        // the typed read refuses and the raw read finds it.
        com.ibm.cloud.cloudant.v1.model.Document stored =
                new com.ibm.cloud.cloudant.v1.model.Document();
        stored.setId("ingest_dlq:x");
        stored.setRev("1-a");
        stored.put("type", "ingest_dead_letter");
        stored.put("dlqId", "x");
        // A TYPE the record cannot take, so the typed read genuinely refuses. An unknown
        // FIELD is not enough: the mapper tolerates those here.
        stored.put("failureCount", "not-a-number");
        stored.setAttachments(java.util.Map.of("payload",
                mock(com.ibm.cloud.cloudant.v1.model.Attachment.class)));

        com.ibm.cloud.cloudant.v1.model.FindResult found =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(found.getDocs()).thenReturn(java.util.List.of(stored));
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> call =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.FindResult> resp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(resp.getResult()).thenReturn(found);
        when(call.execute()).thenReturn(resp);
        when(cloudant.postFind(org.mockito.ArgumentMatchers.any())).thenReturn(call);

        com.ibm.cloud.cloudant.v1.model.DocumentResult ok =
                mock(com.ibm.cloud.cloudant.v1.model.DocumentResult.class);
        when(ok.isOk()).thenReturn(Boolean.TRUE);
        when(ok.getId()).thenReturn("ingest_dlq:x");
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> post =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.DocumentResult> postResp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(postResp.getResult()).thenReturn(ok);
        when(post.execute()).thenReturn(postResp);
        when(cloudant.postDocument(org.mockito.ArgumentMatchers.any())).thenReturn(post);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-1");

        jobs.saveToDlq(request, "boom again", null);

        org.mockito.ArgumentCaptor<com.ibm.cloud.cloudant.v1.model.PostDocumentOptions> written =
                org.mockito.ArgumentCaptor.forClass(
                        com.ibm.cloud.cloudant.v1.model.PostDocumentOptions.class);
        org.mockito.Mockito.verify(cloudant, org.mockito.Mockito.atLeastOnce())
                .postDocument(written.capture());
        Object hasContent = written.getAllValues().get(written.getAllValues().size() - 1)
                .document().get("hasContent");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, hasContent,
                "the row was rewritten saying it has no payload while its attachment is still"
                        + " attached; the next retry imports empty and deletes it");
        // The discriminator. TRUE here is an ANSWER — the probe saw the attachment — and the
        // arm below produces the same TRUE from an ASSUMPTION. Asserting hasContent alone let
        // the whole assumed arm be deleted with the suite green; three reviewers said so in
        // the same round.
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                written.getAllValues().get(written.getAllValues().size() - 1)
                        .document().get("payloadPresenceAssumed"),
                "an ANSWERED payload presence was recorded as an assumption");
    }

    /**
     * The probe itself could not answer. Three shapes reach this: the raw read threw, the
     * index returned no row for a document the typed read had just called stored, and the
     * response carried no attachment block at all. None of them is "there is no attachment",
     * and returning false for them reopened the loss chain one line below the arm that had
     * just been fixed to avoid it.
     */
    private Object[] saveOverAnUnreadableRow(boolean probeThrows) throws Exception {
        IngestJobService jobs = new IngestJobService();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(org.mockito.ArgumentMatchers.anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);

        com.ibm.cloud.cloudant.v1.model.FindResult empty =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(empty.getDocs()).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> emptyCall =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.FindResult> emptyResp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(emptyResp.getResult()).thenReturn(empty);
        when(emptyCall.execute()).thenReturn(emptyResp);

        // Call 1 is getDlqEntry's selector: it throws, so the typed read refuses with "could
        // not be established". Call 2 is the attachment probe — either it throws too, or it
        // comes back with no row for a document the store was just unable to speak about.
        // Call 3 onward is upsertDocument's own probe, which must succeed so the write lands
        // and this test can read what was written.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(cloudant.postFind(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1 || (n == 2 && probeThrows)) {
                throw new RuntimeException("the configuration database did not answer");
            }
            return emptyCall;
        });

        com.ibm.cloud.cloudant.v1.model.DocumentResult ok =
                mock(com.ibm.cloud.cloudant.v1.model.DocumentResult.class);
        when(ok.isOk()).thenReturn(Boolean.TRUE);
        when(ok.getId()).thenReturn("ingest_dlq:y");
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> post =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.DocumentResult> postResp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(postResp.getResult()).thenReturn(ok);
        when(post.execute()).thenReturn(postResp);
        when(cloudant.postDocument(org.mockito.ArgumentMatchers.any())).thenReturn(post);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-2");

        jobs.saveToDlq(request, "boom again", null);

        org.mockito.ArgumentCaptor<com.ibm.cloud.cloudant.v1.model.PostDocumentOptions> written =
                org.mockito.ArgumentCaptor.forClass(
                        com.ibm.cloud.cloudant.v1.model.PostDocumentOptions.class);
        org.mockito.Mockito.verify(cloudant, org.mockito.Mockito.atLeastOnce())
                .postDocument(written.capture());
        com.ibm.cloud.cloudant.v1.model.Document doc =
                written.getAllValues().get(written.getAllValues().size() - 1).document();
        return new Object[]{doc.get("hasContent"), doc.get("payloadPresenceAssumed"),
                doc.get("failureCount")};
    }

    @Test
    @DisplayName("a later byte-less failure does not erase that this item's bytes were dropped")
    void theDropReasonSurvivesTheNextFailure() throws Exception {
        // payloadDropReason is written when encryption refused the bytes, and the retry door
        // reads it to refuse rather than import an empty document over the item. Setting it
        // from THIS attempt alone wrote null over the earlier reason as soon as one byte-less
        // failure arrived for the same item — and every orchestrator saves with no bytes. The
        // protection lasted exactly until the next failure. Codex traced it.
        IngestJobService jobs = new IngestJobService();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(org.mockito.ArgumentMatchers.anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);

        // A readable row that already records the drop.
        com.ibm.cloud.cloudant.v1.model.Document stored =
                new com.ibm.cloud.cloudant.v1.model.Document();
        stored.setId("ingest_dlq:z");
        stored.setRev("1-a");
        stored.put("type", "ingest_dead_letter");
        stored.put("dlqId", "z");
        stored.put("hasContent", Boolean.FALSE);
        stored.put("payloadDropReason", "payload not stored: NEMAKI_ENCRYPTION_KEY is not set");
        com.ibm.cloud.cloudant.v1.model.FindResult found =
                mock(com.ibm.cloud.cloudant.v1.model.FindResult.class);
        when(found.getDocs()).thenReturn(java.util.List.of(stored));
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.FindResult> call =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.FindResult> resp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(resp.getResult()).thenReturn(found);
        when(call.execute()).thenReturn(resp);
        when(cloudant.postFind(org.mockito.ArgumentMatchers.any())).thenReturn(call);

        com.ibm.cloud.cloudant.v1.model.DocumentResult ok =
                mock(com.ibm.cloud.cloudant.v1.model.DocumentResult.class);
        when(ok.isOk()).thenReturn(Boolean.TRUE);
        when(ok.getId()).thenReturn("ingest_dlq:z");
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> post =
                mock(com.ibm.cloud.sdk.core.http.ServiceCall.class);
        @SuppressWarnings("unchecked")
        com.ibm.cloud.sdk.core.http.Response<com.ibm.cloud.cloudant.v1.model.DocumentResult> postResp =
                mock(com.ibm.cloud.sdk.core.http.Response.class);
        when(postResp.getResult()).thenReturn(ok);
        when(post.execute()).thenReturn(postResp);
        when(cloudant.postDocument(org.mockito.ArgumentMatchers.any())).thenReturn(post);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("m-3");

        // No bytes — the shape every orchestrator saves with.
        jobs.saveToDlq(request, "boom again", null);

        org.mockito.ArgumentCaptor<com.ibm.cloud.cloudant.v1.model.PostDocumentOptions> written =
                org.mockito.ArgumentCaptor.forClass(
                        com.ibm.cloud.cloudant.v1.model.PostDocumentOptions.class);
        org.mockito.Mockito.verify(cloudant, org.mockito.Mockito.atLeastOnce())
                .postDocument(written.capture());
        Object reason = written.getAllValues().get(written.getAllValues().size() - 1)
                .document().get("payloadDropReason");
        org.junit.jupiter.api.Assertions.assertNotNull(reason,
                "the record that this item's bytes were never stored was erased by the next"
                        + " failure, so the retry will import an empty document and delete the"
                        + " row");
    }

    /** A service wired to a store that answers nothing at all. */
    private IngestJobService jobsWithADeadStore() {
        IngestJobService jobs = new IngestJobService();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant =
                mock(com.ibm.cloud.cloudant.v1.Cloudant.class);
        when(cloudant.postFind(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("no route to host"));
        when(cloudant.postDocument(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("no route to host"));
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper wrapper =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(wrapper.getClient()).thenReturn(cloudant);
        when(wrapper.getDatabaseName()).thenReturn("nemaki_conf");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
        when(pool.getClient(org.mockito.ArgumentMatchers.anyString())).thenReturn(wrapper);
        jobs.setConnectorPool(pool);
        return jobs;
    }

    @Test
    @DisplayName("a reservation that could not be attempted refuses instead of returning false")
    void theReservationRefusesRatherThanLookingLost() {
        // false is what a lost _rev conflict returns, and the door turns it into 429 "another
        // retry is already in progress" — a fact about a concurrent request that nothing
        // established. The endpoint lock for this stubs the service, so it measures the
        // CONTROLLER; this measures the service that has to raise it.
        IngestDeadLetterRecord row = new IngestDeadLetterRecord();
        row.setDlqId("d-9");
        org.junit.jupiter.api.Assertions.assertThrows(
                IngestJobService.DlqRetryNotReservableException.class,
                () -> jobsWithADeadStore().reserveDlqRetry(row),
                "a store that never answered was reported as a rival holding the reservation");
    }

    @Test
    @DisplayName("a purge that could not read refuses instead of returning its partial count")
    void thePurgeRefusesRatherThanLookingComplete() {
        // Returning the count made the door answer {"status":"success","deleted":0} — the same
        // answer as a completed purge that found nothing older than the cutoff.
        IngestJobService.DlqPurgeIncompleteException stopped =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IngestJobService.DlqPurgeIncompleteException.class,
                        () -> jobsWithADeadStore().purgeDlqOlderThan(java.time.Instant.EPOCH),
                        "a read that never ran was reported as a completed purge");
        org.junit.jupiter.api.Assertions.assertEquals(0, stopped.getDeletedBeforeStopping(),
                "the refusal does not carry what was actually deleted");
    }

    @Test
    @DisplayName("a probe that THREW is not 'there is no attachment'")
    void aProbeThatThrewIsNotAnAnsweredAbsence() throws Exception {
        Object[] written = saveOverAnUnreadableRow(true);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, written[0],
                "the row was written saying it has no payload, from a probe that never"
                        + " answered; the next retry imports empty and deletes it");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, written[1],
                "the row asserts a payload as an established fact when it was assumed");
    }

    @Test
    @DisplayName("a probe that found NO ROW is not 'there is no attachment' either")
    void aProbeThatFoundNoRowIsNotAnAnsweredAbsence() throws Exception {
        // The typed read has just refused because the store could not speak about this row.
        // The index then returning nothing contradicts that or means nothing; either way it
        // does not establish that the document carries no payload. This arm answered FALSE,
        // one line below the arm the previous round had fixed for the same reason.
        Object[] written = saveOverAnUnreadableRow(false);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, written[0],
                "an index that returned no row was read as 'the payload is gone'");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, written[1],
                "the row asserts a payload as an established fact when it was assumed");
    }

    @Test
    @DisplayName("writing over an unreadable row does not restate its history as a first failure")
    void anUnreadableRowDoesNotResetTheFailureHistory() throws Exception {
        // existing == null is the ANSWERED-nothing value here too, and buildDlqRecord read it
        // as "this item has never failed before": failureCount=1, firstFailedAt=now,
        // retryCount=0. A month-long outage was rewritten as a first failure, on the field
        // IngestDeadLetterRecord itself calls the one that "does not move".
        Object[] written = saveOverAnUnreadableRow(true);
        org.junit.jupiter.api.Assertions.assertEquals(0, written[2],
                "an unreadable row was restated as the item's first failure");
    }
}
