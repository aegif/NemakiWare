package jp.aegif.nemaki.rest.purview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;

@Component
public class PurviewEntityPayloadFactory {

    public Map<String, Object> buildBulkPayload(List<Map<String, Object>> entities) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referredEntities", Map.of());
        payload.put("entities", entities);
        return payload;
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

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", "nemaki_document");
        entity.put("attributes", attributes);
        entity.put("status", "ACTIVE");
        entity.put("createdBy", firstNonBlank(content.getCreator(), "system"));
        entity.put("updatedBy", firstNonBlank(content.getModifier(), content.getCreator(), "system"));
        entity.put("version", 0);
        return entity;
    }

    private String buildObjectQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    private long toEpochMillis(java.util.GregorianCalendar value) {
        return value == null ? 0L : value.getTimeInMillis();
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
