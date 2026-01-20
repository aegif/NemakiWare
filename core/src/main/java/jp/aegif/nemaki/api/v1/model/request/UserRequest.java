package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "User creation/update request")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRequest {
    
    @Schema(description = "User ID (required for creation)", example = "john.doe")
    @JsonProperty("userId")
    private String userId;
    
    @Schema(description = "User display name", example = "John Doe")
    @JsonProperty("userName")
    private String userName;
    
    @Schema(description = "User password (required for creation)")
    @JsonProperty("password")
    private String password;
    
    @Schema(description = "User first name", example = "John")
    @JsonProperty("firstName")
    private String firstName;
    
    @Schema(description = "User last name", example = "Doe")
    @JsonProperty("lastName")
    private String lastName;
    
    @Schema(description = "User email address", example = "john.doe@example.com")
    @JsonProperty("email")
    private String email;
    
    @Schema(description = "Groups to assign the user to")
    @JsonProperty("groups")
    private List<String> groups;
    
    public UserRequest() {
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
    
    public List<String> getGroups() {
        return groups;
    }
    
    public void setGroups(List<String> groups) {
        this.groups = groups;
    }
}
