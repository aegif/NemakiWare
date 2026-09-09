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
        IngestDlqController controller = new IngestDlqController();

        IngestJobService jobService = mock(IngestJobService.class);
        IngestDeadLetterRecord row = new IngestDeadLetterRecord();
        row.setDlqId("dlq-1");
        row.setHasContent(false);
        row.setRetryCount(0);
        row.setOriginalRequestJson("{\"connectorId\":\"c1\",\"repositoryId\":\"canopy\","
                + "\"sourceObjectId\":\"obj1\"}");
        when(jobService.getDlqEntry("dlq-1")).thenReturn(row);
        when(jobService.reserveDlqRetry(any())).thenReturn(true);

        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceArchetype(SourceArchetype.FILE_SHARE);
        when(connectorService.get("c1")).thenReturn(connector);
        when(connectorService.countIndexFree("c1")).thenReturn(1);

        CanonicalImportService importService = mock(CanonicalImportService.class);
        when(importService.execute(any(), any())).thenReturn(stubbed);

        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getUsername()).thenReturn("admin");
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getAttribute("CallContext")).thenReturn(ctx);

        wire(controller, "ingestJobService", jobService);
        wire(controller, "canonicalImportService", importService);
        wire(controller, "connectorDefinitionService", connectorService);
        wire(controller, "httpRequest", http);

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
        assertEquals(1, ((Map<?, ?>) res.getBody()).get("retryCount"));
    }
}
