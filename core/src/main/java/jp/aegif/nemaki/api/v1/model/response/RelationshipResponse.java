package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;
import jp.aegif.nemaki.api.v1.model.PropertyValue;

import java.util.Map;

@Schema(description = "Relationship response")
public class RelationshipResponse {
    
    @Schema(description = "Object ID")
    private String objectId;
    
    @Schema(description = "Object type ID")
    private String objectTypeId;
    
    @Schema(description = "Relationship name")
    private String name;
    
    @Schema(description = "Source object ID")
    private String sourceId;
    
    @Schema(description = "Target object ID")
    private String targetId;
    
    @Schema(description = "Created by user")
    private String createdBy;
    
    @Schema(description = "Creation date (ISO 8601)")
    private String creationDate;
    
    @Schema(description = "Last modified by user")
    private String lastModifiedBy;
    
    @Schema(description = "Last modification date (ISO 8601)")
    private String lastModificationDate;
    
    @Schema(description = "All properties with type information")
    private Map<String, PropertyValue> properties;
    
    @Schema(description = "HATEOAS links")
    private Map<String, LinkInfo> links;
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
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
    
    public String getSourceId() {
        return sourceId;
    }
    
    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }
    
    public String getTargetId() {
        return targetId;
    }
    
    public void setTargetId(String targetId) {
        this.targetId = targetId;
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
    
    public Map<String, PropertyValue> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, PropertyValue> properties) {
        this.properties = properties;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
