package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Paginated list of CMIS objects")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectListResponse {
    
    @Schema(description = "List of objects")
    @JsonProperty("objects")
    private List<ObjectResponse> objects;
    
    @Schema(description = "Total number of items available", example = "150")
    @JsonProperty("numItems")
    private Long numItems;
    
    @Schema(description = "Whether there are more items available", example = "true")
    @JsonProperty("hasMoreItems")
    private Boolean hasMoreItems;
    
    @Schema(description = "Number of items skipped", example = "0")
    @JsonProperty("skipCount")
    private Long skipCount;
    
    @Schema(description = "Maximum number of items returned", example = "100")
    @JsonProperty("maxItems")
    private Long maxItems;
    
    @Schema(description = "HATEOAS links for pagination")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public ObjectListResponse() {
    }
    
    public List<ObjectResponse> getObjects() {
        return objects;
    }
    
    public void setObjects(List<ObjectResponse> objects) {
        this.objects = objects;
    }
    
    public Long getNumItems() {
        return numItems;
    }
    
    public void setNumItems(Long numItems) {
        this.numItems = numItems;
    }
    
    public Boolean getHasMoreItems() {
        return hasMoreItems;
    }
    
    public void setHasMoreItems(Boolean hasMoreItems) {
        this.hasMoreItems = hasMoreItems;
    }
    
    public Long getSkipCount() {
        return skipCount;
    }
    
    public void setSkipCount(Long skipCount) {
        this.skipCount = skipCount;
    }
    
    public Long getMaxItems() {
        return maxItems;
    }
    
    public void setMaxItems(Long maxItems) {
        this.maxItems = maxItems;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
