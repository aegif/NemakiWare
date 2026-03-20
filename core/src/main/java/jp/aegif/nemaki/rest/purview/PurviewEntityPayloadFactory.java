package jp.aegif.nemaki.rest.purview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

@Component
public class PurviewEntityPayloadFactory {

    private static final String REPOSITORY_TYPE_NAME = "nemaki_repository";
    private static final String FOLDER_TYPE_NAME = "nemaki_folder";
    private static final String DOCUMENT_TYPE_NAME = "nemaki_document";
    private static final String ARCHIVE_TYPE_NAME = "nemaki_archive";
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

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", DOCUMENT_TYPE_NAME);
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
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

    private String buildRepositoryQualifiedName(String repositoryId) {
        return "nemaki://" + repositoryId;
    }

    private String buildObjectQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    private String buildArchiveQualifiedName(String repositoryId, String archiveId) {
        return "nemaki://" + repositoryId + "/archives/" + archiveId;
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
