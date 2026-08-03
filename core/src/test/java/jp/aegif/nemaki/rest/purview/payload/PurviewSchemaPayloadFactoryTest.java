package jp.aegif.nemaki.rest.purview.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
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
        assertTrue(businessMetadataDefs.isEmpty(), "businessMetadataDefs should be empty (nemakiGovernance removed)");
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
        assertTrue(manifest.getBusinessMetadataNames().isEmpty(), "businessMetadataNames should be empty");
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

    @Test
    @SuppressWarnings("unchecked")
    public void testDocumentEntityDefIncludesCustomPropertyMappings() {
        IntegrationSettingsService service = mock(IntegrationSettingsService.class);
        String json = """
                {
                  "nemaki:document": {
                    "nemaki:dept": { "enabled": true, "catalogName": "department" },
                    "nemaki:priority": { "enabled": true, "catalogName": "priority" }
                  },
                  "nemaki:report": {
                    "nemaki:date": { "enabled": true, "catalogName": "report_date" }
                  }
                }
                """;
        when(service.readSetting("catalog.sync.propertyMappings.bedroom")).thenReturn(json);

        // Mock TypeService to resolve types at runtime
        TypeService typeService = mock(TypeService.class);
        NemakiPropertyDefinitionCore stringCore = mock(NemakiPropertyDefinitionCore.class);
        when(stringCore.getPropertyType()).thenReturn(PropertyType.STRING);
        when(stringCore.getCardinality()).thenReturn(Cardinality.SINGLE);
        NemakiPropertyDefinitionCore intCore = mock(NemakiPropertyDefinitionCore.class);
        when(intCore.getPropertyType()).thenReturn(PropertyType.INTEGER);
        when(intCore.getCardinality()).thenReturn(Cardinality.SINGLE);
        NemakiPropertyDefinitionCore dateCore = mock(NemakiPropertyDefinitionCore.class);
        when(dateCore.getPropertyType()).thenReturn(PropertyType.DATETIME);
        when(dateCore.getCardinality()).thenReturn(Cardinality.SINGLE);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:dept")).thenReturn(stringCore);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:priority")).thenReturn(intCore);
        when(typeService.getPropertyDefinitionCoreByPropertyId("bedroom", "nemaki:date")).thenReturn(dateCore);

        RepositoryInfoMap repoMap = mock(RepositoryInfoMap.class);
        when(repoMap.keys()).thenReturn(Set.of("bedroom"));

        CatalogPropertyMappingResolver resolver = new CatalogPropertyMappingResolver(service, typeService, repoMap);

        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();
        payloadFactory.setPropertyMappingResolver(resolver);

        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());
        List<Map<String, Object>> entityDefs = (List<Map<String, Object>>) payload.get("entityDefs");
        List<String> docAttrs = attributeNames(entityDefs, "nemaki_document");

        // Standard attributes still present
        assertTrue(docAttrs.contains("objectId"));
        assertTrue(docAttrs.contains("repositoryId"));
        // Custom mapped attributes added to both document and folder
        assertTrue(docAttrs.contains("department"), "Expected custom attr 'department'");
        assertTrue(docAttrs.contains("priority"), "Expected custom attr 'priority'");
        assertTrue(docAttrs.contains("report_date"), "Expected custom attr 'report_date'");

        // Verify correct Atlas types (resolved from TypeService, not stored)
        Map<String, Object> docDef = entityDefs.stream()
                .filter(d -> "nemaki_document".equals(d.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> attrDefs = (List<Map<String, Object>>) docDef.get("attributeDefs");
        Map<String, Object> priorityAttr = attrDefs.stream()
                .filter(a -> "priority".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("long", priorityAttr.get("typeName"), "INTEGER should map to Atlas 'long'");
        Map<String, Object> dateAttr = attrDefs.stream()
                .filter(a -> "report_date".equals(a.get("name"))).findFirst().orElseThrow();
        assertEquals("long", dateAttr.get("typeName"), "DATETIME should map to Atlas 'long'");

        // Folder should also have custom attrs
        List<String> folderAttrs = attributeNames(entityDefs, "nemaki_folder");
        assertTrue(folderAttrs.contains("department"), "Folder should also have custom attrs");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDocumentEntityDefWithoutMappingResolverHasNoCustomAttrs() {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();
        // No resolver set

        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());
        List<Map<String, Object>> entityDefs = (List<Map<String, Object>>) payload.get("entityDefs");
        List<String> docAttrs = attributeNames(entityDefs, "nemaki_document");

        // 16 standard attributes + §2's two truncation-evidence companions (v2.3.26):
        // folderPathOriginalSha256 and versionLabelOriginalSha256 must be declared on the type,
        // or the evidence for a shortened value is the thing Atlas drops.
        assertEquals(18, docAttrs.size(), docAttrs.toString());
        assertTrue(docAttrs.containsAll(
                List.of("folderPathOriginalSha256", "versionLabelOriginalSha256")));
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
