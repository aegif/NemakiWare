package jp.aegif.nemaki.rest.purview.payload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PurviewSchemaPayloadFactory {

    private static final String SERVICE_TYPE = "NemakiWare-Custom-Types";

    private CatalogPropertyMappingResolver propertyMappingResolver;

    @Autowired(required = false)
    public void setPropertyMappingResolver(CatalogPropertyMappingResolver propertyMappingResolver) {
        this.propertyMappingResolver = propertyMappingResolver;
    }

    /** @deprecated Use {@link #buildTypeDefinitionsPayload(PurviewSchemaManifest, String)} */
    public Map<String, Object> buildTypeDefinitionsPayload(PurviewSchemaManifest manifest) {
        return buildTypeDefinitionsPayload(manifest, null);
    }

    public Map<String, Object> buildTypeDefinitionsPayload(PurviewSchemaManifest manifest, String repositoryId) {
        List<Map<String, Object>> customAttrDefs = buildCustomPropertyAttributeDefs(repositoryId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of(
                buildRepositoryEntityDef(),
                buildFolderEntityDef(customAttrDefs),
                buildDocumentEntityDef(customAttrDefs),
                buildTypeDefinitionEntityDef(),
                buildExternalAssetEntityDef(),
                buildArchiveEntityDef(),
                buildArchiveProcessEntityDef(),
                buildCloudSyncProcessEntityDef(),
                buildImportProcessEntityDef(),
                buildExportProcessEntityDef()));
        payload.put("relationshipDefs", List.of(
                buildRepositoryContainsFolderRelationshipDef(),
                buildFolderContainsFolderRelationshipDef(),
                buildFolderContainsDocumentRelationshipDef(),
                buildDocumentHasTypeDefinitionRelationshipDef(),
                buildDocumentHasArchiveRelationshipDef()));
        payload.put("businessMetadataDefs", List.of());
        payload.put("classificationDefs", List.of());
        payload.put("enumDefs", List.of());
        payload.put("structDefs", List.of());
        return payload;
    }

    private Map<String, Object> buildRepositoryEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_repository",
                "Repository synchronized from NemakiWare");
        entityDef.put("superTypes", List.of("Referenceable"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("rootFolderId", "string", false),
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildFolderEntityDef(List<Map<String, Object>> customAttrDefs) {
        Map<String, Object> entityDef = baseTypeDef("nemaki_folder",
                "Folder synchronized from NemakiWare");
        entityDef.put("superTypes", List.of("Referenceable"));
        List<Map<String, Object>> attrs = new ArrayList<>(List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                attribute("parentId", "string", true),
                attribute("typeId", "string", true),
                attribute("folderPath", "string", true),
                attribute("lifecycleState", "string", true)));
        attrs.addAll(customAttrDefs);
        entityDef.put("attributeDefs", attrs);
        return entityDef;
    }

    private Map<String, Object> buildDocumentEntityDef(List<Map<String, Object>> customAttrDefs) {
        Map<String, Object> entityDef = baseTypeDef("nemaki_document",
                "Document synchronized from NemakiWare");
        entityDef.put("superTypes", List.of("DataSet"));
        List<Map<String, Object>> attrs = new ArrayList<>(List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                attribute("parentId", "string", true),
                attribute("typeId", "string", true),
                attribute("folderPath", "string", true),
                attribute("versionSeriesId", "string", true),
                attribute("versionLabel", "string", true),
                attribute("isLatestVersion", "boolean", true),
                attribute("lifecycleState", "string", true),
                attribute("archiveState", "string", true),
                attribute("archiveId", "string", true),
                attribute("archivedAt", "long", true),
                attribute("cloudProvider", "string", true),
                attribute("externalFileId", "string", true),
                attribute("cloudFileUrl", "string", true),
                attribute("cloudLastSyncedAt", "string", true)));
        attrs.addAll(customAttrDefs);
        entityDef.put("attributeDefs", attrs);
        return entityDef;
    }

    /**
     * Builds attribute definitions from the resolved property mappings
     * for a specific repository. Type and cardinality are derived from
     * the repository's current type definitions (not stored in the mapping).
     */
    List<Map<String, Object>> buildCustomPropertyAttributeDefs(String repositoryId) {
        if (propertyMappingResolver == null) {
            return List.of();
        }
        // Schema is global to the catalog backend — union all repositories' mappings
        Map<String, CatalogPropertyMappingResolver.ResolvedMapping> resolved =
                propertyMappingResolver.getResolvedMappingsAllRepositories();
        if (resolved.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (CatalogPropertyMappingResolver.ResolvedMapping m : resolved.values()) {
            String atlasType = CatalogPropertyMappingResolver.toAtlasTypeName(m.propertyType());
            String cardinality = m.cardinality() == org.apache.chemistry.opencmis.commons.enums.Cardinality.MULTI
                    ? "SET" : "SINGLE";
            if ("SET".equals(cardinality)) {
                atlasType = "array<" + atlasType + ">";
            }
            result.add(attribute(m.catalogName(), atlasType, true, cardinality));
        }
        return result;
    }

    private Map<String, Object> buildTypeDefinitionEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_type_definition",
                "Type definition synchronized from NemakiWare");
        entityDef.put("superTypes", List.of("Referenceable"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("typeId", "string", false),
                attribute("queryName", "string", true),
                attribute("baseTypeId", "string", false),
                attribute("parentTypeId", "string", true),
                attribute("propertyCount", "long", false),
                attribute("versionable", "boolean", true),
                attribute("contentStreamAllowed", "string", true),
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildExternalAssetEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_external_asset",
                "External managed asset used for NemakiWare lineage");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("externalStableKey", "string", false),
                attribute("sourceSystem", "string", false),
                attribute("externalPath", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildArchiveEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_archive",
                "Archive representation of a NemakiWare document");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("originalObjectId", "string", false),
                attribute("archiveRepositoryId", "string", false),
                attribute("lifecycleState", "string", false),
                attribute("archiveState", "string", true),
                attribute("archivedAt", "long", true),
                attribute("versionSeriesId", "string", true),
                attribute("versionLabel", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildArchiveProcessEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_archive_process",
                "Process representing NemakiWare archive lineage to cold storage");
        entityDef.put("superTypes", List.of("Process"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("archiveId", "string", false),
                attribute("archiveState", "string", true),
                attribute("externalStableKey", "string", false),
                attribute("targetDescription", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildCloudSyncProcessEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_cloud_sync_process",
                "Process representing NemakiWare cloud sync lineage");
        entityDef.put("superTypes", List.of("Process"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                attribute("cloudProvider", "string", false),
                attribute("externalStableKey", "string", false),
                attribute("targetDescription", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildImportProcessEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_import_process",
                "Process representing NemakiWare managed filesystem import lineage");
        entityDef.put("superTypes", List.of("Process"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("folderId", "string", false),
                attribute("importMode", "string", false),
                attribute("externalStableKey", "string", true),
                attribute("sourceDescription", "string", true),
                attribute("objectCount", "long", true)));
        return entityDef;
    }

    private Map<String, Object> buildExportProcessEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_export_process",
                "Process representing NemakiWare managed filesystem export lineage");
        entityDef.put("superTypes", List.of("Process"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("folderId", "string", true),
                attribute("exportMode", "string", false),
                attribute("externalStableKey", "string", true),
                attribute("targetDescription", "string", true),
                attribute("objectCount", "long", true)));
        return entityDef;
    }

    private Map<String, Object> buildRepositoryContainsFolderRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_repository_contains_folder",
                "Links NemakiWare repositories to root folders");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("nemaki_repository", "repository"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_folder", "folder"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
    }

    private Map<String, Object> buildFolderContainsFolderRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_folder_contains_folder",
                "Links NemakiWare folders to child folders");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("nemaki_folder", "parentFolder"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_folder", "childFolder"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
    }

    private Map<String, Object> buildFolderContainsDocumentRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_folder_contains_document",
                "Links NemakiWare folders to child documents");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("nemaki_folder", "folder"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_document", "document"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
    }

    private Map<String, Object> buildDocumentHasArchiveRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_document_has_archive",
                "Links NemakiWare document datasets to archive datasets");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("DataSet", "document"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_archive", "archive"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
    }

    private Map<String, Object> buildDocumentHasTypeDefinitionRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_document_has_type_definition",
                "Links NemakiWare documents to synchronized custom type definitions");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("nemaki_document", "document"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_type_definition", "typeDefinition"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
    }

    private Map<String, Object> baseTypeDef(String name, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", "ENTITY");
        map.put("version", 1);
        map.put("name", name);
        map.put("description", description);
        map.put("typeVersion", "1.0");
        map.put("serviceType", SERVICE_TYPE);
        map.put("options", Map.of());
        return map;
    }

    private Map<String, Object> attribute(String name, String typeName, boolean optional) {
        return attribute(name, typeName, optional, "SINGLE");
    }

    private Map<String, Object> attribute(String name, String typeName, boolean optional, String cardinality) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("typeName", typeName);
        map.put("isOptional", optional);
        map.put("cardinality", cardinality);
        map.put("valuesMinCount", optional ? 0 : 1);
        map.put("valuesMaxCount", "SET".equals(cardinality) ? Integer.MAX_VALUE : 1);
        map.put("isUnique", false);
        map.put("isIndexable", true);
        if ("string".equals(typeName)) {
            map.put("options", Map.of("maxStrLength", "500"));
        }
        return map;
    }

    private Map<String, Object> relationshipEnd(String type, String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("name", name);
        map.put("isContainer", false);
        map.put("cardinality", "SINGLE");
        map.put("isLegacyAttribute", false);
        return map;
    }
}
