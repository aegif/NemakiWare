package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Login request with credentials")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginRequest {
    
    @Schema(description = "User ID", required = true, example = "admin")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "Password", required = true)
    @JsonProperty("password")
    private String password;
    
    @Schema(description = "Repository ID (optional, can be specified in path)", example = "bedroom")
    @JsonProperty("repositoryId")
    private String repositoryId;
    
    public LoginRequest() {
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
}
