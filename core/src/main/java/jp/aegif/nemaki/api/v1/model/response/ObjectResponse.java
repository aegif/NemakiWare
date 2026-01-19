package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jp.aegif.nemaki.api.v1.model.PropertyValue;

import java.util.List;
import java.util.Map;

@Schema(description = "CMIS object response with properties and metadata")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectResponse {
    
    @Schema(description = "Object ID", example = "obj-12345")
    @JsonProperty("objectId")
    private String objectId;
    
    @Schema(description = "Base type ID", example = "cmis:document")
    @JsonProperty("baseTypeId")
    private String baseTypeId;
    
    @Schema(description = "Object type ID", example = "cmis:document")
    @JsonProperty("objectTypeId")
    private String objectTypeId;
    
    @Schema(description = "Object name", example = "invoice-2026-001.pdf")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "Object description")
    @JsonProperty("description")
    private String description;
    
    @Schema(description = "Creator username", example = "admin")
    @JsonProperty("createdBy")
    private String createdBy;
    
    @Schema(description = "Creation date in ISO 8601 format", example = "2026-01-19T09:00:00Z")
    @JsonProperty("creationDate")
    private String creationDate;
    
    @Schema(description = "Last modifier username", example = "admin")
    @JsonProperty("lastModifiedBy")
    private String lastModifiedBy;
    
    @Schema(description = "Last modification date in ISO 8601 format", example = "2026-01-19T10:30:00Z")
    @JsonProperty("lastModificationDate")
    private String lastModificationDate;
    
    @Schema(description = "Change token for optimistic locking")
    @JsonProperty("changeToken")
    private String changeToken;
    
    @Schema(description = "Parent folder ID (for fileable objects)")
    @JsonProperty("parentId")
    private String parentId;
    
    @Schema(description = "Object path (for fileable objects)", example = "/invoices/2026/invoice-001.pdf")
    @JsonProperty("path")
    private String path;
    
    @Schema(description = "Whether this is the latest version")
    @JsonProperty("isLatestVersion")
    private Boolean isLatestVersion;
    
    @Schema(description = "Whether this is the latest major version")
    @JsonProperty("isLatestMajorVersion")
    private Boolean isLatestMajorVersion;
    
    @Schema(description = "Whether this is a major version")
    @JsonProperty("isMajorVersion")
    private Boolean isMajorVersion;
    
    @Schema(description = "Version label", example = "1.0")
    @JsonProperty("versionLabel")
    private String versionLabel;
    
    @Schema(description = "Version series ID")
    @JsonProperty("versionSeriesId")
    private String versionSeriesId;
    
    @Schema(description = "Whether the version series is checked out")
    @JsonProperty("isVersionSeriesCheckedOut")
    private Boolean isVersionSeriesCheckedOut;
    
    @Schema(description = "User who checked out the version series")
    @JsonProperty("versionSeriesCheckedOutBy")
    private String versionSeriesCheckedOutBy;
    
    @Schema(description = "ID of the private working copy")
    @JsonProperty("versionSeriesCheckedOutId")
    private String versionSeriesCheckedOutId;
    
    @Schema(description = "Checkin comment")
    @JsonProperty("checkinComment")
    private String checkinComment;
    
    @Schema(description = "Content stream length in bytes")
    @JsonProperty("contentStreamLength")
    private Long contentStreamLength;
    
    @Schema(description = "Content stream MIME type", example = "application/pdf")
    @JsonProperty("contentStreamMimeType")
    private String contentStreamMimeType;
    
    @Schema(description = "Content stream file name", example = "invoice-001.pdf")
    @JsonProperty("contentStreamFileName")
    private String contentStreamFileName;
    
    @Schema(description = "Content stream ID")
    @JsonProperty("contentStreamId")
    private String contentStreamId;
    
    @Schema(description = "Whether this object is immutable")
    @JsonProperty("isImmutable")
    private Boolean isImmutable;
    
    @Schema(description = "Secondary type IDs")
    @JsonProperty("secondaryTypeIds")
    private List<String> secondaryTypeIds;
    
    @Schema(description = "Object properties with 2-layer structure (value/type)")
    @JsonProperty("properties")
    private Map<String, PropertyValue> properties;
    
    @Schema(description = "Allowable actions for this object")
    @JsonProperty("allowableActions")
    private AllowableActionsResponse allowableActions;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public ObjectResponse() {
    }
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public String getBaseTypeId() {
        return baseTypeId;
    }
    
    public void setBaseTypeId(String baseTypeId) {
        this.baseTypeId = baseTypeId;
    }
    
    public String getObjectTypeId() {
        return objectTypeId;
    }
    
    public void setObjectTypeId(String objectTypeId) {
        this.objectTypeId = objectTypeId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
    
    public String getCreationDate() {
        return creationDate;
    }
    
    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }
    
    public String getLastModifiedBy() {
        return lastModifiedBy;
    }
    
    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }
    
    public String getLastModificationDate() {
        return lastModificationDate;
    }
    
    public void setLastModificationDate(String lastModificationDate) {
        this.lastModificationDate = lastModificationDate;
    }
    
    public String getChangeToken() {
        return changeToken;
    }
    
    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
    }
    
    public String getParentId() {
        return parentId;
    }
    
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public Boolean getIsLatestVersion() {
        return isLatestVersion;
    }
    
    public void setIsLatestVersion(Boolean isLatestVersion) {
        this.isLatestVersion = isLatestVersion;
    }
    
    public Boolean getIsLatestMajorVersion() {
        return isLatestMajorVersion;
    }
    
    public void setIsLatestMajorVersion(Boolean isLatestMajorVersion) {
        this.isLatestMajorVersion = isLatestMajorVersion;
    }
    
    public Boolean getIsMajorVersion() {
        return isMajorVersion;
    }
    
    public void setIsMajorVersion(Boolean isMajorVersion) {
        this.isMajorVersion = isMajorVersion;
    }
    
    public String getVersionLabel() {
        return versionLabel;
    }
    
    public void setVersionLabel(String versionLabel) {
        this.versionLabel = versionLabel;
    }
    
    public String getVersionSeriesId() {
        return versionSeriesId;
    }
    
    public void setVersionSeriesId(String versionSeriesId) {
        this.versionSeriesId = versionSeriesId;
    }
    
    public Boolean getIsVersionSeriesCheckedOut() {
        return isVersionSeriesCheckedOut;
    }
    
    public void setIsVersionSeriesCheckedOut(Boolean isVersionSeriesCheckedOut) {
        this.isVersionSeriesCheckedOut = isVersionSeriesCheckedOut;
    }
    
    public String getVersionSeriesCheckedOutBy() {
        return versionSeriesCheckedOutBy;
    }
    
    public void setVersionSeriesCheckedOutBy(String versionSeriesCheckedOutBy) {
        this.versionSeriesCheckedOutBy = versionSeriesCheckedOutBy;
    }
    
    public String getVersionSeriesCheckedOutId() {
        return versionSeriesCheckedOutId;
    }
    
    public void setVersionSeriesCheckedOutId(String versionSeriesCheckedOutId) {
        this.versionSeriesCheckedOutId = versionSeriesCheckedOutId;
    }
    
    public String getCheckinComment() {
        return checkinComment;
    }
    
    public void setCheckinComment(String checkinComment) {
        this.checkinComment = checkinComment;
    }
    
    public Long getContentStreamLength() {
        return contentStreamLength;
    }
    
    public void setContentStreamLength(Long contentStreamLength) {
        this.contentStreamLength = contentStreamLength;
    }
    
    public String getContentStreamMimeType() {
        return contentStreamMimeType;
    }
    
    public void setContentStreamMimeType(String contentStreamMimeType) {
        this.contentStreamMimeType = contentStreamMimeType;
    }
    
    public String getContentStreamFileName() {
        return contentStreamFileName;
    }
    
    public void setContentStreamFileName(String contentStreamFileName) {
        this.contentStreamFileName = contentStreamFileName;
    }
    
    public String getContentStreamId() {
        return contentStreamId;
    }
    
    public void setContentStreamId(String contentStreamId) {
        this.contentStreamId = contentStreamId;
    }
    
    public Boolean getIsImmutable() {
        return isImmutable;
    }
    
    public void setIsImmutable(Boolean isImmutable) {
        this.isImmutable = isImmutable;
    }
    
    public List<String> getSecondaryTypeIds() {
        return secondaryTypeIds;
    }
    
    public void setSecondaryTypeIds(List<String> secondaryTypeIds) {
        this.secondaryTypeIds = secondaryTypeIds;
    }
    
    public Map<String, PropertyValue> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, PropertyValue> properties) {
        this.properties = properties;
    }
    
    public AllowableActionsResponse getAllowableActions() {
        return allowableActions;
    }
    
    public void setAllowableActions(AllowableActionsResponse allowableActions) {
        this.allowableActions = allowableActions;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
