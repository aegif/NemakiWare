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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
     * Right repository, wrong kind. A prefix match would pass this: the endpoint declares itself a
     * document while its name is an archive's, and the sink would create a {@code nemaki_document}
     * under an archive's qualified name.
     */
    @Test
    public void aQualifiedNameDisagreeingWithItsOwnKindIsRejected() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                LineageEndpoint.archiveQualifiedName(BEDROOM, "a"), BEDROOM, "a", null,
                Map.of("name", "n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    /** Same trick with the object id: the name must be the one this endpoint's fields imply. */
    @Test
    public void aQualifiedNameNamingAnotherObjectIsRejected() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                LineageEndpoint.objectQualifiedName(BEDROOM, "someone-elses-id"), BEDROOM, "a",
                null, Map.of("name", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
    }

    /** And with the operation an artifact belongs to. */
    @Test
    public void anArtifactQualifiedNameNamingAnotherOperationIsRejected() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.EXPORT_ARTIFACT,
                LineageEndpoint.exportArtifactQualifiedName(BEDROOM, "op-2"), BEDROOM, null,
                "op-1", Map.of("artifactKind", "ZIP"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(), List.of(forged)));
    }

    /** Every factory-built endpoint must satisfy the check its own kind implies. */
    @Test
    public void everyFactoryProducesAConsistentQualifiedName() {
        List<LineageEndpoint> all = List.of(
                LineageEndpoint.document(BEDROOM, "d-1", "n"),
                LineageEndpoint.folder(BEDROOM, "f-1", "n"),
                LineageEndpoint.archive(BEDROOM, "a-1", "d-1", 1767225600000L),
                LineageEndpoint.externalAsset(BEDROOM, "https://ext/1", "confluence"),
                LineageEndpoint.cloudObject(BEDROOM, "gdrive", "file-1"),
                LineageEndpoint.coldStorage(BEDROOM, "s3://b/k", "GLACIER"),
                LineageEndpoint.filesystemPath(BEDROOM, "/srv/in/a.pdf"),
                LineageEndpoint.importArtifact(BEDROOM, "op-1", "MANAGED", null),
                LineageEndpoint.exportArtifact(BEDROOM, "op-1", "ZIP", "e.zip", 1L));
        // every factory-built name must also survive the exact recomputation
        for (LineageEndpoint endpoint : all) {
            assertDoesNotThrow(() -> LineageRepositoryScope.validateEndpoint(BEDROOM, endpoint,
                    "input"), endpoint.kind().toString());
        }
        assertDoesNotThrow(() -> LineageRepositoryScope.validate(BEDROOM, all, List.of()));
        assertDoesNotThrow(() -> LineageRepositoryScope.validateArtifactOperation("op-1", all,
                List.of()));
    }

    /**
     * The external case, which a prefix match could never catch: the name is built from one stable
     * key while the attribute holds another.
     *
     * <p>Both halves are used, by different readers. Atlas resolves the entity by qualified name;
     * a snapshot reader — and increment A-2's digest — reads the attribute. An endpoint like this
     * describes two different assets at once and looks well-formed from either side alone.
     */
    @Test
    public void anExternalNameBuiltFromADifferentStableKeyIsRejected() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(BEDROOM, "https://ext/A"), BEDROOM,
                null, null,
                Map.of("sourceSystem", "confluence",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "https://ext/B"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    /**
     * And the message must not carry either name. The qualified name is reversible base64 of the
     * stable key, so repeating it in an exception puts the key in every log that catches it.
     */
    @Test
    public void anExternalNameIsNotReproducedInTheMessage() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CLOUD_OBJECT,
                LineageEndpoint.externalAssetQualifiedName(BEDROOM, "gdrive:SECRET-A"),
                BEDROOM, null, null,
                Map.of("sourceSystem", "gdrive",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "gdrive:SECRET-B"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));

        String encodedA = LineageEndpoint.externalAssetQualifiedName(BEDROOM,
                "gdrive:SECRET-A").substring(
                        LineageEndpoint.externalAssetQualifiedNamePrefix(BEDROOM).length());
        assertFalse(e.getMessage().contains(encodedA),
                "the encoded stable key reached the message: " + e.getMessage());
        assertTrue(e.getMessage().contains("redacted"), e.getMessage());
        assertTrue(e.getMessage().contains("kind=CLOUD_OBJECT"),
                "the kind is what the message identifies instead: " + e.getMessage());
    }

    /** The redaction still has to identify which name it stood for, or a log cannot be read. */
    @Test
    public void theRedactionDistinguishesDifferentNames() {
        assertNotEquals(messageFor("gdrive:A"), messageFor("gdrive:B"));
    }

    private static String messageFor(String stableKey) {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CLOUD_OBJECT,
                LineageEndpoint.externalAssetQualifiedName(BEDROOM, stableKey), BEDROOM, null,
                null, Map.of("sourceSystem", "gdrive",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "gdrive:OTHER"));
        return assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()))
                .getMessage();
    }

    /**
     * The redaction must not assume the name is well-formed. A hand-crafted document is exactly
     * where a name would not start with {@code nemaki://}, and it is exactly the case where
     * echoing it back into a log is worst.
     */
    @Test
    public void aMalformedExternalNameIsStillRedacted() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                "/external-assets/SECRETVALUE", BEDROOM, null, null,
                Map.of("sourceSystem", "confluence",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "https://ext/1"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
        assertFalse(e.getMessage().contains("SECRETVALUE"), e.getMessage());
    }

    /** A CMIS name carries an object id, not a secret, so it stays legible in the message. */
    @Test
    public void aCmisNameIsShownInFullBecauseItCarriesNoSecret() {
        LineageEndpoint forged = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                LineageEndpoint.objectQualifiedName(BEDROOM, "other-id"), BEDROOM, "a", null,
                Map.of("name", "n"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validate(BEDROOM, List.of(forged), List.of()));
        assertTrue(e.getMessage().contains("nemaki://bedroom/objects/a"), e.getMessage());
    }

    /**
     * The null endpoint itself, not just the null list.
     *
     * <p>This branch used to {@code return} rather than throw, which made a broken list report a
     * clean check — and a negated-conditional mutant is killed by the non-null path alone, so
     * mutation coverage did not stand in for asserting it.
     */
    @Test
    public void aNullEndpointIsRejectedByTheArtifactCheckToo() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1",
                        (LineageEndpoint) null, "input"));

        List<LineageEndpoint> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", withNull,
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageRepositoryScope.validateArtifactOperation("op-1", List.of(),
                        withNull));
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
                3L);
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
