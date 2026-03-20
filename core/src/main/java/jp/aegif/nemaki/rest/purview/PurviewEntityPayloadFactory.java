package jp.aegif.nemaki.rest.purview;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

@Component
public class PurviewEntityPayloadFactory {

    private static final String REPOSITORY_TYPE_NAME = "nemaki_repository";
    private static final String FOLDER_TYPE_NAME = "nemaki_folder";
    private static final String DOCUMENT_TYPE_NAME = "nemaki_document";
    private static final String TYPE_DEFINITION_TYPE_NAME = "nemaki_type_definition";
    private static final String ARCHIVE_TYPE_NAME = "nemaki_archive";
    private static final String EXTERNAL_ASSET_TYPE_NAME = "nemaki_external_asset";
    private static final String ARCHIVE_PROCESS_TYPE_NAME = "nemaki_archive_process";
    private static final String CLOUD_SYNC_PROCESS_TYPE_NAME = "nemaki_cloud_sync_process";
    private static final String DOCUMENT_HAS_TYPE_DEFINITION_RELATIONSHIP = "nemaki_document_has_type_definition";
    private static final String LIFECYCLE_ACTIVE = "ACTIVE";
    private static final String LIFECYCLE_ARCHIVED = "ARCHIVED";

    public Map<String, Object> buildBulkPayload(List<Map<String, Object>> entities) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referredEntities", Map.of());
        payload.put("entities", List.copyOf(entities));
        return payload;
    }

    public Map<String, Object> buildRepositoryEntity(RepositoryInfo repositoryInfo) {
        String repositoryId = repositoryInfo.getId();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildRepositoryQualifiedName(repositoryId));
        attributes.put("name", firstNonBlank(repositoryInfo.getName(), repositoryId));
        attributes.put("description", nullIfBlank(repositoryInfo.getDescription()));
        attributes.put("owner", "system");
        attributes.put("createTime", null);
        attributes.put("modifiedTime", null);
        attributes.put("repositoryId", repositoryId);
        attributes.put("rootFolderId", nullIfBlank(repositoryInfo.getRootFolderId()));
        attributes.put("lifecycleState", LIFECYCLE_ACTIVE);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", REPOSITORY_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", "system");
        entity.put("updatedBy", "system");
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildFolderEntity(String repositoryId, Content content) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildObjectQualifiedName(repositoryId, content.getId()));
        attributes.put("name", firstNonBlank(content.getName(), content.getId()));
        attributes.put("description", nullIfBlank(content.getDescription()));
        attributes.put("owner", firstNonBlank(content.getCreator(), content.getModifier(), "system"));
        attributes.put("createTime", toEpochMillis(content.getCreated()));
        attributes.put("modifiedTime", toEpochMillis(content.getModified()));
        attributes.put("repositoryId", repositoryId);
        attributes.put("objectId", content.getId());
        attributes.put("parentId", nullIfBlank(content.getParentId()));
        attributes.put("typeId", nullIfBlank(content.getObjectType()));
        attributes.put("lifecycleState", LIFECYCLE_ACTIVE);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", FOLDER_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildDocumentEntity(String repositoryId, Content content) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildObjectQualifiedName(repositoryId, content.getId()));
        attributes.put("name", firstNonBlank(content.getName(), content.getId()));
        attributes.put("description", nullIfBlank(content.getDescription()));
        attributes.put("owner", firstNonBlank(content.getCreator(), content.getModifier(), "system"));
        attributes.put("createTime", toEpochMillis(content.getCreated()));
        attributes.put("modifiedTime", toEpochMillis(content.getModified()));
        attributes.put("repositoryId", repositoryId);
        attributes.put("objectId", content.getId());
        attributes.put("parentId", nullIfBlank(content.getParentId()));
        attributes.put("typeId", nullIfBlank(content.getObjectType()));

        if (content instanceof Document document) {
            attributes.put("versionSeriesId", nullIfBlank(document.getVersionSeriesId()));
            attributes.put("versionLabel", nullIfBlank(document.getVersionLabel()));
            attributes.put("isLatestVersion", document.isLatestVersion());
        } else {
            attributes.put("versionSeriesId", null);
            attributes.put("versionLabel", null);
            attributes.put("isLatestVersion", null);
        }
        attributes.put("lifecycleState", LIFECYCLE_ACTIVE);
        attributes.put("archiveState", null);
        attributes.put("archiveId", null);
        attributes.put("archivedAt", null);
        attributes.put("cloudProvider", PurviewCloudMetadataSupport.getCloudProvider(content));
        attributes.put("externalFileId", PurviewCloudMetadataSupport.getExternalFileId(content));
        attributes.put("cloudFileUrl", PurviewCloudMetadataSupport.getCloudFileUrl(content));
        attributes.put("cloudLastSyncedAt", PurviewCloudMetadataSupport.getCloudLastSyncedAt(content));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", DOCUMENT_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildTypeDefinitionEntity(String repositoryId, NemakiTypeDefinition typeDefinition) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildTypeDefinitionQualifiedName(repositoryId, typeDefinition.getTypeId()));
        attributes.put("name", firstNonBlank(typeDefinition.getDisplayName(), typeDefinition.getTypeId()));
        attributes.put("description", nullIfBlank(typeDefinition.getDescription()));
        attributes.put("owner", firstNonBlank(typeDefinition.getCreator(), typeDefinition.getModifier(), "system"));
        attributes.put("createTime", toEpochMillis(typeDefinition.getCreated()));
        attributes.put("modifiedTime", toEpochMillis(typeDefinition.getModified()));
        attributes.put("repositoryId", repositoryId);
        attributes.put("typeId", nullIfBlank(typeDefinition.getTypeId()));
        attributes.put("queryName", nullIfBlank(typeDefinition.getQueryName()));
        attributes.put("baseTypeId", typeDefinition.getBaseId() == null ? null : typeDefinition.getBaseId().value());
        attributes.put("parentTypeId", nullIfBlank(typeDefinition.getParentId()));
        attributes.put("propertyCount", typeDefinition.getProperties() == null ? 0L : (long) typeDefinition.getProperties().size());
        attributes.put("versionable", typeDefinition.isVersionable());
        attributes.put("contentStreamAllowed",
                typeDefinition.getContentStreamAllowed() == null ? null : typeDefinition.getContentStreamAllowed().value());
        attributes.put("lifecycleState", LIFECYCLE_ACTIVE);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", TYPE_DEFINITION_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(typeDefinition.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(typeDefinition.getModifier(), typeDefinition.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildDocumentTypeRelationship(String repositoryId, Content content) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", DOCUMENT_HAS_TYPE_DEFINITION_RELATIONSHIP);
        relationship.put("end1", relationshipEnd(
                DOCUMENT_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, content.getId())));
        relationship.put("end2", relationshipEnd(
                TYPE_DEFINITION_TYPE_NAME,
                buildTypeDefinitionQualifiedName(repositoryId, content.getObjectType())));
        relationship.put("attributes", Map.of());
        return relationship;
    }

    public Map<String, Object> buildDocumentArchiveRelationship(String repositoryId, Archive archive) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", "nemaki_document_has_archive");
        relationship.put("end1", relationshipEnd(
                "DataSet",
                buildObjectQualifiedName(repositoryId, archive.getOriginalId())));
        relationship.put("end2", relationshipEnd(
                ARCHIVE_TYPE_NAME,
                buildArchiveQualifiedName(repositoryId, archive.getId())));
        relationship.put("attributes", Map.of());
        return relationship;
    }

    public Map<String, Object> buildRepositoryFolderRelationship(String repositoryId, Content folder) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", "nemaki_repository_contains_folder");
        relationship.put("end1", relationshipEnd(
                REPOSITORY_TYPE_NAME,
                buildRepositoryQualifiedName(repositoryId)));
        relationship.put("end2", relationshipEnd(
                FOLDER_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, folder.getId())));
        relationship.put("attributes", Map.of());
        return relationship;
    }

    public Map<String, Object> buildFolderFolderRelationship(String repositoryId, Content folder) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", "nemaki_folder_contains_folder");
        relationship.put("end1", relationshipEnd(
                FOLDER_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, folder.getParentId())));
        relationship.put("end2", relationshipEnd(
                FOLDER_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, folder.getId())));
        relationship.put("attributes", Map.of());
        return relationship;
    }

    public Map<String, Object> buildFolderDocumentRelationship(String repositoryId, Content document) {
        Map<String, Object> relationship = new LinkedHashMap<>();
        relationship.put("typeName", "nemaki_folder_contains_document");
        relationship.put("end1", relationshipEnd(
                FOLDER_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, document.getParentId())));
        relationship.put("end2", relationshipEnd(
                DOCUMENT_TYPE_NAME,
                buildObjectQualifiedName(repositoryId, document.getId())));
        relationship.put("attributes", Map.of());
        return relationship;
    }

    public Map<String, Object> buildArchivedDocumentEntity(String repositoryId, Archive archive) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildObjectQualifiedName(repositoryId, archive.getOriginalId()));
        attributes.put("name", firstNonBlank(archive.getName(), archive.getOriginalId(), archive.getId()));
        attributes.put("description", null);
        attributes.put("owner", firstNonBlank(archive.getArchivedBy(), archive.getCreator(), "system"));
        attributes.put("createTime", toEpochMillis(archive.getCreated()));
        attributes.put("modifiedTime", firstNonZero(
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getModified()),
                toEpochMillis(archive.getCreated())));
        attributes.put("repositoryId", repositoryId);
        attributes.put("objectId", archive.getOriginalId());
        attributes.put("parentId", nullIfBlank(archive.getParentId()));
        attributes.put("typeId", nullIfBlank(archive.getType()));
        attributes.put("versionSeriesId", nullIfBlank(archive.getVersionSeriesId()));
        attributes.put("versionLabel", nullIfBlank(archive.getVersionLabel()));
        attributes.put("isLatestVersion", archive.isLatestVersion());
        attributes.put("lifecycleState", LIFECYCLE_ARCHIVED);
        attributes.put("archiveState", archive.getEffectiveArchiveState());
        attributes.put("archiveId", nullIfBlank(archive.getId()));
        attributes.put("archivedAt", zeroToNull(toEpochMillis(archive.getArchivedAt())));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", DOCUMENT_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(archive.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(archive.getArchivedBy(), archive.getModifier(), archive.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildArchiveEntity(String repositoryId, Archive archive) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildArchiveQualifiedName(repositoryId, archive.getId()));
        attributes.put("name", firstNonBlank(archive.getName(), archive.getId(), archive.getOriginalId()));
        attributes.put("description", null);
        attributes.put("owner", firstNonBlank(archive.getArchivedBy(), archive.getCreator(), "system"));
        attributes.put("createTime", firstNonZero(
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getCreated())));
        attributes.put("modifiedTime", firstNonZero(
                toEpochMillis(archive.getColdArchivedAt()),
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getModified()),
                toEpochMillis(archive.getCreated())));
        attributes.put("originalObjectId", nullIfBlank(archive.getOriginalId()));
        attributes.put("archiveRepositoryId", repositoryId);
        attributes.put("lifecycleState", LIFECYCLE_ARCHIVED);
        attributes.put("archiveState", archive.getEffectiveArchiveState());
        attributes.put("archivedAt", zeroToNull(toEpochMillis(archive.getArchivedAt())));
        attributes.put("versionSeriesId", nullIfBlank(archive.getVersionSeriesId()));
        attributes.put("versionLabel", nullIfBlank(archive.getVersionLabel()));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", ARCHIVE_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(archive.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(archive.getArchivedBy(), archive.getModifier(), archive.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public boolean hasArchiveLineageTarget(Archive archive) {
        return resolveArchiveExternalStableKey(archive) != null;
    }

    public String resolveArchiveExternalStableKey(Archive archive) {
        if (archive == null) {
            return null;
        }
        String contentRef = null;
        if (archive.getContentRef() != null) {
            contentRef = nullIfBlank(archive.getContentRef().get("ref"));
        }
        if (contentRef != null) {
            return contentRef;
        }
        if (Archive.STATE_ARCHIVED_COLD.equals(archive.getEffectiveArchiveState())
                || Archive.STATE_COLD_MOVING.equals(archive.getEffectiveArchiveState())) {
            return nullIfBlank(archive.getPath());
        }
        return null;
    }

    public Map<String, Object> buildExternalAssetEntity(String repositoryId, Archive archive) {
        String stableKey = requireArchiveExternalStableKey(archive);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildExternalAssetQualifiedName(repositoryId, stableKey));
        attributes.put("name", firstNonBlank(archive.getName(), archive.getOriginalId(), archive.getId(), stableKey));
        attributes.put("description", null);
        attributes.put("owner", firstNonBlank(archive.getArchivedBy(), archive.getCreator(), "system"));
        attributes.put("createTime", firstNonZero(
                toEpochMillis(archive.getColdArchivedAt()),
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getCreated())));
        attributes.put("modifiedTime", firstNonZero(
                toEpochMillis(archive.getColdArchivedAt()),
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getModified()),
                toEpochMillis(archive.getCreated())));
        attributes.put("externalStableKey", stableKey);
        attributes.put("sourceSystem", firstNonBlank(resolveArchiveSourceSystem(archive), "cold-storage"));
        attributes.put("externalPath", firstNonBlank(stableKey, archive.getPath()));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", EXTERNAL_ASSET_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(archive.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(archive.getArchivedBy(), archive.getModifier(), archive.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public boolean hasCloudSyncLineageTarget(Content content) {
        return resolveCloudExternalStableKey(content) != null;
    }

    public String resolveCloudExternalStableKey(Content content) {
        String provider = PurviewCloudMetadataSupport.getCloudProvider(content);
        String externalFileId = PurviewCloudMetadataSupport.getExternalFileId(content);
        if (provider == null || provider.isBlank() || externalFileId == null || externalFileId.isBlank()) {
            return null;
        }
        return provider + ":" + externalFileId;
    }

    public Map<String, Object> buildExternalAssetEntity(String repositoryId, Content content) {
        String stableKey = requireCloudExternalStableKey(content);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildExternalAssetQualifiedName(repositoryId, stableKey));
        attributes.put("name", firstNonBlank(content.getName(), content.getId(), stableKey));
        attributes.put("description", nullIfBlank(content.getDescription()));
        attributes.put("owner", firstNonBlank(content.getCreator(), content.getModifier(), "system"));
        attributes.put("createTime", toEpochMillis(content.getCreated()));
        attributes.put("modifiedTime", toEpochMillis(content.getModified()));
        attributes.put("externalStableKey", stableKey);
        attributes.put("sourceSystem", PurviewCloudMetadataSupport.getCloudProvider(content));
        attributes.put("externalPath", firstNonBlank(
                PurviewCloudMetadataSupport.getCloudFileUrl(content),
                PurviewCloudMetadataSupport.getExternalFileId(content)));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", EXTERNAL_ASSET_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildCloudSyncProcessEntity(String repositoryId, Content content) {
        String stableKey = requireCloudExternalStableKey(content);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildCloudSyncProcessQualifiedName(repositoryId, content.getId()));
        attributes.put("name", "Cloud Sync " + firstNonBlank(content.getName(), content.getId()));
        attributes.put("description", nullIfBlank(content.getDescription()));
        attributes.put("owner", firstNonBlank(content.getCreator(), content.getModifier(), "system"));
        attributes.put("createTime", toEpochMillis(content.getCreated()));
        attributes.put("modifiedTime", toEpochMillis(content.getModified()));
        attributes.put("repositoryId", repositoryId);
        attributes.put("objectId", content.getId());
        attributes.put("cloudProvider", PurviewCloudMetadataSupport.getCloudProvider(content));
        attributes.put("externalStableKey", stableKey);
        attributes.put("targetDescription", firstNonBlank(
                PurviewCloudMetadataSupport.getCloudFileUrl(content),
                PurviewCloudMetadataSupport.getExternalFileId(content)));

        Map<String, Object> relationshipAttributes = new LinkedHashMap<>();
        relationshipAttributes.put("inputs", List.of(
                relationshipEnd(EXTERNAL_ASSET_TYPE_NAME, buildExternalAssetQualifiedName(repositoryId, stableKey))));
        relationshipAttributes.put("outputs", List.of(
                relationshipEnd(DOCUMENT_TYPE_NAME, buildObjectQualifiedName(repositoryId, content.getId()))));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", CLOUD_SYNC_PROCESS_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("relationshipAttributes", relationshipAttributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    public Map<String, Object> buildArchiveProcessEntity(String repositoryId, Archive archive) {
        String stableKey = requireArchiveExternalStableKey(archive);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("qualifiedName", buildArchiveProcessQualifiedName(repositoryId, archive.getId()));
        attributes.put("name", "Archive " + firstNonBlank(archive.getName(), archive.getOriginalId(), archive.getId()));
        attributes.put("description", null);
        attributes.put("owner", firstNonBlank(archive.getArchivedBy(), archive.getCreator(), "system"));
        attributes.put("createTime", firstNonZero(
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getCreated())));
        attributes.put("modifiedTime", firstNonZero(
                toEpochMillis(archive.getColdArchivedAt()),
                toEpochMillis(archive.getArchivedAt()),
                toEpochMillis(archive.getModified()),
                toEpochMillis(archive.getCreated())));
        attributes.put("repositoryId", repositoryId);
        attributes.put("archiveId", archive.getId());
        attributes.put("archiveState", archive.getEffectiveArchiveState());
        attributes.put("externalStableKey", stableKey);
        attributes.put("targetDescription", firstNonBlank(stableKey, archive.getPath()));

        Map<String, Object> relationshipAttributes = new LinkedHashMap<>();
        relationshipAttributes.put("inputs", List.of(
                relationshipEnd(DOCUMENT_TYPE_NAME, buildObjectQualifiedName(repositoryId, archive.getOriginalId()))));
        relationshipAttributes.put("outputs", List.of(
                relationshipEnd(ARCHIVE_TYPE_NAME, buildArchiveQualifiedName(repositoryId, archive.getId())),
                relationshipEnd(EXTERNAL_ASSET_TYPE_NAME, buildExternalAssetQualifiedName(repositoryId, stableKey))));

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", ARCHIVE_PROCESS_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("relationshipAttributes", relationshipAttributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(archive.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(archive.getArchivedBy(), archive.getModifier(), archive.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    private String buildRepositoryQualifiedName(String repositoryId) {
        return "nemaki://" + repositoryId;
    }

    private String buildObjectQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    private String buildTypeDefinitionQualifiedName(String repositoryId, String typeId) {
        return "nemaki://" + repositoryId + "/types/" + typeId;
    }

    private String buildArchiveQualifiedName(String repositoryId, String archiveId) {
        return "nemaki://" + repositoryId + "/archives/" + archiveId;
    }

    public String buildExternalAssetQualifiedName(String repositoryId, String stableKey) {
        return "nemaki://" + repositoryId + "/external-assets/"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(stableKey.getBytes(StandardCharsets.UTF_8));
    }

    private String buildArchiveProcessQualifiedName(String repositoryId, String archiveId) {
        return "nemaki://" + repositoryId + "/archive-processes/" + archiveId;
    }

    public String buildCloudSyncProcessQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/cloud-sync-processes/" + objectId;
    }

    private String resolveArchiveSourceSystem(Archive archive) {
        if (archive.getContentRef() == null) {
            return null;
        }
        return nullIfBlank(archive.getContentRef().get("type"));
    }

    private String requireArchiveExternalStableKey(Archive archive) {
        String stableKey = resolveArchiveExternalStableKey(archive);
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalStateException("Archive does not have a stable external target");
        }
        return stableKey;
    }

    private String requireCloudExternalStableKey(Content content) {
        String stableKey = resolveCloudExternalStableKey(content);
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalStateException("Content does not have a stable cloud target");
        }
        return stableKey;
    }

    private Map<String, Object> relationshipEnd(String typeName, String qualifiedName) {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("typeName", typeName);
        end.put("uniqueAttributes", Map.of("qualifiedName", qualifiedName));
        return end;
    }

    private long toEpochMillis(java.util.GregorianCalendar value) {
        return value == null ? 0L : value.getTimeInMillis();
    }

    private Long zeroToNull(long value) {
        return value == 0L ? null : value;
    }

    private long firstNonZero(long... values) {
        for (long value : values) {
            if (value > 0L) {
                return value;
            }
        }
        return 0L;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
