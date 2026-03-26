package jp.aegif.nemaki.rest.purview.payload;

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
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_repository".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_folder".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_document".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_type_definition".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_external_asset".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_archive".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_archive_process".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_cloud_sync_process".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_import_process".equals(def.get("name"))));
        assertTrue(entityDefs.stream().anyMatch(def -> "nemaki_export_process".equals(def.get("name"))));
        assertTrue(attributeNames(entityDefs, "nemaki_repository").contains("rootFolderId"));
        assertTrue(attributeNames(entityDefs, "nemaki_folder").contains("parentId"));
        assertTrue(attributeNames(entityDefs, "nemaki_folder").contains("folderPath"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("folderPath"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("lifecycleState"));
        assertTrue(attributeNames(entityDefs, "nemaki_type_definition").contains("baseTypeId"));
        assertTrue(attributeNames(entityDefs, "nemaki_type_definition").contains("propertyCount"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("archiveId"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("cloudProvider"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("externalFileId"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("cloudFileUrl"));
        assertTrue(attributeNames(entityDefs, "nemaki_document").contains("cloudLastSyncedAt"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive").contains("archiveState"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive").contains("archivedAt"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive_process").contains("externalStableKey"));
        assertTrue(attributeNames(entityDefs, "nemaki_archive_process").contains("targetDescription"));
        assertTrue(attributeNames(entityDefs, "nemaki_cloud_sync_process").contains("externalStableKey"));
        assertTrue(attributeNames(entityDefs, "nemaki_cloud_sync_process").contains("cloudProvider"));
        assertTrue(attributeNames(entityDefs, "nemaki_import_process").contains("sourceDescription"));
        assertTrue(attributeNames(entityDefs, "nemaki_import_process").contains("objectCount"));
        assertTrue(attributeNames(entityDefs, "nemaki_export_process").contains("targetDescription"));
        assertTrue(attributeNames(entityDefs, "nemaki_export_process").contains("objectCount"));
        assertEquals(5, relationshipDefs.size());
        assertTrue(relationshipDefs.stream().anyMatch(def -> "nemaki_repository_contains_folder".equals(def.get("name"))));
        assertTrue(relationshipDefs.stream().anyMatch(def -> "nemaki_folder_contains_folder".equals(def.get("name"))));
        assertTrue(relationshipDefs.stream().anyMatch(def -> "nemaki_folder_contains_document".equals(def.get("name"))));
        assertTrue(relationshipDefs.stream().anyMatch(def -> "nemaki_document_has_type_definition".equals(def.get("name"))));
        assertTrue(relationshipDefs.stream().anyMatch(def -> "nemaki_document_has_archive".equals(def.get("name"))));
        assertEquals(1, businessMetadataDefs.size());
        assertEquals("nemakiGovernance", businessMetadataDefs.get(0).get("name"));
    }

    @Test
    public void testBuildManifestReturnsStableHashAcrossMultipleInvocations() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();

        PurviewSchemaManifest manifest1 = manifestFactory.buildManifest();
        PurviewSchemaManifest manifest2 = manifestFactory.buildManifest();

        assertEquals(manifest1.getSchemaHash(), manifest2.getSchemaHash());
        assertEquals(manifest1.getSchemaVersion(), manifest2.getSchemaVersion());
    }

    @Test
    public void testBuildManifestIncludesAllExpectedCategories() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();

        assertFalse(manifest.getCustomTypeNames().isEmpty());
        assertFalse(manifest.getRelationshipTypeNames().isEmpty());
        assertFalse(manifest.getBusinessMetadataNames().isEmpty());
        assertFalse(manifest.getSchemaHash().isEmpty());
        assertFalse(manifest.getSchemaVersion().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEntityDefsContainRequiredAtlasFields() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();

        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());
        List<Map<String, Object>> entityDefs = (List<Map<String, Object>>) payload.get("entityDefs");

        for (Map<String, Object> entityDef : entityDefs) {
            assertTrue(entityDef.containsKey("name"), "entityDef missing 'name': " + entityDef);
            assertTrue(entityDef.containsKey("attributeDefs"), "entityDef missing 'attributeDefs': " + entityDef.get("name"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRelationshipDefsContainEndDefs() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();

        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());
        List<Map<String, Object>> relationshipDefs = (List<Map<String, Object>>) payload.get("relationshipDefs");

        for (Map<String, Object> relDef : relationshipDefs) {
            assertTrue(relDef.containsKey("name"), "relDef missing 'name'");
            assertTrue(relDef.containsKey("endDef1"), "relDef missing 'endDef1': " + relDef.get("name"));
            assertTrue(relDef.containsKey("endDef2"), "relDef missing 'endDef2': " + relDef.get("name"));
        }
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
