package jp.aegif.nemaki.api.v1.model.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Policy apply/remove request")
public class PolicyApplyRequest {
    
    @Schema(description = "Policy ID to apply/remove", required = true)
    private String policyId;
    
    @Schema(description = "Object ID to apply/remove policy to/from", required = true)
    private String objectId;
    
    public String getPolicyId() {
        return policyId;
    }
    
    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
}
