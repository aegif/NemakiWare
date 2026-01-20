package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Group information response")
public class GroupResponse {
    
    @Schema(description = "Group ID", example = "developers")
    @JsonProperty("groupId")
    private String groupId;
    
    @Schema(description = "Display name", example = "Developers Team")
    @JsonProperty("name")
    private String name;
    
    @Schema(description = "List of user IDs that are members of this group")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "List of group IDs that are sub-groups of this group")
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
