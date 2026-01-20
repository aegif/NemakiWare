package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Group list response")
public class GroupListResponse {
    
    @Schema(description = "List of groups")
    @JsonProperty("groups")
    private List<GroupResponse> groups;
    
    @Schema(description = "Total number of groups")
    @JsonProperty("totalCount")
    private Integer totalCount;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public List<GroupResponse> getGroups() {
        return groups;
    }
    
    public void setGroups(List<GroupResponse> groups) {
        this.groups = groups;
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
