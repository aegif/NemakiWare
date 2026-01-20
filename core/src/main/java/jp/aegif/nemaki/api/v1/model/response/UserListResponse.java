package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "User list response")
public class UserListResponse {
    
    @Schema(description = "List of users")
    @JsonProperty("users")
    private List<UserResponse> users;
    
    @Schema(description = "Total number of users")
    @JsonProperty("totalCount")
    private Integer totalCount;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public List<UserResponse> getUsers() {
        return users;
    }
    
    public void setUsers(List<UserResponse> users) {
        this.users = users;
    }
    
    public Integer getTotalCount() {
        return totalCount;
    }
    
    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
