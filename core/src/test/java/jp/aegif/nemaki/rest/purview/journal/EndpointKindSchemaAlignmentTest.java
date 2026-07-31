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
     * Kinds whose Atlas type increment B still has to create.
     *
     * <p>Asserted to be exactly this set, so that the day B adds one of these types the alignment
     * check below starts applying to it and this list has to be shortened deliberately.
     */
    private static final Set<EndpointKind> AWAITING_SCHEMA = Set.of(
            EndpointKind.CMIS_FOLDER, EndpointKind.IMPORT_ARTIFACT, EndpointKind.EXPORT_ARTIFACT);

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
        assertEquals(List.of("name", "versionLabel", "folderPath"),
                EndpointKind.CMIS_DOCUMENT.allowedAttributes());
        assertEquals(List.of("name"), EndpointKind.CMIS_FOLDER.allowedAttributes());
        assertEquals(List.of("archivedAt", "originalObjectId", "name", "versionLabel",
                "archiveState"), EndpointKind.ARCHIVE.allowedAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey", "externalPath"),
                EndpointKind.EXTERNAL_ASSET.allowedAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey", "externalPath"),
                EndpointKind.CLOUD_OBJECT.allowedAttributes());
        assertEquals(List.of("sourceSystem", "externalStableKey", "externalPath"),
                EndpointKind.COLD_STORAGE.allowedAttributes());
        assertEquals(List.of("importMode", "byteLength", "contentHash", "originalFileName"),
                EndpointKind.IMPORT_ARTIFACT.allowedAttributes());
        assertEquals(List.of("artifactKind", "objectCount", "name"),
                EndpointKind.EXPORT_ARTIFACT.allowedAttributes());
    }

    /**
     * Attributes increment B is to add to the schema, and to the kind that should then carry them.
     *
     * <p>The other direction of the alignment check. {@code everyDeclaredAttributeExistsInTheAtlasType}
     * catches an attribute declared here and missing from Atlas; nothing caught the reverse, so B
     * could add {@code provider} to {@code nemaki_external_asset}, forget {@code EndpointKind},
     * and leave every test green while the value never travels.
     */
    private static final Map<EndpointKind, List<String>> AWAITING_INCREMENT_B = Map.of(
            EndpointKind.CMIS_DOCUMENT, List.of("mimeType", "contentLength"),
            EndpointKind.EXTERNAL_ASSET, List.of("tenantId"),
            EndpointKind.CLOUD_OBJECT, List.of("provider"),
            EndpointKind.COLD_STORAGE, List.of("storageClass"));

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

    // ------------------------------------------------------------------

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
