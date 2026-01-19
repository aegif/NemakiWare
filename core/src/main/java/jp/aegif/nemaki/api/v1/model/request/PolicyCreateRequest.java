package jp.aegif.nemaki.api.v1.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jp.aegif.nemaki.api.v1.model.PropertyValue;

import java.util.Map;

@Schema(description = "Policy creation request")
public class PolicyCreateRequest {
    
    @Schema(description = "Policy type ID (defaults to cmis:policy)")
    private String typeId;
    
    @Schema(description = "Policy name", required = true)
    private String name;
    
    @Schema(description = "Policy description")
    private String description;
    
    @Schema(description = "Policy text (implementation-specific)")
    private String policyText;
    
    @Schema(description = "Folder ID to file the policy in")
    private String folderId;
    
    @Schema(description = "Additional properties")
    private Map<String, PropertyValue> properties;
    
    public String getTypeId() {
        return typeId;
    }
    
    public void setTypeId(String typeId) {
        this.typeId = typeId;
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
    
    public String getPolicyText() {
        return policyText;
    }
    
    public void setPolicyText(String policyText) {
        this.policyText = policyText;
    }
    
    public String getFolderId() {
        return folderId;
    }
    
    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }
    
    public Map<String, PropertyValue> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, PropertyValue> properties) {
        this.properties = properties;
    }
}
