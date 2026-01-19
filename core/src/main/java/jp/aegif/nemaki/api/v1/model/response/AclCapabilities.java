package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "ACL capabilities")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AclCapabilities {
    
    @Schema(description = "Supported permissions", example = "basic")
    @JsonProperty("supportedPermissions")
    private String supportedPermissions;
    
    @Schema(description = "ACL propagation", example = "propagate")
    @JsonProperty("aclPropagation")
    private String aclPropagation;
    
    @Schema(description = "List of permissions")
    @JsonProperty("permissions")
    private List<PermissionDefinition> permissions;
    
    @Schema(description = "Permission mapping")
    @JsonProperty("permissionMapping")
    private Map<String, List<String>> permissionMapping;
    
    public AclCapabilities() {
    }
    
    public String getSupportedPermissions() {
        return supportedPermissions;
    }
    
    public void setSupportedPermissions(String supportedPermissions) {
        this.supportedPermissions = supportedPermissions;
    }
    
    public String getAclPropagation() {
        return aclPropagation;
    }
    
    public void setAclPropagation(String aclPropagation) {
        this.aclPropagation = aclPropagation;
    }
    
    public List<PermissionDefinition> getPermissions() {
        return permissions;
    }
    
    public void setPermissions(List<PermissionDefinition> permissions) {
        this.permissions = permissions;
    }
    
    public Map<String, List<String>> getPermissionMapping() {
        return permissionMapping;
    }
    
    public void setPermissionMapping(Map<String, List<String>> permissionMapping) {
        this.permissionMapping = permissionMapping;
    }
    
    @Schema(description = "Permission definition")
    public static class PermissionDefinition {
        
        @Schema(description = "Permission ID", example = "cmis:read")
        @JsonProperty("permission")
        private String permission;
        
        @Schema(description = "Permission description", example = "Read permission")
        @JsonProperty("description")
        private String description;
        
        public PermissionDefinition() {
        }
        
        public PermissionDefinition(String permission, String description) {
            this.permission = permission;
            this.description = description;
        }
        
        public String getPermission() {
            return permission;
        }
        
        public void setPermission(String permission) {
            this.permission = permission;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
    }
}
