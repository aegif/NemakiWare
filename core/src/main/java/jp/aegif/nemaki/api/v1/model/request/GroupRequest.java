package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Group creation/update request")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupRequest {
    
    @Schema(description = "Group ID (required for creation)", example = "developers")
    @JsonProperty("groupId")
    private String groupId;
    
    @Schema(description = "Group display name", example = "Developers")
    @JsonProperty("groupName")
    private String groupName;
    
    @Schema(description = "User IDs to add as members")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "Group IDs to add as members (nested groups)")
    @JsonProperty("groups")
    private List<String> groups;
    
    public GroupRequest() {
    }
    
    public String getGroupId() {
        return groupId;
    }
    
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
    
    public String getGroupName() {
        return groupName;
    }
    
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
    
    public List<String> getUsers() {
        return users;
    }
    
    public void setUsers(List<String> users) {
        this.users = users;
    }
    
    public List<String> getGroups() {
        return groups;
    }
    
    public void setGroups(List<String> groups) {
        this.groups = groups;
    }
}
