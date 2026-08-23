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
}
