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
                buildDocumentEntityDef(),
                buildExternalAssetEntityDef(),
                buildArchiveEntityDef()));
        payload.put("relationshipDefs", List.of(buildDocumentHasArchiveRelationshipDef()));
        payload.put("businessMetadataDefs", List.of(buildGovernanceBusinessMetadataDef()));
        payload.put("classificationDefs", List.of());
        payload.put("enumDefs", List.of());
        payload.put("structDefs", List.of());
        return payload;
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
                attribute("isLatestVersion", "boolean", true)));
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
                attribute("lifecycleState", "string", false)));
        return entityDef;
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
