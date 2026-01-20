package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for creating a new group")
public class GroupCreateRequest {
    
    @Schema(description = "Group ID", required = true, example = "developers")
    @JsonProperty("groupId")
    private String groupId;
    
    @Schema(description = "Display name", required = true, example = "Developers Team")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "List of user IDs to add as members")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "List of group IDs to add as sub-groups")
    @JsonProperty("groups")
    private List<String> groups;
    
    public String getGroupId() {
        return groupId;
    }
    
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
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
