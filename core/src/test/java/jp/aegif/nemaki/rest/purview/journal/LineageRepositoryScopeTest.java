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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The tenant boundary, and the operation boundary inside it.
 *
 * <p>Both are checked more than once on the way to the catalog — builder, store, legacy reader and
 * the call immediately before publish — because an event injected straight into CouchDB or mapped
 * from a v1 record never passes through the builder. These tests exercise the shared check those
 * four call sites use.
 */
public class LineageRepositoryScopeTest {

    private static final String BEDROOM = "bedroom";
    private static final String CANOPY = "canopy";

    @Test
    public void endpointsInTheEventsOwnRepositoryPass() {
        assertDoesNotThrow(() -> LineageRepositoryScope.validate(BEDROOM,
                List.of(LineageEndpoint.document(BEDROOM, "a", "n")),
                List.of(LineageEndpoint.folder(BEDROOM, "f", "Contracts"))));
    }

    @Test
    public void anInputFromAnotherRepositoryIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM,
                        List.of(LineageEndpoint.document(CANOPY, "a", "n")), List.of()));
        assertTrue(e.getMessage().contains("cross-repository"), e.getMessage());
    }

    @Test
    public void anOutputFromAnotherRepositoryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(),
                        List.of(LineageEndpoint.document(CANOPY, "a", "n"))));
    }

    /**
     * The field and the name are checked separately on purpose. A hand-crafted document can set
     * {@code repositoryId} to the event's repository while the qualified name — which is what the
     * catalog actually resolves — points somewhere else.
     */
    @Test
    public void aQualifiedNameDisagreeingWithTheFieldIsRejected() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                LineageEndpoint.objectQualifiedName(CANOPY, "a"), BEDROOM, "a", null,
                Map.of("name", "n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
        assertTrue(e.getMessage().contains("qualified name"), e.getMessage());
    }

    /**
     * A repository whose name merely starts with the event's must not satisfy the name check —
     * this is what the trailing slash in the expected prefix is for.
     */
    @Test
    public void aRepositoryNameThatOnlyStartsWithTheEventsIsNotIt() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                "nemaki://bedroom-archive/objects/a", BEDROOM, "a", null, Map.of("name", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM,
                        List.of(LineageEndpoint.document("bedroom-archive", "a", "n")),
                        List.of()));
    }

    @Test
    public void aBlankEventRepositoryIsRejected() {
        List<LineageEndpoint> inputs = List.of(LineageEndpoint.document(BEDROOM, "a", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(null, inputs, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(" ", inputs, List.of()));
    }

    /** Treating a null list as empty would let a mapping error skip the check and report success. */
    @Test
    public void aNullEndpointListIsRejectedRatherThanTreatedAsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", List.of(), null));
    }

    @Test
    public void aNullEndpointIsRejected() {
        List<LineageEndpoint> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, withNull, List.of()));
    }

    // ------------------------------------------------------------------
    // Artifact endpoints must belong to the event's own operation
    // ------------------------------------------------------------------

    /**
     * {@code nemaki://bedroom/imports/op-2} is a perfectly valid endpoint of the right repository.
     * It is still the wrong provenance for an event about {@code op-1}, and the repository check
     * alone would let it through.
     */
    @Test
    public void anArtifactFromAnotherOperationIsRejected() {
        LineageEndpoint artifact = LineageEndpoint.importArtifact(BEDROOM, "op-2", "MANAGED", null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", List.of(artifact),
                        List.of()));
        assertTrue(e.getMessage().contains("op-2") && e.getMessage().contains("op-1"),
                e.getMessage());

        assertDoesNotThrow(() -> LineageRepositoryScope.validateArtifactOperation("op-2",
                List.of(artifact), List.of()));
    }

    @Test
    public void anExportArtifactIsCheckedOnTheOutputSideToo() {
        LineageEndpoint artifact = LineageEndpoint.exportArtifact(BEDROOM, "op-2", "ZIP", "e.zip",
                3);
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", List.of(),
                        List.of(artifact)));
    }

    /**
     * An artifact endpoint without an event operation is not "unconstrained" — it is an artifact
     * whose provenance cannot be checked at all, which is the case the rule exists for.
     */
    @Test
    public void anArtifactWithoutAnEventOperationIsRejected() {
        LineageEndpoint artifact = LineageEndpoint.importArtifact(BEDROOM, "op-2", "MANAGED", null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation(null, List.of(artifact),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation(" ", List.of(artifact),
                        List.of()));
    }

    /** Non-artifact endpoints have no operation, so an event without one is fine. */
    @Test
    public void nonArtifactEndpointsAreUnaffectedByTheOperationCheck() {
        assertDoesNotThrow(() -> LineageRepositoryScope.validateArtifactOperation(null,
                List.of(LineageEndpoint.document(BEDROOM, "a", "n")),
                List.of(LineageEndpoint.cloudObject(BEDROOM, "gdrive", "f-1"))));
    }
}
