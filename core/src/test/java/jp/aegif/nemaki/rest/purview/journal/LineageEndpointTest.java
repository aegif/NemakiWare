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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
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
                    () -> new LineageEndpoint(kind, "nemaki://x/objects/1", null,
                            identityFor(kind, EndpointKind.Identity.OBJECT_ID),
                            identityFor(kind, EndpointKind.Identity.OPERATION_ID),
                            requiredAttributesFor(kind)),
                    kind + " accepted a null repositoryId");
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageEndpoint(kind, "nemaki://x/objects/1", " ",
                            identityFor(kind, EndpointKind.Identity.OBJECT_ID),
                            identityFor(kind, EndpointKind.Identity.OPERATION_ID),
                            requiredAttributesFor(kind)),
                    kind + " accepted a blank repositoryId");
        }
    }

    private static String identityFor(EndpointKind kind, EndpointKind.Identity wanted) {
        return kind.identity() == wanted ? "1" : null;
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
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.IMPORT_ARTIFACT, "nemaki://bedroom/imports/x", REPO, null, " ",
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
    /**
     * The declared contract, spelled out. A loop that only walks whatever the enum happens to
     * declare cannot notice an attribute being dropped from a kind — the loop just gets shorter.
     */
    @Test
    public void eachKindDeclaresTheAttributesItsCatalogTypeNeeds() {
        assertEquals(List.of("name"), EndpointKind.CMIS_DOCUMENT.requiredAttributes());
        assertEquals(List.of("name", "mimeType", "contentLength", "versionLabel"),
                EndpointKind.CMIS_DOCUMENT.allowedAttributes());
        assertEquals(List.of("name"), EndpointKind.CMIS_FOLDER.requiredAttributes());
        assertEquals(List.of("archivedAt"), EndpointKind.ARCHIVE.requiredAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey"),
                EndpointKind.EXTERNAL_ASSET.requiredAttributes());
        assertEquals(List.of("provider", "externalStableKey"),
                EndpointKind.CLOUD_OBJECT.requiredAttributes());
        assertEquals(List.of("storageClass", "externalStableKey"),
                EndpointKind.COLD_STORAGE.requiredAttributes());
        assertEquals(List.of("importMode"), EndpointKind.IMPORT_ARTIFACT.requiredAttributes());
        assertEquals(List.of("artifactKind"), EndpointKind.EXPORT_ARTIFACT.requiredAttributes());

        // the stable key is required, not merely allowed: it is what the name is rebuilt from
        for (EndpointKind kind : EndpointKind.values()) {
            if (kind.identity() == EndpointKind.Identity.STABLE_KEY) {
                assertTrue(kind.requiredAttributes().contains(kind.identityAttribute()),
                        kind + " names itself by an attribute it does not require");
            }
            assertFalse(kind.isAllowedAttribute("notDeclaredAnywhere"),
                    kind + " allows an undeclared attribute");
        }
    }

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

    // ------------------------------------------------------------------
    // Attribute types, not just names
    // ------------------------------------------------------------------

    /**
     * An allowlist of names alone accepted {@code name=123}. The catalog would store something
     * nobody meant, and A-2's digest would hash a value whose type depends on which producer
     * wrote it — so the same fact from two code paths would digest differently.
     */
    @Test
    public void aTextAttributeMustBeText() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1", Map.of("name", 123)));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.document(REPO, "d-1",
                        Map.of("name", "n", "mimeType", Boolean.TRUE)));
    }

    /** And a count must be a whole number, not a numeric-looking String. */
    @Test
    public void aCountAttributeMustBeAWholeNumber() {
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", "1024")));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", 1024.5d)));

        assertEquals(1024L, LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", 1024L)).attributes().get("contentLength"));
        assertEquals(1024, LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", 1024)).attributes().get("contentLength"));
    }

    /** A negative byte count or object count is not a smaller number; it is a broken producer. */
    @Test
    public void aCountAttributeMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", -1L)));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.exportArtifact(REPO, "op-1", "ZIP", "e.zip", -1));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.importArtifact(REPO,
                "op-1", "MANAGED", Map.of("byteLength", -1L)));

        // zero is a real value: an empty file and an export of nothing both happen
        assertDoesNotThrow(() -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "contentLength", 0L)));
        assertDoesNotThrow(() -> LineageEndpoint.exportArtifact(REPO, "op-1", "ZIP", null, 0));
    }

    /**
     * Only immutable scalars. {@code Map.copyOf} is a shallow copy, so a {@code List} value would
     * stay mutable after the endpoint was built and the snapshot would change under an event that
     * had already been emitted.
     */
    @Test
    public void aCollectionValuedAttributeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", List.of("n"))));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.document(REPO, "d-1",
                Map.of("name", "n", "mimeType", Map.of("a", "b"))));
    }

    /**
     * The factory's own arguments win, because only one of the two can be the truth and silently
     * discarding the explicit one is the worse of the two failures.
     */
    @Test
    public void extrasMayNotOverwriteWhatTheFactorySets() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.importArtifact(REPO, "op-1", "MANAGED",
                        Map.of("importMode", "SOMETHING_ELSE")));
        assertTrue(e.getMessage().contains("must not be passed in extras"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Identity fields are present exactly when the kind uses them
    // ------------------------------------------------------------------

    /**
     * A stray {@code objectId} on an external endpoint is not spare data. Nothing reads it, so it
     * would sit in the journal looking like identity while the name was built from the stable key.
     */
    @Test
    public void anIdentityFieldTheKindDoesNotUseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.EXTERNAL_ASSET,
                LineageEndpoint.externalAssetQualifiedName(REPO, "k"), REPO, "stray-object-id",
                null, Map.of("sourceSystem", "s",
                        LineageEndpoint.ATTR_EXTERNAL_STABLE_KEY, "k")));
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.CMIS_DOCUMENT, LineageEndpoint.objectQualifiedName(REPO, "d-1"),
                REPO, "d-1", "stray-operation-id", Map.of("name", "n")));
        assertThrows(IllegalArgumentException.class, () -> new LineageEndpoint(
                EndpointKind.IMPORT_ARTIFACT,
                LineageEndpoint.importArtifactQualifiedName(REPO, "op-1"), REPO,
                "stray-object-id", "op-1", Map.of("importMode", "MANAGED")));
    }

    // ------------------------------------------------------------------
    // What may go into a stable key
    // ------------------------------------------------------------------

    /**
     * The qualified name is reversible base64, so a signed URL in a stable key is a working
     * credential recoverable from any catalog entity. The design makes stripping it the producer's
     * job; this is the check that the producer did it.
     */
    @Test
    public void aStableKeyCarryingCredentialsOrQueryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "https://blob.example/doc?sig=SECRET&se=2026", "azure", null));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "https://ext/doc#section-2", "confluence", null));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "https://user:pass@ext/doc", "confluence", null));
        // Embedded, not trailing: trim() already removes anything at the ends.
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "https://ext/do" + (char) 7 + "c", "confluence", null));

        assertDoesNotThrow(() -> LineageEndpoint.externalAsset(REPO,
                "https://ext/spaces/ENG/pages/12345", "confluence", null));
    }

    /**
     * Only the authority is searched for credentials. A mailbox path legitimately contains "@",
     * and rejecting it would make mail-connector lineage unrepresentable.
     */
    @Test
    public void anAtSignInThePathIsNotCredentials() {
        assertDoesNotThrow(() -> LineageEndpoint.externalAsset(REPO,
                "https://ext/users/a@b.example/items/9", "exchange", null));
        assertDoesNotThrow(() -> LineageEndpoint.externalAsset(REPO,
                "mailbox://host/a@b.example/9", "exchange", null));
    }

    /** A stable key need not be a URI at all, and one with no path is still a whole key. */
    @Test
    public void aStableKeyWithoutASchemeOrPathIsAccepted() {
        assertDoesNotThrow(() -> LineageEndpoint.externalAsset(REPO, "opaque-connector-key-123",
                "confluence", null));
        assertDoesNotThrow(() -> LineageEndpoint.externalAsset(REPO, "cloud://gdrive",
                "gdrive", null));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "cloud://user:pw@gdrive", "gdrive", null));
    }

    /**
     * The edges of the parse. Each of these is a position where an off-by-one in the scheme or
     * authority scan changes the verdict rather than the message.
     */
    @Test
    public void theStableKeyParserHandlesTheEdgesOfItsOwnScan() {
        // the forbidden character at index 0, where a "> 0" test would miss it
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, "?everything", "x", null));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, "#everything", "x", null));

        // a scheme separator at index 0: there is no scheme, but there is still an authority
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, "://user:pw@host/x", "x", null));

        // empty userinfo is still userinfo
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.externalAsset(REPO, "https://@host/x", "x", null));

        // a two-character scheme, where a shifted window would run off the front of the string
        assertDoesNotThrow(
                () -> LineageEndpoint.externalAsset(REPO, "s3://bucket/a@b", "s3", null));

        // whatever precedes the scheme separator is not the authority — a stable key need not be
        // a URI, and an "@" outside the authority is not a credential
        assertDoesNotThrow(
                () -> LineageEndpoint.externalAsset(REPO, "a@b://host", "connector", null));
        assertDoesNotThrow(
                () -> LineageEndpoint.externalAsset(REPO, "user@tenant/item-9", "x", null));
    }

    /** {@code trim} removes only the ends, so a control character elsewhere must be caught. */
    @Test
    public void aControlCharacterAnywhereIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                (char) 127 + "https://ext/doc", "confluence", null));
        assertThrows(IllegalArgumentException.class, () -> LineageEndpoint.externalAsset(REPO,
                "https://ext/doc" + (char) 127, "confluence", null));
    }

    /**
     * {@code /srv/in/./a.pdf} and {@code /srv/in/b/../a.pdf} are the same file, and two names for
     * one file are two assets in the catalog.
     */
    @Test
    public void aFilesystemPathIsNormalised() {
        String expected = LineageEndpoint.filesystemPath(REPO, "/srv/in/a.pdf")
                .catalogQualifiedName();
        assertEquals(expected,
                LineageEndpoint.filesystemPath(REPO, "/srv/in/./a.pdf").catalogQualifiedName());
        assertEquals(expected,
                LineageEndpoint.filesystemPath(REPO, "/srv/in/b/../a.pdf").catalogQualifiedName());
    }

    /** A relative path names a different file depending on who emitted it. */
    @Test
    public void aRelativeFilesystemPathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEndpoint.filesystemPath(REPO, "in/a.pdf"));
    }

    /**
     * The kinds occupy disjoint name spaces. If two kinds could produce one name for the same id,
     * a document could be published under a folder's identity, and the exact-name check in
     * {@link LineageRepositoryScope} would have nothing to catch it with.
     */
    @Test
    public void theKindsCannotProduceTheSameQualifiedName() {
        Map<String, EndpointKind> seen = new HashMap<>();
        for (LineageEndpoint endpoint : oneOfEachKind()) {
            if (endpoint.kind().identity() == EndpointKind.Identity.STABLE_KEY) {
                continue; // the external kinds share one name space by design; see above
            }
            String name = endpoint.catalogQualifiedName();
            EndpointKind clash = seen.put(name, endpoint.kind());
            assertEquals(null, clash,
                    endpoint.kind() + " and " + clash + " both name '" + name + "'");
        }
    }

    /** One well-formed endpoint per kind, for the loops that need to cover all of them. */
    static List<LineageEndpoint> oneOfEachKind() {
        return List.of(
                LineageEndpoint.document(REPO, "same-id", "n"),
                LineageEndpoint.folder(REPO, "same-id", "n"),
                LineageEndpoint.archive(REPO, "same-id", null, "2026-01-01T00:00:00Z"),
                LineageEndpoint.externalAsset(REPO, "https://ext/1", "confluence", null),
                LineageEndpoint.cloudObject(REPO, "gdrive", "file-1"),
                LineageEndpoint.coldStorage(REPO, "s3://b/k", "GLACIER"),
                LineageEndpoint.importArtifact(REPO, "same-id", "MANAGED", null),
                LineageEndpoint.exportArtifact(REPO, "same-id", "ZIP", null, null));
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
            attributes.put(required, kind.attribute(required).type()
                    == EndpointAttribute.Type.COUNT ? 1L : "x");
        }
        return attributes;
    }
}
