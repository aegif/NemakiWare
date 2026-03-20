package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class PurviewSchemaPayloadFactoryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testBuildTypeDefinitionsPayloadIncludesExpectedCustomTypesAndRelationship() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();

        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());

        List<Map<String, Object>> entityDefs = (List<Map<String, Object>>) payload.get("entityDefs");
        List<Map<String, Object>> relationshipDefs = (List<Map<String, Object>>) payload.get("relationshipDefs");
        List<Map<String, Object>> businessMetadataDefs = (List<Map<String, Object>>) payload.get("businessMetadataDefs");

        assertFalse(entityDefs.isEmpty());
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_document".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_external_asset".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_archive".equals(def.get("name"))));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("lifecycleState"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("archiveId"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive").contains("archiveState"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive").contains("archivedAt"));
        assertEquals(1, relationshipDefs.size());
        assertEquals("nemaki_document_has_archive", relationshipDefs.get(0).get("name"));
        assertEquals(1, businessMetadataDefs.size());
        assertEquals("nemakiGovernance", businessMetadataDefs.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    private List<String> attributeNames(List<Map<String, Object>> entityDefs, String typeName) {
        Map<String, Object> typeDef = entityDefs.stream()
                .filter(def -> typeName.equals(def.get("name")))
                .findFirst()
                .orElseGet(LinkedHashMap::new);
        return ((List<Map<String, Object>>) typeDef.get("attributeDefs")).stream()
                .map(def -> def.get("name").toString())
                .toList();
    }
}
