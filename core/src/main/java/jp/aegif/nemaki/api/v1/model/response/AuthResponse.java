package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Authentication response with token and user information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    
    @Schema(description = "Authentication token", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    @JsonProperty("token")
    private String token;
    
    @Schema(description = "Token expiration timestamp in milliseconds", example = "1737331200000")
    @JsonProperty("expiresAt")
    private Long expiresAt;
    
    @Schema(description = "Repository ID", example = "bedroom")
    @JsonProperty("repositoryId")
    private String repositoryId;
    
    @Schema(description = "Authenticated user information")
    @JsonProperty("user")
    private UserResponse user;
    
    public AuthResponse() {
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public Long getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public UserResponse getUser() {
        return user;
    }
    
    public void setUser(UserResponse user) {
        this.user = user;
    }
}
