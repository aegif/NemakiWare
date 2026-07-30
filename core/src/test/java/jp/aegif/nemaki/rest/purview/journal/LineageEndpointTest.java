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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * What an endpoint must carry before it is allowed to become a catalog reference.
 *
 * <p>Endpoints used to be bare strings, and the sink referenced all of them as {@code DataSet};
 * for folders and for anything external that type does not exist. These tests hold the boundary
 * where the producer's knowledge — "this is a folder", "this came from a cloud drive" — is
 * captured instead of discarded.
 */
public class LineageEndpointTest {

    private static final String REPO = "bedroom";

    // ------------------------------------------------------------------
    // The type the catalog will see
    // ------------------------------------------------------------------

    /**
     * {@code nemaki_folder} extends {@code Referenceable}, which {@code Process.inputs} does not
     * accept, so a folder endpoint must resolve to its DataSet proxy rather than to the folder.
     */
    @Test
    public void aFolderResolvesToItsDataSetProxyNotToTheFolderType() {
        LineageEndpoint folder = LineageEndpoint.folder(REPO, "f-1", "Contracts");
        assertEquals(EndpointKind.CMIS_FOLDER, folder.kind());
        assertEquals("nemaki_folder_dataset", folder.kind().atlasTypeName());
        assertEquals("nemaki://bedroom/folders/f-1/dataset", folder.catalogQualifiedName());
    }

    @Test
    public void aDocumentResolvesToTheDocumentType() {
        LineageEndpoint document = LineageEndpoint.document(REPO, "d-1", "contract.pdf");
        assertEquals("nemaki_document", document.kind().atlasTypeName());
        assertEquals("nemaki://bedroom/objects/d-1", document.catalogQualifiedName());
    }

    /** No kind resolves to bare {@code DataSet}: an abstract type would be satisfied by a shell. */
    @Test
    public void noKindResolvesToBareDataSet() {
        for (EndpointKind kind : EndpointKind.values()) {
            assertTrue(kind.atlasTypeName().startsWith("nemaki_"),
                    kind + " resolves to " + kind.atlasTypeName());
        }
    }

    // ------------------------------------------------------------------
    // repositoryId is required everywhere, including on external kinds
    // ------------------------------------------------------------------

    /**
     * An external asset lives outside the repository, but the claim "this repository imported that
     * thing" belongs to one, and the qualified name embeds it. Leaving it null here is what let an
     * injected event reference another repository's external asset and pass every check.
     */
    @Test
    public void everyKindRequiresARepositoryId() {
        for (EndpointKind kind : EndpointKind.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageEndpoint(kind, "nemaki://x/objects/1", null, "1", "op",
                            requiredAttributesFor(kind)),
                    kind + " accepted a null repositoryId");
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageEndpoint(kind, "nemaki://x/objects/1", " ", "1", "op",
                            requiredAttributesFor(kind)),
                    kind + " accepted a blank repositoryId");
        }
    }

    @Test
    public void externalKindsCarryTheRepositoryInTheirQualifiedName() {
        assertTrue(LineageEndpoint.cloudObject(REPO, "gdrive", "file-1")
                .catalogQualifiedName().startsWith("nemaki://bedroom/external-assets/"));
        assertTrue(LineageEndpoint.coldStorage(REPO, "s3://bucket/key", "GLACIER")
                .catalogQualifiedName().startsWith("nemaki://bedroom/external-assets/"));
        assertTrue(LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf")
                .catalogQualifiedName().startsWith("nemaki://bedroom/external-assets/"));
    }

    /**
     * The three external kinds are three ways of describing the same thing, and they resolve to
     * one Atlas type. A cloud object reached through the cloud-drive factory and the same object
     * described as a generic external asset must therefore be one entity, not two.
     */
    @Test
    public void theExternalKindsAgreeOnTheNameOfTheSameAsset() {
        String stableKey = "cloud://gdrive/file-1";
        assertEquals(
                LineageEndpoint.externalAsset(REPO, stableKey, "gdrive", null)
                        .catalogQualifiedName(),
                LineageEndpoint.cloudObject(REPO, "gdrive", "file-1").catalogQualifiedName());
        assertEquals("nemaki_external_asset", EndpointKind.CLOUD_OBJECT.atlasTypeName());
        assertEquals("nemaki_external_asset", EndpointKind.COLD_STORAGE.atlasTypeName());
        assertEquals("nemaki_external_asset", EndpointKind.EXTERNAL_ASSET.atlasTypeName());
    }

    /**
     * The lineage path and the catalog sync path must name the same external asset identically, or
     * one asset becomes two entities and the lineage attaches to the wrong one.
     */
    @Test
    public void externalAssetNamingMatchesThePurviewSyncPath() {
        String stableKey = "cloud://gdrive/file-1";
        assertEquals(
                new PurviewEntityPayloadFactory().buildExternalAssetQualifiedName(REPO, stableKey),
                LineageEndpoint.externalAssetQualifiedName(REPO, stableKey));
    }

    // ------------------------------------------------------------------
    // Identity fields, per kind
    // ------------------------------------------------------------------

    @Test
    public void cmisKindsRequireAnObjectId() {
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.CMIS_DOCUMENT, "nemaki://bedroom/objects/x", REPO, null, null,
                Map.of("name", "n")));
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.ARCHIVE, "nemaki://bedroom/archives/x", REPO, " ", null,
                Map.of("archivedAt", "2026-01-01T00:00:00Z")));
    }

    /** An artifact's qualified name is built from its operation, so it cannot be absent. */
    @Test
    public void artifactKindsRequireAnOperationId() {
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.IMPORT_ARTIFACT, "nemaki://bedroom/imports/x", REPO, null, null,
                Map.of("importMode", "MANAGED")));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.exportArtifact(REPO, " ", "ZIP", "e.zip", 3));
    }

    @Test
    public void artifactQualifiedNamesAreBuiltFromTheOperation() {
        assertEquals("nemaki://bedroom/imports/op-9",
                LineageEndpoint.importArtifact(REPO, "op-9", "MANAGED", null)
                        .catalogQualifiedName());
        assertEquals("nemaki://bedroom/exports/op-9",
                LineageEndpoint.exportArtifact(REPO, "op-9", "ZIP", "e.zip", 3)
                        .catalogQualifiedName());
    }

    // ------------------------------------------------------------------
    // Attributes: an allowlist, and a required set
    // ------------------------------------------------------------------

    /**
     * The catalog silently drops attributes its schema does not declare. Without an allowlist an
     * attribute could be sent on every event, discarded on arrival, and never missed.
     */
    @Test
    public void attributesOutsideTheAllowlistAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1",
                        Map.of("name", "n", "somethingElse", "x")));
        assertTrue(e.getMessage().contains("allowlist"), e.getMessage());
    }

    /** An attribute that is valid for one kind is not thereby valid for another. */
    @Test
    public void theAllowlistIsPerKind() {
        // mimeType is a document attribute; an archive has no such column
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.ARCHIVE, "nemaki://bedroom/archives/a-1", REPO, "a-1", null,
                Map.of("archivedAt", "2026-01-01T00:00:00Z", "mimeType", "application/pdf")));
    }

    /**
     * Without these the entity is indistinguishable from an Atlas shell — the exact failure that
     * made lineage look present while carrying nothing.
     */
    @Test
    public void requiredAttributesAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", (String) null));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", Map.of("mimeType", "application/pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.cloudObject(REPO, null, "file-1"));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.coldStorage(REPO, "s3://b/k", " "));
    }

    /** Blank counts as absent: an empty name conveys nothing the catalog can use. */
    @Test
    public void aBlankRequiredAttributeIsRejected() {
        Map<String, Object> blankName = new HashMap<>();
        blankName.put("name", " ");
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", blankName));

        Map<String, Object> nullName = new HashMap<>();
        nullName.put("name", null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", nullName));
    }

    /**
     * Attributes are a point-in-time snapshot, so the endpoint must not share the caller's map —
     * a later mutation would change what a already-emitted event claims to have seen.
     */
    @Test
    public void attributesAreCopiedNotAliased() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("name", "before");
        LineageEndpoint endpoint = LineageEndpoint.document(REPO, "d-1", attributes);
        attributes.put("name", "after");
        assertEquals("before", endpoint.attributes().get("name"));
        assertThrows(UnsupportedOperationException.class,
                () -> endpoint.attributes().put("name", "after"));
    }

    /**
     * {@code file://} is a resource outside the repository exactly as {@code cloud://} is, so it
     * takes the external-asset naming rule rather than a kind of its own.
     */
    @Test
    public void aFilesystemPathIsAnExternalAsset() {
        LineageEndpoint endpoint = LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf");
        assertEquals(EndpointKind.EXTERNAL_ASSET, endpoint.kind());
        assertEquals("filesystem", endpoint.attributes().get("sourceSystem"));
        assertEquals("file:///srv/in/a.pdf",
                endpoint.attributes().get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY));
    }

    // ------------------------------------------------------------------
    // Optional fields on the factories: present and absent
    // ------------------------------------------------------------------

    /**
     * {@code originalId} is what ties an archive back to the document it came from, and it is
     * absent for an archive whose original is already gone. Both shapes have to be constructible.
     */
    @Test
    public void anArchiveCarriesItsOriginalWhenThereIsOne() {
        LineageEndpoint withOriginal = LineageEndpoint.archive(REPO, "a-1", "d-1",
                "2026-01-01T00:00:00Z");
        assertEquals("nemaki://bedroom/archives/a-1", withOriginal.catalogQualifiedName());
        assertEquals("nemaki_archive", withOriginal.kind().atlasTypeName());
        assertEquals("d-1", withOriginal.attributes().get("originalId"));
        assertEquals("2026-01-01T00:00:00Z", withOriginal.attributes().get("archivedAt"));

        LineageEndpoint withoutOriginal = LineageEndpoint.archive(REPO, "a-1", null,
                "2026-01-01T00:00:00Z");
        assertFalse(withoutOriginal.attributes().containsKey("originalId"),
                "an absent original must be absent, not an empty string");
        assertFalse(LineageEndpoint.archive(REPO, "a-1", " ", "2026-01-01T00:00:00Z")
                .attributes().containsKey("originalId"));
    }

    /** {@code archivedAt} is the required one: without it the entity is an undated shell. */
    @Test
    public void anArchiveWithoutATimestampIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.archive(REPO, "a-1", "d-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.archive(REPO, "a-1", "d-1", " "));
    }

    @Test
    public void anExternalAssetCarriesItsTenantWhenThereIsOne() {
        LineageEndpoint multiTenant = LineageEndpoint.externalAsset(REPO, "https://ext/1",
                "confluence", "tenant-9");
        assertEquals("confluence", multiTenant.attributes().get("sourceSystem"));
        assertEquals("https://ext/1",
                multiTenant.attributes().get(LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY));
        assertEquals("tenant-9", multiTenant.attributes().get("tenantId"));

        assertFalse(LineageEndpoint.externalAsset(REPO, "https://ext/1", "confluence", null)
                .attributes().containsKey("tenantId"));
        assertFalse(LineageEndpoint.externalAsset(REPO, "https://ext/1", "confluence", " ")
                .attributes().containsKey("tenantId"));
    }

    @Test
    public void anExternalAssetNeedsBothASourceSystemAndAStableKey() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, "https://ext/1", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, " ", "confluence", null));
    }

    /** The connector's own attributes ride along; they are still subject to the allowlist. */
    @Test
    public void anImportArtifactMergesTheCallersExtraAttributes() {
        LineageEndpoint withExtras = LineageEndpoint.importArtifact(REPO, "op-9", "MANAGED",
                Map.of("byteLength", 1024L, "originalFileName", "in.zip"));
        assertEquals("MANAGED", withExtras.attributes().get("importMode"));
        assertEquals(1024L, withExtras.attributes().get("byteLength"));
        assertEquals("in.zip", withExtras.attributes().get("originalFileName"));

        assertEquals(Map.of("importMode", "MANAGED"),
                LineageEndpoint.importArtifact(REPO, "op-9", "MANAGED", null).attributes());

        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.importArtifact(
                REPO, "op-9", "MANAGED", Map.of("notDeclaredInTheSchema", "x")));
    }

    @Test
    public void anExportArtifactsNameAndCountAreOptional() {
        LineageEndpoint full = LineageEndpoint.exportArtifact(REPO, "op-9", "ZIP", "e.zip", 3);
        assertEquals("ZIP", full.attributes().get("artifactKind"));
        assertEquals("e.zip", full.attributes().get("name"));
        assertEquals(3, full.attributes().get("objectCount"));

        LineageEndpoint bare = LineageEndpoint.exportArtifact(REPO, "op-9", "DIRECTORY", null,
                null);
        assertFalse(bare.attributes().containsKey("name"));
        assertFalse(bare.attributes().containsKey("objectCount"));
        assertFalse(LineageEndpoint.exportArtifact(REPO, "op-9", "ZIP", " ", null)
                .attributes().containsKey("name"));
    }

    /**
     * A required attribute outside its own allowlist would make the kind unconstructible — the
     * required check demands it and the allowlist check rejects it.
     */
    @Test
    public void everyRequiredAttributeIsAlsoAllowed() {
        for (EndpointKind kind : EndpointKind.values()) {
            assertFalse(kind.allowedAttributes().isEmpty(),
                    kind + " allows no attributes at all");
            for (String required : kind.requiredAttributes()) {
                assertTrue(kind.isAllowedAttribute(required),
                        kind + " requires '" + required + "' but does not allow it");
                assertTrue(kind.allowedAttributes().contains(required),
                        kind + " reports an allowlist that omits its own required '" + required
                                + "'");
            }
        }
    }

    /**
     * The kinds occupy disjoint name spaces. If two kinds could produce one name for the same id,
     * a document could be published under a folder's identity, and the exact-name check in
     * {@link LineageRepositoryScope} would have nothing to catch it with.
     */
    @Test
    public void theKindsCannotProduceTheSameQualifiedName() {
        Map<String, EndpointKind> seen = new HashMap<>();
        for (EndpointKind kind : EndpointKind.values()) {
            String name = LineageEndpoint.expectedQualifiedName(kind, REPO, "same-id", "same-id");
            if (name == null) {
                continue; // external kinds share one name space by design; see the test above
            }
            EndpointKind clash = seen.put(name, kind);
            assertEquals(null, clash, kind + " and " + clash + " both name '" + name + "'");
        }
    }

    @Test
    public void kindIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                null, "nemaki://bedroom/objects/d-1", REPO, "d-1", null, Map.of()));
    }

    /** Minimum attribute map that satisfies a kind, for the loops above. */
    private static Map<String, Object> requiredAttributesFor(EndpointKind kind) {
        Map<String, Object> attributes = new HashMap<>();
        for (String required : kind.requiredAttributes()) {
            attributes.put(required, "x");
        }
        return attributes;
    }
}
