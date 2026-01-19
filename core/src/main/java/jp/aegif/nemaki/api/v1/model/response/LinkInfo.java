package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "HATEOAS link information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkInfo {
    
    @Schema(description = "Link URL", example = "/api/v1/repositories/bedroom/objects/OBJECT_ID")
    @JsonProperty("href")
    private String href;
    
    @Schema(description = "HTTP method for the link", example = "GET")
    @JsonProperty("method")
    private String method;
    
    @Schema(description = "Link title/description", example = "Get object details")
    @JsonProperty("title")
    private String title;
    
    @Schema(description = "Media type of the linked resource", example = "application/json")
    @JsonProperty("type")
    private String type;
    
    public LinkInfo() {
    }
    
    public LinkInfo(String href) {
        this.href = href;
    }
    
    public LinkInfo(String href, String method) {
        this.href = href;
        this.method = method;
    }
    
    public static LinkInfo of(String href) {
        return new LinkInfo(href);
    }
    
    public static LinkInfo get(String href) {
        return new LinkInfo(href, "GET");
    }
    
    public static LinkInfo post(String href) {
        return new LinkInfo(href, "POST");
    }
    
    public static LinkInfo put(String href) {
        return new LinkInfo(href, "PUT");
    }
    
    public static LinkInfo delete(String href) {
        return new LinkInfo(href, "DELETE");
    }
    
    public String getHref() {
        return href;
    }
    
    public void setHref(String href) {
        this.href = href;
    }
    
    public String getMethod() {
        return method;
    }
    
    public void setMethod(String method) {
        this.method = method;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public LinkInfo withTitle(String title) {
        this.title = title;
        return this;
    }
    
    public LinkInfo withType(String type) {
        this.type = type;
        return this;
    }
}
