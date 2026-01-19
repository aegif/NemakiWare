package jp.aegif.nemaki.api.v1.model.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "ACL apply request")
public class AclRequest {
    
    @Schema(description = "List of ACE entries to apply", required = true)
    private List<AceEntry> aces;
    
    @Schema(description = "ACL propagation mode (REPOSITORYDETERMINED, OBJECTONLY, PROPAGATE)")
    private String aclPropagation;
    
    public List<AceEntry> getAces() {
        return aces;
    }
    
    public void setAces(List<AceEntry> aces) {
        this.aces = aces;
    }
    
    public String getAclPropagation() {
        return aclPropagation;
    }
    
    public void setAclPropagation(String aclPropagation) {
        this.aclPropagation = aclPropagation;
    }
    
    @Schema(description = "Access Control Entry")
    public static class AceEntry {
        
        @Schema(description = "Principal ID (user or group)", required = true)
        private String principalId;
        
        @Schema(description = "List of permissions", required = true)
        private List<String> permissions;
        
        public String getPrincipalId() {
            return principalId;
        }
        
        public void setPrincipalId(String principalId) {
            this.principalId = principalId;
        }
        
        public List<String> getPermissions() {
            return permissions;
        }
        
        public void setPermissions(List<String> permissions) {
            this.permissions = permissions;
        }
    }
}
