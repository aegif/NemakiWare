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
}
