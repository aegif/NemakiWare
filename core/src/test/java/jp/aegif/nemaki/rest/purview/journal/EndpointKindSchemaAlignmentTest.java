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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;

/**
 * {@link EndpointKind}'s attribute table against the Atlas types that actually exist.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The allowlist's whole justification is that the catalog silently drops attributes its schema
 * does not declare — so an attribute outside the schema is sent, discarded, and never missed. The
 * first version of the table declared five such attributes ({@code mimeType},
 * {@code contentLength}, {@code provider}, {@code storageClass}, {@code tenantId}), one wrong type
 * ({@code archivedAt} as text against a {@code long} column) and one wrong name
 * ({@code originalId} against {@code originalObjectId}). Every one of those would have travelled
 * on every event and arrived nowhere.
 *
 * <p>Nothing compared the two tables, and no unit test could: both sides were self-consistent.
 * This is that comparison, done mechanically so it stays true.
 */
public class EndpointKindSchemaAlignmentTest {

    /**
     * Attributes every Atlas {@code DataSet} inherits and no {@code entityDef} repeats.
     *
     * <p>{@code Referenceable} contributes {@code qualifiedName}; {@code Asset} contributes the
     * rest.
     */
    private static final Set<String> ATLAS_BASE_ATTRIBUTES =
            Set.of("qualifiedName", "name", "description", "owner");

    /**
     * Kinds whose Atlas type increment B still has to create — now none (v2.3.30).
     *
     * <p>Kept, empty, rather than deleted with its assertion: it is the check that every kind's
     * Atlas type exists, and an empty expected set is the strongest form of it. A kind added
     * later without a type fails here rather than passing a loop that skips what it cannot find.
     */
    private static final Set<EndpointKind> AWAITING_SCHEMA = Set.of();

    @Test
    public void everyDeclaredAttributeExistsInTheAtlasType() {
        Map<String, Map<String, String>> schema = atlasTypes();
        for (EndpointKind kind : EndpointKind.values()) {
            Map<String, String> type = schema.get(kind.atlasTypeName());
            if (type == null) {
                continue; // covered by awaitingSchemaIsExactlyTheTypesIncrementBOwes
            }
            for (String attribute : kind.allowedAttributes()) {
                assertTrue(type.containsKey(attribute) || ATLAS_BASE_ATTRIBUTES.contains(attribute),
                        kind + " declares '" + attribute + "' but " + kind.atlasTypeName()
                                + " has no such attribute — Atlas would drop it silently."
                                + " Declared by the type: " + new TreeSet<>(type.keySet()));
            }
        }
    }

    /**
     * A {@code long} column will not hold a formatted timestamp, and a {@code string} column will
     * not hold a number in a form anything downstream can compare.
     */
    @Test
    public void declaredTypesMatchTheAtlasColumnTypes() {
        Map<String, Map<String, String>> schema = atlasTypes();
        for (EndpointKind kind : EndpointKind.values()) {
            Map<String, String> type = schema.get(kind.atlasTypeName());
            if (type == null) {
                continue;
            }
            for (String attribute : kind.allowedAttributes()) {
                String atlasType = type.get(attribute);
                if (atlasType == null) {
                    continue; // inherited from Asset; Atlas types them as string
                }
                EndpointAttribute.Type declared = kind.attribute(attribute).type();
                String expected = switch (declared) {
                    case TEXT -> "string";
                    case COUNT -> "long";
                };
                assertEquals(expected, atlasType,
                        kind + "." + attribute + " is declared " + declared + " here but "
                                + kind.atlasTypeName() + " types it " + atlasType);
            }
        }
    }

    /**
     * An attribute the Atlas type marks mandatory must be one this kind requires, or the entity
     * cannot be created from an endpoint at all.
     *
     * <p>The converse does not hold: a type may require attributes that are not endpoint snapshot
     * data ({@code archiveRepositoryId}, {@code lifecycleState}), and increment C's payload
     * factory supplies those from the event rather than from the endpoint. Only attributes this
     * kind claims to carry are checked.
     */
    @Test
    public void anAttributeThisKindCarriesIsRequiredIfTheTypeRequiresIt() {
        Map<String, Set<String>> mandatory = mandatoryAtlasAttributes();
        for (EndpointKind kind : EndpointKind.values()) {
            Set<String> required = mandatory.get(kind.atlasTypeName());
            if (required == null) {
                continue;
            }
            for (String attribute : kind.allowedAttributes()) {
                if (required.contains(attribute)) {
                    assertTrue(kind.requiredAttributes().contains(attribute),
                            kind + " allows '" + attribute + "' as optional, but "
                                    + kind.atlasTypeName() + " marks it mandatory — an endpoint"
                                    + " without it produces an entity Atlas refuses");
                }
            }
        }
    }

    @Test
    public void awaitingSchemaIsExactlyTheTypesIncrementBOwes() {
        Map<String, Map<String, String>> schema = atlasTypes();
        Set<EndpointKind> missing = new java.util.HashSet<>();
        for (EndpointKind kind : EndpointKind.values()) {
            if (!schema.containsKey(kind.atlasTypeName())) {
                missing.add(kind);
            }
        }
        assertEquals(AWAITING_SCHEMA, missing,
                "the set of kinds whose Atlas type does not exist has changed; if increment B"
                        + " created one, shorten AWAITING_SCHEMA so the alignment checks apply");
    }

    /**
     * The exact attribute set each kind declares.
     *
     * <p>Not "kinds sharing an Atlas type declare the same attributes", which was the earlier rule
     * and is wrong: after increment B the three external kinds keep one type and diverge —
     * {@code provider} on cloud, {@code storageClass} on cold, {@code tenantId} on the generic one.
     * Sharing a type constrains what may be declared, not what must be.
     *
     * <p>Spelled out rather than derived, because a loop over whatever the enum happens to declare
     * cannot notice an attribute disappearing; the loop just gets shorter.
     */
    @Test
    public void eachKindDeclaresExactlyTheseAttributes() {
        // The OriginalSha256 companions are §2's truncation evidence (v2.3.26): declared so
        // the value's shortening is never silent, and present in the Atlas type so the sink
        // is not handing over an attribute the catalog discards.
        assertEquals(List.of("name", "versionLabel", "folderPath",
                        "versionLabelOriginalSha256", "folderPathOriginalSha256",
                        "versionSeriesId",
                        // 増分 B (v2.3.31)
                        "mimeType", "contentLength",
                        "versionObjectId", "changeToken", "contentHash",
                        // P1-1(b): what the repository holds after an ingest. contentStored is
                        // three values as a STRING, not a boolean — a check-in with no stream
                        // carries the previous content forward, which is undetermined.
                        "contentStored", "contentHashAlgorithm", "contentStateReason"),
                EndpointKind.CMIS_DOCUMENT.allowedAttributes());
        assertEquals(List.of("name"), EndpointKind.CMIS_FOLDER.allowedAttributes());
        assertEquals(List.of("archivedAt", "originalObjectId", "name", "versionLabel",
                "nameOriginalSha256", "versionLabelOriginalSha256",
                "archiveState", "versionSeriesId"), EndpointKind.ARCHIVE.allowedAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey", "externalPath",
                        "tenantId", "sourceRevision", "sourceModifiedAt",
                        "sourceContentHash", "sourceContentLength",
                        // P1-1(b): the source facts the stable key does not already state.
                        // The CHANNEL is in the key and so is absent here; the workspace is not
                        // (the key's tenant segment is the connector's tenant id, and is dropped
                        // when it has none) and neither is an attachment's parent message.
                        "sourceObjectType", "chatWorkspaceId", "chatMessageId", "chatThreadId"),
                EndpointKind.EXTERNAL_ASSET.allowedAttributes());
        // no externalPath: the natural value is the drive URL, whose query string is where
        // sharing tokens live — unrepresentable until B defines a provider-canonical URL
        assertEquals(List.of("sourceSystem", "externalStableKey",
                        "provider", "sourceRevision", "sourceModifiedAt",
                        "sourceContentHash", "sourceContentLength"),
                EndpointKind.CLOUD_OBJECT.allowedAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey", "externalPath",
                        "storageClass"),
                EndpointKind.COLD_STORAGE.allowedAttributes());
        // originalFileName / name are display values and carry §2 companions (v2.3.29);
        // importMode, artifactKind and contentHash do not — machine state and a digest.
        assertEquals(List.of("importMode", "byteLength", "contentHash", "originalFileName",
                        "originalFileNameOriginalSha256"),
                EndpointKind.IMPORT_ARTIFACT.allowedAttributes());
        assertEquals(List.of("artifactKind", "objectCount", "name", "nameOriginalSha256"),
                EndpointKind.EXPORT_ARTIFACT.allowedAttributes());
    }

    /**
     * Attributes increment B still owes: the artifact manifest fields (v2.3.13).
     *
     * <p>The other direction of the alignment check.
     * {@code everyDeclaredAttributeExistsInTheAtlasType} catches an attribute declared in a kind
     * and missing from Atlas; nothing caught the reverse, so B could add {@code provider} to
     * {@code nemaki_external_asset}, forget {@code EndpointKind}, and leave every test green
     * while the value never travels.
     *
     * <p>The content and version-identity fields that used to be here are now on both sides
     * (v2.3.31), so they are asserted by the alignment checks rather than by their absence.
     * What remains is the manifest that ties an operation's chunks together, which needs the
     * chunking work rather than a schema line.
     */
    private static final Map<EndpointKind, List<String>> AWAITING_INCREMENT_B = Map.of(
            EndpointKind.IMPORT_ARTIFACT, List.of("manifestDigest", "totalObjectCount",
                    "totalByteLength", "completedObjectCount", "failedObjectCount",
                    "businessResult"),
            EndpointKind.EXPORT_ARTIFACT, List.of("manifestDigest", "totalObjectCount",
                    "totalByteLength", "completedObjectCount", "failedObjectCount",
                    "businessResult"));

    @Test
    public void theAttributesIncrementBOwesAreAbsentFromBothSidesOrPresentInBoth() {
        Map<String, Map<String, String>> schema = atlasTypes();
        for (Map.Entry<EndpointKind, List<String>> owed : AWAITING_INCREMENT_B.entrySet()) {
            EndpointKind kind = owed.getKey();
            Map<String, String> type = schema.get(kind.atlasTypeName());
            for (String attribute : owed.getValue()) {
                boolean inSchema = type != null && type.containsKey(attribute);
                boolean inKind = kind.isAllowedAttribute(attribute);
                assertEquals(inSchema, inKind,
                        kind + "." + attribute + " is in " + (inSchema ? "the Atlas type" : "the"
                                + " kind") + " but not the other. Increment B adds these to both,"
                                + " or to neither: adding only to the schema means the value never"
                                + " travels, and adding only here means Atlas drops it.");
            }
        }
    }

    /**
     * Names an artifact type may never declare, on either side (v2.3.29).
     *
     * <p>§4's boundary rule is that the Atlas persistence layer carries no stored URL: a
     * SharePoint sharing link keeps its token in the <em>path</em>, so stripping a query string
     * does not make a URL safe and no transformation of a stored one can be shown to be. The
     * artifact types are where a "source" or "destination" attribute would most naturally be
     * added by someone who has not read that rule — the operation's own Process type already
     * carries the description, and adding it here would put the same operator-supplied path
     * behind a second, longer-lived retention rule.
     *
     * <p>Matched as substrings, case-insensitively, so a future {@code sourceUri} or
     * {@code downloadLink} is caught by the same list that catches {@code url}.
     */
    private static final List<String> FORBIDDEN_ON_ARTIFACTS = List.of(
            "url", "uri", "link", "href", "path", "token", "secret", "credential",
            "signature", "sas");

    @Test
    public void anArtifactTypeCarriesNoLocationOrSecretAttribute() {
        Map<String, Map<String, String>> schema = atlasTypes();
        for (EndpointKind kind : List.of(EndpointKind.IMPORT_ARTIFACT,
                EndpointKind.EXPORT_ARTIFACT)) {
            Set<String> names = new TreeSet<>(kind.allowedAttributes());
            Map<String, String> type = schema.get(kind.atlasTypeName());
            assertNotNull(type, kind.atlasTypeName() + " must exist in the schema payload");
            names.addAll(type.keySet());
            for (String name : names) {
                for (String forbidden : FORBIDDEN_ON_ARTIFACTS) {
                    assertFalse(name.toLowerCase(java.util.Locale.ROOT).contains(forbidden),
                            kind.atlasTypeName() + " declares '" + name + "', which looks like a"
                                    + " location or a secret. Where the bytes came from or went"
                                    + " belongs to the Process type; §4 forbids a stored URL at"
                                    + " the Atlas boundary because the token can be in the path.");
                }
            }
        }
    }

    /**
     * The folder companion is identified by the folder's object id, not its name or path.
     *
     * <p>A rename or a move must not change the qualified name: past lineage points at it, and
     * an identity that tracked the name would strand every Process the moment someone renamed a
     * folder. The name lives in the entity as an attribute, where changing it is an update.
     */
    @Test
    public void aFolderCompanionIsIdentifiedByObjectId() {
        assertEquals(EndpointKind.Identity.OBJECT_ID, EndpointKind.CMIS_FOLDER.identity());
        assertEquals("nemaki_folder_dataset", EndpointKind.CMIS_FOLDER.atlasTypeName());

        assertEquals("nemaki://bedroom/folders/f-1/dataset",
                LineageEndpoint.folderProxyQualifiedName("bedroom", "f-1"));
        assertNotEquals(LineageEndpoint.folderProxyQualifiedName("bedroom", "f-1"),
                LineageEndpoint.folderProxyQualifiedName("bedroom", "f-2"));
        assertNotEquals(LineageEndpoint.folderProxyQualifiedName("bedroom", "f-1"),
                LineageEndpoint.folderProxyQualifiedName("canopy", "f-1"));
        // The companion is a distinct entity from the folder, so the two names must differ.
        assertNotEquals(LineageEndpoint.objectQualifiedName("bedroom", "f-1"),
                LineageEndpoint.folderProxyQualifiedName("bedroom", "f-1"));
    }

    /**
     * The companion carries the two fields the retention rule needs, and no location.
     *
     * <p>{@code active} is what a query filters on and {@code sourceState} is why; collapsing
     * them would make ARCHIVED and PURGED indistinguishable, and only the second may ever be
     * removed. Neither is a folder path — the companion is named by id precisely so it does not
     * have to carry one.
     */
    @Test
    public void theFolderCompanionCarriesLifecycleStateAndNoLocation() {
        Map<String, String> type = atlasTypes().get("nemaki_folder_dataset");
        assertNotNull(type, "nemaki_folder_dataset must exist in the schema payload");
        assertEquals("boolean", type.get("active"));
        assertEquals("string", type.get("sourceState"));
        assertEquals("string", type.get("repositoryId"));
        assertEquals("string", type.get("objectId"));

        for (String name : type.keySet()) {
            for (String forbidden : FORBIDDEN_ON_ARTIFACTS) {
                assertFalse(name.toLowerCase(java.util.Locale.ROOT).contains(forbidden),
                        "nemaki_folder_dataset declares '" + name + "', which looks like a"
                                + " location or a secret");
            }
        }
    }

    /**
     * Identity is the operation, not the folder — and the qualified names say so.
     *
     * <p>If an artifact were named after the folder, {@code computeEventKey} would give the
     * second import of that folder the same key as the first and {@code append()} would drop it
     * as a duplicate. That failure is silent, which is why it is pinned here rather than left to
     * the identity rules in the design doc.
     */
    @Test
    public void anArtifactIsIdentifiedByItsOperation() {
        assertEquals(EndpointKind.Identity.OPERATION_ID, EndpointKind.IMPORT_ARTIFACT.identity());
        assertEquals(EndpointKind.Identity.OPERATION_ID, EndpointKind.EXPORT_ARTIFACT.identity());

        assertEquals("nemaki://bedroom/imports/op-1",
                LineageEndpoint.importArtifactQualifiedName("bedroom", "op-1"));
        assertEquals("nemaki://bedroom/exports/op-1",
                LineageEndpoint.exportArtifactQualifiedName("bedroom", "op-1"));
        assertNotEquals(LineageEndpoint.importArtifactQualifiedName("bedroom", "op-1"),
                LineageEndpoint.importArtifactQualifiedName("bedroom", "op-2"));
        assertNotEquals(LineageEndpoint.importArtifactQualifiedName("bedroom", "op-1"),
                LineageEndpoint.importArtifactQualifiedName("canopy", "op-1"));
    }

    /**
     * Every endpoint kind resolves to a {@code DataSet}, so a Process can name it.
     *
     * <p>Atlas types {@code Process.inputs} and {@code Process.outputs} as {@code DataSet}. A
     * kind whose Atlas type is not one produces a lineage edge the catalog refuses — which is
     * exactly why {@code CMIS_FOLDER} points at {@code nemaki_folder_dataset} and not at
     * {@code nemaki_folder}, and why the artifact types were created at all.
     */
    @Test
    public void everyEndpointKindIsADataSet() {
        Map<String, List<String>> superTypes = atlasSuperTypes();
        for (EndpointKind kind : EndpointKind.values()) {
            List<String> parents = superTypes.get(kind.atlasTypeName());
            assertNotNull(parents, kind.atlasTypeName() + " has no type definition");
            assertTrue(parents.contains("DataSet"),
                    kind + " resolves to " + kind.atlasTypeName() + ", whose superTypes are "
                            + parents + ". Process.inputs/outputs are typed DataSet, so a"
                            + " lineage edge naming this kind would be refused.");
        }
        // The folder itself is deliberately NOT a DataSet — that is the whole reason the
        // companion exists, and changing it would rewrite every folder entity in the catalog.
        assertFalse(superTypes.get("nemaki_folder").contains("DataSet"));
    }

    /**
     * An import/export artifact and a folder companion are different entities in one graph.
     *
     * <p>{@code IMPORT_UPLOADED}'s input is the artifact and its output is the moved content —
     * folders among it. If the two ever produced the same qualified name, a Process would name
     * one entity as both its input and its output and the lineage would be a self-loop.
     */
    @Test
    public void anArtifactAndAFolderCompanionAreDistinctEntities() {
        String importArtifact = LineageEndpoint.importArtifactQualifiedName("bedroom", "op-1");
        String exportArtifact = LineageEndpoint.exportArtifactQualifiedName("bedroom", "op-1");
        String companion = LineageEndpoint.folderProxyQualifiedName("bedroom", "f-1");
        String folder = LineageEndpoint.objectQualifiedName("bedroom", "f-1");

        assertEquals(4, new TreeSet<>(List.of(importArtifact, exportArtifact, companion, folder))
                .size(), "two of these collide, which would make a Process name one entity twice");

        // The same operation id used for an import and an export is still two artifacts.
        assertNotEquals(importArtifact, exportArtifact);
        // And a lineage edge for a folder names the companion, never the folder.
        assertNotEquals(folder, companion);
    }

    /**
     * Exactly the display values carry §2 companions, and the companions exist in Atlas.
     *
     * <p>A companion the Atlas type does not declare is discarded on arrival, which turns
     * "this value was shortened" into silence — the precise failure §2 was written to stop.
     */
    @Test
    public void artifactDisplayValuesCarryTheirTruncationEvidence() {
        Map<String, Map<String, String>> schema = atlasTypes();
        assertEquals(EndpointAttribute.Policy.TRUNCATE_WITH_EVIDENCE,
                EndpointKind.IMPORT_ARTIFACT.attribute("originalFileName").policy());
        assertEquals(EndpointAttribute.Policy.TRUNCATE_WITH_EVIDENCE,
                EndpointKind.EXPORT_ARTIFACT.attribute("name").policy());
        assertTrue(schema.get("nemaki_import_artifact")
                .containsKey("originalFileNameOriginalSha256"));
        assertTrue(schema.get("nemaki_export_artifact").containsKey("nameOriginalSha256"));

        // The values that must never be shortened: a digest, and two machine-read modes.
        assertEquals(EndpointAttribute.Policy.PRESERVE,
                EndpointKind.IMPORT_ARTIFACT.attribute("contentHash").policy());
        assertEquals(EndpointAttribute.Policy.PRESERVE,
                EndpointKind.IMPORT_ARTIFACT.attribute("importMode").policy());
        assertEquals(EndpointAttribute.Policy.PRESERVE,
                EndpointKind.EXPORT_ARTIFACT.attribute("artifactKind").policy());
    }

    // ------------------------------------------------------------------

    /** {@code typeName -> superTypes}, read from the real payload factory. */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> atlasSuperTypes() {
        Map<String, List<String>> superTypes = new LinkedHashMap<>();
        for (Map<String, Object> entityDef : entityDefs()) {
            superTypes.put((String) entityDef.get("name"),
                    (List<String>) entityDef.getOrDefault("superTypes", List.of()));
        }
        return superTypes;
    }

    /** {@code typeName -> (attributeName -> atlasType)}, read from the real payload factory. */
    private static Map<String, Map<String, String>> atlasTypes() {
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (Map<String, Object> entityDef : entityDefs()) {
            Map<String, String> attributes = new LinkedHashMap<>();
            for (Map<String, Object> attribute : attributeDefs(entityDef)) {
                attributes.put((String) attribute.get("name"), (String) attribute.get("typeName"));
            }
            types.put((String) entityDef.get("name"), attributes);
        }
        return types;
    }

    private static Map<String, Set<String>> mandatoryAtlasAttributes() {
        Map<String, Set<String>> mandatory = new HashMap<>();
        for (Map<String, Object> entityDef : entityDefs()) {
            Set<String> names = new java.util.HashSet<>();
            for (Map<String, Object> attribute : attributeDefs(entityDef)) {
                if (Boolean.FALSE.equals(attribute.get("isOptional"))) {
                    names.add((String) attribute.get("name"));
                }
            }
            mandatory.put((String) entityDef.get("name"), names);
        }
        return mandatory;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entityDefs() {
        Map<String, Object> payload =
                new PurviewSchemaPayloadFactory().buildTypeDefinitionsPayload(null);
        List<Map<String, Object>> entityDefs =
                (List<Map<String, Object>>) payload.get("entityDefs");
        assertNotNull(entityDefs, "the schema payload has no entityDefs");
        return entityDefs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributeDefs(Map<String, Object> entityDef) {
        return (List<Map<String, Object>>) entityDef.get("attributeDefs");
    }
}
