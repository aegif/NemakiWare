package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for creating a new user")
public class UserCreateRequest {
    
    @Schema(description = "User ID (login name)", required = true, example = "john.doe")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "Display name", required = true, example = "John Doe")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "Password", required = true, example = "password123")
    @JsonProperty("password")
    private String password;
    
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
    
    @Schema(description = "List of group IDs to add the user to")
    @JsonProperty("groups")
    private List<String> groups;
    
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
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
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
