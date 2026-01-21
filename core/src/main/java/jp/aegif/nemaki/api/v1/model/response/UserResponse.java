package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "User response with user details and metadata")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    
    @Schema(description = "User ID", example = "admin")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "User display name", example = "Administrator")
    @JsonProperty("userName")
    private String userName;
    
    @Schema(description = "User first name", example = "Admin")
    @JsonProperty("firstName")
    private String firstName;
    
    @Schema(description = "User last name", example = "User")
    @JsonProperty("lastName")
    private String lastName;
    
    @Schema(description = "User email address", example = "admin@example.com")
    @JsonProperty("email")
    private String email;
    
    @Schema(description = "Whether the user is an administrator")
    @JsonProperty("isAdmin")
    private Boolean isAdmin;
    
    @Schema(description = "User type", example = "nemaki:user")
    @JsonProperty("type")
    private String type;
    
    @Schema(description = "Groups the user belongs to")
    @JsonProperty("groups")
    private List<String> groups;
    
    @Schema(description = "User's favorite object IDs")
    @JsonProperty("favorites")
    private List<String> favorites;
    
    @Schema(description = "Creator username", example = "system")
    @JsonProperty("createdBy")
    private String createdBy;
    
    @Schema(description = "Creation date in ISO 8601 format", example = "2026-01-19T09:00:00Z")
    @JsonProperty("creationDate")
    private String creationDate;
    
    @Schema(description = "Last modifier username", example = "admin")
    @JsonProperty("lastModifiedBy")
    private String lastModifiedBy;
    
    @Schema(description = "Last modification date in ISO 8601 format", example = "2026-01-19T10:30:00Z")
    @JsonProperty("lastModificationDate")
    private String lastModificationDate;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public UserResponse() {
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getUserName() {
        return userName;
    }
    
    public void setUserName(String userName) {
        this.userName = userName;
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
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public List<String> getGroups() {
        return groups;
    }
    
    public void setGroups(List<String> groups) {
        this.groups = groups;
    }
    
    public List<String> getFavorites() {
        return favorites;
    }
    
    public void setFavorites(List<String> favorites) {
        this.favorites = favorites;
    }
    
    public String getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
    
    public String getCreationDate() {
        return creationDate;
    }
    
    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }
    
    public String getLastModifiedBy() {
        return lastModifiedBy;
    }
    
    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }
    
    public String getLastModificationDate() {
        return lastModificationDate;
    }
    
    public void setLastModificationDate(String lastModificationDate) {
        this.lastModificationDate = lastModificationDate;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
