package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "List of groups response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupListResponse {
    
    @Schema(description = "List of groups")
    @JsonProperty("groups")
    private List<GroupResponse> groups;
    
    @Schema(description = "Total number of groups")
    @JsonProperty("numItems")
    private Integer numItems;
    
    @Schema(description = "Whether there are more results")
    @JsonProperty("hasMoreItems")
    private Boolean hasMoreItems;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public GroupListResponse() {
    }
    
    public List<GroupResponse> getGroups() {
        return groups;
    }
    
    public void setGroups(List<GroupResponse> groups) {
        this.groups = groups;
    }
    
    public Integer getNumItems() {
        return numItems;
    }
    
    public void setNumItems(Integer numItems) {
        this.numItems = numItems;
    }
    
    public Boolean getHasMoreItems() {
        return hasMoreItems;
    }
    
    public void setHasMoreItems(Boolean hasMoreItems) {
        this.hasMoreItems = hasMoreItems;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
