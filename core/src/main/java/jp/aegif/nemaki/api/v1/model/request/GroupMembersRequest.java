package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for adding or removing group members")
public class GroupMembersRequest {
    
    @Schema(description = "List of user IDs to add or remove")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "List of group IDs to add or remove as sub-groups")
    @JsonProperty("groups")
    private List<String> groups;
    
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
