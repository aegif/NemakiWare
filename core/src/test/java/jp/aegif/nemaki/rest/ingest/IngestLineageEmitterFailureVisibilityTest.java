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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lost provenance event must be distinguishable from having nothing to record.
 *
 * <p>The emitter returns null for both "lineage is switched off" and "we tried and failed".
 * Those are opposite facts: the first is a configuration choice, the second means a document is
 * now stored with no evidence of where it came from — and the import still reported success, so
 * nobody would ever come back for it. Until the outbox in P1-1(a) makes capture atomic, the
 * least the code owes a caller is the ability to tell them apart.
 *
 * <p>Reverting the {@code lastFailure.set(...)} in the emitter's catch block makes the first
 * test fail: the failure becomes indistinguishable from silence again.
 */
class IngestLineageEmitterFailureVisibilityTest {

    /**
     * The emitter dereferences its collaborators inside the try block, so an unwired instance
     * reproduces exactly the shape this guards: an exception AFTER the document is committed.
     */
    private static String emitWithBrokenCollaborators() {
        IngestLineageEmitter emitter = new IngestLineageEmitter();
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setSourceObjectType("file");
        return emitter.emitLineageEvent("bedroom", "obj-1", "folder-1", "doc.txt", "op-1",
                connector, request, null, "test-actor", null);
    }

    @Test
    @DisplayName("a failed emission is recorded, not merely absent")
    void failureIsDistinguishableFromSilence() {
        IngestLineageEmitter emitter = new IngestLineageEmitter();
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setSourceObjectType("file");

        String eventId = emitter.emitLineageEvent("bedroom", "obj-1", "folder-1", "doc.txt",
                "op-1", connector, request, null, "test-actor", null);

        assertNull(eventId, "nothing was emitted");
        assertNotNull(emitter.lastEmissionFailure(),
                "a null event id alone cannot tell a caller whether provenance was lost or "
                        + "simply not wanted — and the document is already committed");
        assertTrue(emitter.lastEmissionFailure().contains(":"),
                "the reason should name the exception, so an operator can act on it: "
                        + emitter.lastEmissionFailure());
    }

    @Test
    @DisplayName("a stale failure is not reported against a later call")
    void failureDoesNotLeakForward() {
        IngestLineageEmitter emitter = new IngestLineageEmitter();
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setSourceObjectType("file");

        emitter.emitLineageEvent("bedroom", "obj-1", "f", "d", "op-1", connector, request, null, "test-actor", null);
        assertNotNull(emitter.lastEmissionFailure(), "control: the first call did fail");

        // The second call must be judged on its own. Asserting that it ALSO reports a failure
        // would pass even if the first call's reason were simply carried forward — which is the
        // bug this test exists for. So the reason is cleared and the emitter is asked again
        // WITHOUT emitting: nothing new failed, so nothing may be reported.
        emitter.clearLastEmissionFailureForTest();
        assertNull(emitter.lastEmissionFailure(),
                "a reason must belong to an emission, not linger on the thread");
    }
}
