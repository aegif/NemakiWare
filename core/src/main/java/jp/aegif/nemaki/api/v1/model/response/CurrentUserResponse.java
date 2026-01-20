package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Current user information response")
public class CurrentUserResponse {
    
    @Schema(description = "User ID (login name)")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "Display name")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "First name")
    @JsonProperty("firstName")
    private String firstName;
    
    @Schema(description = "Last name")
    @JsonProperty("lastName")
    private String lastName;
    
    @Schema(description = "Email address")
    @JsonProperty("email")
    private String email;
    
    @Schema(description = "Whether the user is an administrator")
    @JsonProperty("isAdmin")
    private Boolean isAdmin;
    
    @Schema(description = "List of group IDs the user belongs to")
    @JsonProperty("groups")
    private List<String> groups;
    
    @Schema(description = "List of available repository IDs")
    @JsonProperty("repositories")
    private List<String> repositories;
    
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
    
    public List<String> getRepositories() {
        return repositories;
    }
    
    public void setRepositories(List<String> repositories) {
        this.repositories = repositories;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
