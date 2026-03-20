package jp.aegif.nemaki.rest.purview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class PurviewSchemaPayloadFactory {

    private static final String SERVICE_TYPE = "NemakiWare-Custom-Types";

    public Map<String, Object> buildTypeDefinitionsPayload(PurviewSchemaManifest manifest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityDefs", List.of(
                buildRepositoryEntityDef(),
                buildFolderEntityDef(),
                buildDocumentEntityDef(),
                buildTypeDefinitionEntityDef(),
                buildExternalAssetEntityDef(),
                buildArchiveEntityDef(),
                buildArchiveProcessEntityDef(),
                buildCloudSyncProcessEntityDef()));
        payload.put("relationshipDefs", List.of(
                buildRepositoryContainsFolderRelationshipDef(),
                buildFolderContainsFolderRelationshipDef(),
                buildFolderContainsDocumentRelationshipDef(),
                buildDocumentHasTypeDefinitionRelationshipDef(),
                buildDocumentHasArchiveRelationshipDef()));
        payload.put("businessMetadataDefs", List.of(buildGovernanceBusinessMetadataDef()));
        payload.put("classificationDefs", List.of());
        payload.put("enumDefs", List.of());
        payload.put("structDefs", List.of());
        return payload;
    }

    private Map<String, Object> buildRepositoryEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_repository",
                "Repository synchronized from NemakiWare");
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("rootFolderId", "string", false),
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildFolderEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_folder",
                "Folder synchronized from NemakiWare");
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                attribute("parentId", "string", true),
                attribute("typeId", "string", true),
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    private Map<String, Object> buildDocumentEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_document",
                "Document synchronized from NemakiWare");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                attribute("parentId", "string", true),
                attribute("typeId", "string", true),
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
        return entityDef;
    }

    private Map<String, Object> buildTypeDefinitionEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_type_definition",
                "Type definition synchronized from NemakiWare");
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

    private Map<String, Object> buildGovernanceBusinessMetadataDef() {
        Map<String, Object> metadataDef = baseTypeDef("nemakiGovernance",
                "Governance metadata synchronized from NemakiWare");
        metadataDef.put("category", "BUSINESS_METADATA");
        metadataDef.put("attributeDefs", List.of(
                attribute("nemakiLifecycleState", "string", true),
                attribute("ownerDepartment", "string", true),
                attribute("retentionPolicy", "string", true)));
        return metadataDef;
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
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("typeName", typeName);
        map.put("isOptional", optional);
        map.put("cardinality", "SINGLE");
        map.put("valuesMinCount", optional ? 0 : 1);
        map.put("valuesMaxCount", 1);
        map.put("isUnique", false);
        map.put("isIndexable", true);
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
