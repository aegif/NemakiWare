package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Password change request")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PasswordChangeRequest {
    
    @Schema(description = "Current password", required = true)
    @JsonProperty("oldPassword")
    private String oldPassword;
    
    @Schema(description = "New password", required = true)
    @JsonProperty("newPassword")
    private String newPassword;
    
    public PasswordChangeRequest() {
    }
    
    public String getOldPassword() {
        return oldPassword;
    }
    
    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }
    
    public String getNewPassword() {
        return newPassword;
    }
    
    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
