package jp.aegif.nemaki.api.v1.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Applied policies list response")
public class PolicyListResponse {
    
    @Schema(description = "Object ID that policies are applied to")
    private String objectId;
    
    @Schema(description = "List of applied policies")
    private List<ObjectResponse> policies;
    
    @Schema(description = "Number of applied policies")
    private Integer numPolicies;
    
    @Schema(description = "HATEOAS links")
    private Map<String, LinkInfo> links;
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public List<ObjectResponse> getPolicies() {
        return policies;
    }
    
    public void setPolicies(List<ObjectResponse> policies) {
        this.policies = policies;
    }
    
    public Integer getNumPolicies() {
        return numPolicies;
    }
    
    public void setNumPolicies(Integer numPolicies) {
        this.numPolicies = numPolicies;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
