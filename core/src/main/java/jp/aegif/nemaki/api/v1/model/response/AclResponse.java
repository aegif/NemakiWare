package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "ACL response")
public class AclResponse {
    
    @Schema(description = "Object ID")
    private String objectId;
    
    @Schema(description = "Whether the ACL is exact (not inherited)")
    private Boolean exact;
    
    @Schema(description = "List of ACE entries")
    private List<AceEntry> aces;
    
    @Schema(description = "HATEOAS links")
    private Map<String, LinkInfo> links;
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public Boolean getExact() {
        return exact;
    }
    
    public void setExact(Boolean exact) {
        this.exact = exact;
    }
    
    public List<AceEntry> getAces() {
        return aces;
    }
    
    public void setAces(List<AceEntry> aces) {
        this.aces = aces;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
    
    @Schema(description = "Access Control Entry")
    public static class AceEntry {
        
        @Schema(description = "Principal ID (user or group)")
        private String principalId;
        
        @Schema(description = "List of permissions")
        private List<String> permissions;
        
        @Schema(description = "Whether this ACE is directly applied (not inherited)")
        private Boolean direct;
        
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
        
        public Boolean getDirect() {
            return direct;
        }
        
        public void setDirect(Boolean direct) {
            this.direct = direct;
        }
    }
}
