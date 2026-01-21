package jp.aegif.nemaki.api.v1.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Group members add/remove request")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupMembersRequest {
    
    @Schema(description = "User IDs to add/remove")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "Group IDs to add/remove (nested groups)")
    @JsonProperty("groups")
    private List<String> groups;
    
    public GroupMembersRequest() {
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
