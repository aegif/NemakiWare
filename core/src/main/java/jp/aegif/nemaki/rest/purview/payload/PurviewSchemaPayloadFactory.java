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

    public Map<String, Object> buildTypeDefinitionsPayload(PurviewSchemaManifest manifest) {
        List<Map<String, Object>> customAttrDefs = buildCustomPropertyAttributeDefs();
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
                buildExportProcessEntityDef(),
                buildImportArtifactEntityDef(),
                buildExportArtifactEntityDef(),
                buildFolderDatasetEntityDef()));
        payload.put("relationshipDefs", List.of(
                buildRepositoryContainsFolderRelationshipDef(),
                buildFolderContainsFolderRelationshipDef(),
                buildFolderContainsDocumentRelationshipDef(),
                buildDocumentHasTypeDefinitionRelationshipDef(),
                buildDocumentHasArchiveRelationshipDef(),
                buildFolderHasDatasetRelationshipDef()));
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
                // §2's truncation evidence (v2.3.26). Additive: an attribute the type does
                // not declare is dropped on arrival, so the companion has to exist here or
                // the evidence for a shortened value would be the thing that goes missing.
                attribute("folderPathOriginalSha256", "string", true),
                attribute("versionLabelOriginalSha256", "string", true),
                attribute("isLatestVersion", "boolean", true),
                attribute("lifecycleState", "string", true),
                attribute("archiveState", "string", true),
                attribute("archiveId", "string", true),
                attribute("archivedAt", "long", true),
                attribute("cloudProvider", "string", true),
                attribute("externalFileId", "string", true),
                attribute("cloudFileUrl", "string", true),
                attribute("cloudLastSyncedAt", "string", true),
                // 増分 B (v2.3.31). Content facts and version identity: without the latter,
                // importing the same external file three times reads as "the same asset became
                // the same document" three times, and which version moved is unanswerable.
                // All identifiers, digests and counts — no display value, so no §2 companion.
                attribute("mimeType", "string", true),
                attribute("contentLength", "long", true),
                attribute("versionObjectId", "string", true),
                attribute("changeToken", "string", true),
                attribute("contentHash", "string", true),
                // P1-1(b). contentStored is a THREE-value string — "true" / "false" / "unknown" —
                // not a boolean: a check-in with no stream carries the previous version's content
                // forward, which is undetermined rather than absent. Making it boolean would
                // force that third state into one of the other two.
                attribute("contentStored", "string", true),
                attribute("contentHashAlgorithm", "string", true),
                attribute("contentStateReason", "string", true),
                // P1-1(d) R3: what the digest above is a digest OF. Never "stored" — see
                // EndpointKind.CMIS_DOCUMENT.
                attribute("contentHashSubject", "string", true)));
        attrs.addAll(customAttrDefs);
        entityDef.put("attributeDefs", attrs);
        return entityDef;
    }

    /**
     * Builds attribute definitions from the resolved property mappings
     * for a specific repository. Type and cardinality are derived from
     * the repository's current type definitions (not stored in the mapping).
     */
    List<Map<String, Object>> buildCustomPropertyAttributeDefs() {
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
                attribute("externalPath", "string", true),
                // 増分 B (v2.3.31). One Atlas type, three kinds, and the kinds diverge:
                // tenantId on the generic external asset, provider on cloud, storageClass on
                // cold. Sharing a type constrains what may be declared, not what must be.
                //
                // storageClass is NOT sourceSystem: sourceSystem carries the storage adapter
                // type (what the catalog sync reads from contentRef.type), and GLACIER is a
                // property of the object within that adapter.
                attribute("tenantId", "string", true),
                attribute("provider", "string", true),
                attribute("storageClass", "string", true),
                // What the source said about the bytes, so a re-sync can tell "unchanged" from
                // "changed back". Identifiers and counts; no URL, no path, no credential.
                attribute("sourceRevision", "string", true),
                attribute("sourceModifiedAt", "long", true),
                attribute("sourceContentHash", "string", true),
                attribute("sourceContentLength", "long", true),
                // P1-1(b). What kind of thing was ingested — message, attachment, page — which
                // the stable key's path segment implies but does not state.
                attribute("sourceObjectType", "string", true),
                // The chat identifiers the stable key does not carry. The channel IS in the key;
                // the workspace is not (the key's tenant segment is the connector's tenant id)
                // and neither is the parent message of an attachment.
                attribute("chatWorkspaceId", "string", true),
                attribute("chatMessageId", "string", true),
                attribute("chatThreadId", "string", true)));
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
                attribute("versionLabel", "string", true),
                // §2's truncation evidence (v2.3.26) — see nemaki_document.
                attribute("nameOriginalSha256", "string", true),
                attribute("versionLabelOriginalSha256", "string", true)));
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

    /**
     * What was fed into an import — the upload or the source directory (increment B).
     *
     * <p>Identity is {@code nemaki://{repo}/imports/{operationId}}: an artifact belongs to one
     * operation, so the same folder imported twice produces two artifacts and two Processes.
     * Naming it after the folder instead would make the second import collapse into the first.
     *
     * <p><b>No source path or URL attribute, deliberately.</b> Where the bytes came from is a
     * property of the operation, and {@code nemaki_import_process.sourceDescription} carries it.
     * Putting it here as well would put an operator-supplied path — the one place §4's sharing
     * links keep their tokens — behind a second, longer-lived retention rule.
     * {@code originalFileName} is a leaf name, not a path.
     *
     * <p>{@code originalFileName} is the only display value here, so it is the only one carrying
     * a §2 truncation companion. {@code importMode} and {@code contentHash} are machine values
     * and a digest; shortening either would name something that does not exist.
     */
    private Map<String, Object> buildImportArtifactEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_import_artifact",
                "Artifact consumed by a NemakiWare import operation");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("importMode", "string", false),
                attribute("byteLength", "long", true),
                attribute("contentHash", "string", true),
                attribute("originalFileName", "string", true),
                // §2's truncation evidence (v2.3.26) — see nemaki_document.
                attribute("originalFileNameOriginalSha256", "string", true),
                // v2.3.58 (additive, optional): where a historical entity records that its
                // source was destroyed. Without it these types had nowhere to say so, and Atlas
                // silently drops an undeclared attribute — a tombstone written anyway would be
                // indistinguishable from a live object, which is worse than no entity at all.
                // Optional, so every entity already published stays valid.
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    /**
     * What an export produced — the zip or the target directory (increment B).
     *
     * <p>Identity is {@code nemaki://{repo}/exports/{operationId}}, for the same reason as the
     * import artifact: two exports of the same folder are two facts.
     *
     * <p><b>No target path or URL attribute</b>, mirroring the import side; the destination is
     * {@code nemaki_export_process.targetDescription}. {@code name} is the export's display name
     * and is the only value here under §2's limit.
     */
    private Map<String, Object> buildExportArtifactEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_export_artifact",
                "Artifact produced by a NemakiWare export operation");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("artifactKind", "string", false),
                attribute("objectCount", "long", true),
                // name itself is inherited from Asset; only its evidence companion is declared.
                attribute("nameOriginalSha256", "string", true),
                // v2.3.58 (additive, optional): where a historical entity records that its
                // source was destroyed. Without it these types had nowhere to say so, and Atlas
                // silently drops an undeclared attribute — a tombstone written anyway would be
                // indistinguishable from a live object, which is worse than no entity at all.
                // Optional, so every entity already published stays valid.
                attribute("lifecycleState", "string", true)));
        return entityDef;
    }

    /**
     * The DataSet companion of a folder (増分 B, §3).
     *
     * <p>Atlas types {@code Process.inputs} and {@code Process.outputs} as {@code DataSet}, and
     * {@code nemaki_folder} is not one. Changing its supertype would rewrite every folder entity
     * already in the catalog, so a folder gets a 1:1 companion instead: the same object, named
     * for lineage's benefit, created and retired alongside it. It is not a placeholder for a
     * folder that does not exist — {@code sourceState} says which of those it is.
     *
     * <p><b>Never deleted on source deletion.</b> Past lineage points at it, and a Process whose
     * input has vanished is worse than one whose input is marked {@code PURGED}. Only the
     * retention path may remove it, and only with no referencing Process left.
     *
     * <p>{@code name} is inherited from {@code Asset}; the identity is the qualified name, which
     * is derived from {@code objectId} and therefore survives a rename and a move.
     */
    private Map<String, Object> buildFolderDatasetEntityDef() {
        Map<String, Object> entityDef = baseTypeDef("nemaki_folder_dataset",
                "DataSet companion of a NemakiWare folder, for lineage endpoints");
        entityDef.put("superTypes", List.of("DataSet"));
        entityDef.put("attributeDefs", List.of(
                attribute("repositoryId", "string", false),
                attribute("objectId", "string", false),
                // Two fields, not one: `active` is what a query filters on, `sourceState` is why.
                // Collapsing them would make "archived" and "purged" indistinguishable to the
                // retention rule, which may only remove the second.
                attribute("active", "boolean", true),
                attribute("sourceState", "string", true)));
        return entityDef;
    }

    /**
     * The 1:1 tie between a folder and its DataSet companion.
     *
     * <p>{@code endDef1} is the folder rather than {@code DataSet}: the companion belongs to
     * exactly one folder, and naming the concrete type is what stops a second companion from
     * being attached to the same folder by a retry.
     */
    private Map<String, Object> buildFolderHasDatasetRelationshipDef() {
        Map<String, Object> relationshipDef = baseTypeDef("nemaki_folder_has_dataset",
                "Links a NemakiWare folder to its DataSet companion");
        relationshipDef.put("category", "RELATIONSHIP");
        relationshipDef.put("relationshipCategory", "ASSOCIATION");
        relationshipDef.put("endDef1", relationshipEnd("nemaki_folder", "folder"));
        relationshipDef.put("endDef2", relationshipEnd("nemaki_folder_dataset", "folderDataset"));
        relationshipDef.put("propagateTags", "NONE");
        return relationshipDef;
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
