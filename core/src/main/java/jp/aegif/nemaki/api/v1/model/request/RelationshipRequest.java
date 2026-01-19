package jp.aegif.nemaki.api.v1.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jp.aegif.nemaki.api.v1.model.PropertyValue;

import java.util.Map;

@Schema(description = "Relationship creation request")
public class RelationshipRequest {
    
    @Schema(description = "Relationship type ID (defaults to cmis:relationship)")
    private String typeId;
    
    @Schema(description = "Source object ID", required = true)
    private String sourceId;
    
    @Schema(description = "Target object ID", required = true)
    private String targetId;
    
    @Schema(description = "Relationship name")
    private String name;
    
    @Schema(description = "Additional properties")
    private Map<String, PropertyValue> properties;
    
    public String getTypeId() {
        return typeId;
    }
    
    public void setTypeId(String typeId) {
        this.typeId = typeId;
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
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public Map<String, PropertyValue> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, PropertyValue> properties) {
        this.properties = properties;
    }
}
