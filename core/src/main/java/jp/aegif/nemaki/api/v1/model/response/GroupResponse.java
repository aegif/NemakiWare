package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Group response with group details and metadata")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupResponse {
    
    @Schema(description = "Group ID", example = "administrators")
    @JsonProperty("groupId")
    private String groupId;
    
    @Schema(description = "Group display name", example = "Administrators")
    @JsonProperty("groupName")
    private String groupName;
    
    @Schema(description = "Group type", example = "nemaki:group")
    @JsonProperty("type")
    private String type;
    
    @Schema(description = "User IDs that are members of this group")
    @JsonProperty("users")
    private List<String> users;
    
    @Schema(description = "Group IDs that are members of this group (nested groups)")
    @JsonProperty("groups")
    private List<String> groups;
    
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
    
    public GroupResponse() {
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
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
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
