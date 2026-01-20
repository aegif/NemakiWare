package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Authentication response")
public class AuthResponse {
    
    @Schema(description = "Authentication token (JWT or session token)")
    @JsonProperty("token")
    private String token;
    
    @Schema(description = "Token type (e.g., 'Bearer')")
    @JsonProperty("tokenType")
    private String tokenType;
    
    @Schema(description = "Token expiration time in seconds")
    @JsonProperty("expiresIn")
    private Long expiresIn;
    
    @Schema(description = "User ID")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "User display name")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "Whether the user is an administrator")
    @JsonProperty("isAdmin")
    private Boolean isAdmin;
    
    @Schema(description = "List of group IDs the user belongs to")
    @JsonProperty("groups")
    private List<String> groups;
    
    @Schema(description = "Repository ID")
    @JsonProperty("repositoryId")
    private String repositoryId;
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public String getTokenType() {
        return tokenType;
    }
    
    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }
    
    public Long getExpiresIn() {
        return expiresIn;
    }
    
    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public Boolean getIsAdmin() {
        return isAdmin;
    }
    
    public void setIsAdmin(Boolean isAdmin) {
        this.isAdmin = isAdmin;
    }
    
    public List<String> getGroups() {
        return groups;
    }
    
    public void setGroups(List<String> groups) {
        this.groups = groups;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
}
