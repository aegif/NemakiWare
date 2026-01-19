package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Repository information response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepositoryInfoResponse {
    
    @Schema(description = "Repository ID", example = "bedroom")
    @JsonProperty("repositoryId")
    private String repositoryId;
    
    @Schema(description = "Repository name", example = "Bedroom Repository")
    @JsonProperty("repositoryName")
    private String repositoryName;
    
    @Schema(description = "Repository description", example = "Main content repository")
    @JsonProperty("repositoryDescription")
    private String repositoryDescription;
    
    @Schema(description = "Vendor name", example = "aegif")
    @JsonProperty("vendorName")
    private String vendorName;
    
    @Schema(description = "Product name", example = "NemakiWare")
    @JsonProperty("productName")
    private String productName;
    
    @Schema(description = "Product version", example = "3.0.0")
    @JsonProperty("productVersion")
    private String productVersion;
    
    @Schema(description = "Root folder ID")
    @JsonProperty("rootFolderId")
    private String rootFolderId;
    
    @Schema(description = "CMIS version supported", example = "1.1")
    @JsonProperty("cmisVersionSupported")
    private String cmisVersionSupported;
    
    @Schema(description = "Principal ID for anonymous user")
    @JsonProperty("principalIdAnonymous")
    private String principalIdAnonymous;
    
    @Schema(description = "Principal ID for anyone")
    @JsonProperty("principalIdAnyone")
    private String principalIdAnyone;
    
    @Schema(description = "Thin client URI")
    @JsonProperty("thinClientUri")
    private String thinClientUri;
    
    @Schema(description = "Whether changes on type are supported")
    @JsonProperty("changesOnType")
    private Boolean changesOnType;
    
    @Schema(description = "Latest change log token")
    @JsonProperty("latestChangeLogToken")
    private String latestChangeLogToken;
    
    @Schema(description = "Repository capabilities")
    @JsonProperty("capabilities")
    private RepositoryCapabilities capabilities;
    
    @Schema(description = "ACL capabilities")
    @JsonProperty("aclCapabilities")
    private AclCapabilities aclCapabilities;
    
    @Schema(description = "HATEOAS links")
    @JsonProperty("_links")
    private Map<String, LinkInfo> links;
    
    public RepositoryInfoResponse() {
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public String getRepositoryName() {
        return repositoryName;
    }
    
    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }
    
    public String getRepositoryDescription() {
        return repositoryDescription;
    }
    
    public void setRepositoryDescription(String repositoryDescription) {
        this.repositoryDescription = repositoryDescription;
    }
    
    public String getVendorName() {
        return vendorName;
    }
    
    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }
    
    public String getProductName() {
        return productName;
    }
    
    public void setProductName(String productName) {
        this.productName = productName;
    }
    
    public String getProductVersion() {
        return productVersion;
    }
    
    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }
    
    public String getRootFolderId() {
        return rootFolderId;
    }
    
    public void setRootFolderId(String rootFolderId) {
        this.rootFolderId = rootFolderId;
    }
    
    public String getCmisVersionSupported() {
        return cmisVersionSupported;
    }
    
    public void setCmisVersionSupported(String cmisVersionSupported) {
        this.cmisVersionSupported = cmisVersionSupported;
    }
    
    public String getPrincipalIdAnonymous() {
        return principalIdAnonymous;
    }
    
    public void setPrincipalIdAnonymous(String principalIdAnonymous) {
        this.principalIdAnonymous = principalIdAnonymous;
    }
    
    public String getPrincipalIdAnyone() {
        return principalIdAnyone;
    }
    
    public void setPrincipalIdAnyone(String principalIdAnyone) {
        this.principalIdAnyone = principalIdAnyone;
    }
    
    public String getThinClientUri() {
        return thinClientUri;
    }
    
    public void setThinClientUri(String thinClientUri) {
        this.thinClientUri = thinClientUri;
    }
    
    public Boolean getChangesOnType() {
        return changesOnType;
    }
    
    public void setChangesOnType(Boolean changesOnType) {
        this.changesOnType = changesOnType;
    }
    
    public String getLatestChangeLogToken() {
        return latestChangeLogToken;
    }
    
    public void setLatestChangeLogToken(String latestChangeLogToken) {
        this.latestChangeLogToken = latestChangeLogToken;
    }
    
    public RepositoryCapabilities getCapabilities() {
        return capabilities;
    }
    
    public void setCapabilities(RepositoryCapabilities capabilities) {
        this.capabilities = capabilities;
    }
    
    public AclCapabilities getAclCapabilities() {
        return aclCapabilities;
    }
    
    public void setAclCapabilities(AclCapabilities aclCapabilities) {
        this.aclCapabilities = aclCapabilities;
    }
    
    public Map<String, LinkInfo> getLinks() {
        return links;
    }
    
    public void setLinks(Map<String, LinkInfo> links) {
        this.links = links;
    }
}
