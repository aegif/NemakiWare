package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for updating a user")
public class UserUpdateRequest {
    
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
    
    @Schema(description = "List of group IDs to set for the user (replaces existing groups)")
    @JsonProperty("groups")
    private List<String> groups;
    
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
}
