package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "User information response")
public class UserResponse {
    
    @Schema(description = "User ID (login name)", example = "john.doe")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "Display name", example = "John Doe")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "First name", example = "John")
    @JsonProperty("firstName")
    private String firstName;
    
    @Schema(description = "Last name", example = "Doe")
    @JsonProperty("lastName")
    private String lastName;
    
    @Schema(description = "Email address", example = "john.doe@example.com")
    @JsonProperty("email")
    private String email;
    
    @Schema(description = "Whether the user is an administrator", example = "false")
    @JsonProperty("isAdmin")
    private Boolean isAdmin;
    
    @Schema(description = "List of group IDs the user belongs to")
    @JsonProperty("groups")
    private List<String> groups;
    
    @Schema(description = "Creation date in ISO 8601 format")
    @JsonProperty("created")
    private String created;
    
    @Schema(description = "Last modification date in ISO 8601 format")
    @JsonProperty("modified")
    private String modified;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
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
    
    public String getFirstName() {
        return firstName;
    }
    
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }
    
    public String getLastName() {
        return lastName;
    }
    
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
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
    
    public String getCreated() {
        return created;
    }
    
    public void setCreated(String created) {
        this.created = created;
    }
    
    public String getModified() {
        return modified;
    }
    
    public void setModified(String modified) {
        this.modified = modified;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
