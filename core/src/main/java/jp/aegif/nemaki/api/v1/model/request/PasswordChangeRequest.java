package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for changing a user's password")
public class PasswordChangeRequest {
    
    @Schema(description = "Current password (required for non-admin users changing their own password)")
    @JsonProperty("currentPassword")
    private String currentPassword;
    
    @Schema(description = "New password", required = true)
    @JsonProperty("newPassword")
    private String newPassword;
    
    public String getCurrentPassword() {
        return currentPassword;
    }
    
    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }
    
    public String getNewPassword() {
        return newPassword;
    }
    
    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
