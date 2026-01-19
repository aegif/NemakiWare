package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Repository capabilities")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepositoryCapabilities {
    
    @Schema(description = "ACL capability", example = "manage")
    @JsonProperty("capabilityAcl")
    private String capabilityAcl;
    
    @Schema(description = "All versions searchable capability")
    @JsonProperty("capabilityAllVersionsSearchable")
    private Boolean capabilityAllVersionsSearchable;
    
    @Schema(description = "Changes capability", example = "objectidsonly")
    @JsonProperty("capabilityChanges")
    private String capabilityChanges;
    
    @Schema(description = "Content stream updates capability", example = "anytime")
    @JsonProperty("capabilityContentStreamUpdatability")
    private String capabilityContentStreamUpdatability;
    
    @Schema(description = "Get descendants capability")
    @JsonProperty("capabilityGetDescendants")
    private Boolean capabilityGetDescendants;
    
    @Schema(description = "Get folder tree capability")
    @JsonProperty("capabilityGetFolderTree")
    private Boolean capabilityGetFolderTree;
    
    @Schema(description = "Order by capability", example = "common")
    @JsonProperty("capabilityOrderBy")
    private String capabilityOrderBy;
    
    @Schema(description = "Multifiling capability")
    @JsonProperty("capabilityMultifiling")
    private Boolean capabilityMultifiling;
    
    @Schema(description = "PWC searchable capability")
    @JsonProperty("capabilityPwcSearchable")
    private Boolean capabilityPwcSearchable;
    
    @Schema(description = "PWC updatable capability")
    @JsonProperty("capabilityPwcUpdatable")
    private Boolean capabilityPwcUpdatable;
    
    @Schema(description = "Query capability", example = "bothcombined")
    @JsonProperty("capabilityQuery")
    private String capabilityQuery;
    
    @Schema(description = "Renditions capability", example = "read")
    @JsonProperty("capabilityRenditions")
    private String capabilityRenditions;
    
    @Schema(description = "Unfiling capability")
    @JsonProperty("capabilityUnfiling")
    private Boolean capabilityUnfiling;
    
    @Schema(description = "Version specific filing capability")
    @JsonProperty("capabilityVersionSpecificFiling")
    private Boolean capabilityVersionSpecificFiling;
    
    @Schema(description = "Join capability", example = "none")
    @JsonProperty("capabilityJoin")
    private String capabilityJoin;
    
    public RepositoryCapabilities() {
    }
    
    public String getCapabilityAcl() {
        return capabilityAcl;
    }
    
    public void setCapabilityAcl(String capabilityAcl) {
        this.capabilityAcl = capabilityAcl;
    }
    
    public Boolean getCapabilityAllVersionsSearchable() {
        return capabilityAllVersionsSearchable;
    }
    
    public void setCapabilityAllVersionsSearchable(Boolean capabilityAllVersionsSearchable) {
        this.capabilityAllVersionsSearchable = capabilityAllVersionsSearchable;
    }
    
    public String getCapabilityChanges() {
        return capabilityChanges;
    }
    
    public void setCapabilityChanges(String capabilityChanges) {
        this.capabilityChanges = capabilityChanges;
    }
    
    public String getCapabilityContentStreamUpdatability() {
        return capabilityContentStreamUpdatability;
    }
    
    public void setCapabilityContentStreamUpdatability(String capabilityContentStreamUpdatability) {
        this.capabilityContentStreamUpdatability = capabilityContentStreamUpdatability;
    }
    
    public Boolean getCapabilityGetDescendants() {
        return capabilityGetDescendants;
    }
    
    public void setCapabilityGetDescendants(Boolean capabilityGetDescendants) {
        this.capabilityGetDescendants = capabilityGetDescendants;
    }
    
    public Boolean getCapabilityGetFolderTree() {
        return capabilityGetFolderTree;
    }
    
    public void setCapabilityGetFolderTree(Boolean capabilityGetFolderTree) {
        this.capabilityGetFolderTree = capabilityGetFolderTree;
    }
    
    public String getCapabilityOrderBy() {
        return capabilityOrderBy;
    }
    
    public void setCapabilityOrderBy(String capabilityOrderBy) {
        this.capabilityOrderBy = capabilityOrderBy;
    }
    
    public Boolean getCapabilityMultifiling() {
        return capabilityMultifiling;
    }
    
    public void setCapabilityMultifiling(Boolean capabilityMultifiling) {
        this.capabilityMultifiling = capabilityMultifiling;
    }
    
    public Boolean getCapabilityPwcSearchable() {
        return capabilityPwcSearchable;
    }
    
    public void setCapabilityPwcSearchable(Boolean capabilityPwcSearchable) {
        this.capabilityPwcSearchable = capabilityPwcSearchable;
    }
    
    public Boolean getCapabilityPwcUpdatable() {
        return capabilityPwcUpdatable;
    }
    
    public void setCapabilityPwcUpdatable(Boolean capabilityPwcUpdatable) {
        this.capabilityPwcUpdatable = capabilityPwcUpdatable;
    }
    
    public String getCapabilityQuery() {
        return capabilityQuery;
    }
    
    public void setCapabilityQuery(String capabilityQuery) {
        this.capabilityQuery = capabilityQuery;
    }
    
    public String getCapabilityRenditions() {
        return capabilityRenditions;
    }
    
    public void setCapabilityRenditions(String capabilityRenditions) {
        this.capabilityRenditions = capabilityRenditions;
    }
    
    public Boolean getCapabilityUnfiling() {
        return capabilityUnfiling;
    }
    
    public void setCapabilityUnfiling(Boolean capabilityUnfiling) {
        this.capabilityUnfiling = capabilityUnfiling;
    }
    
    public Boolean getCapabilityVersionSpecificFiling() {
        return capabilityVersionSpecificFiling;
    }
    
    public void setCapabilityVersionSpecificFiling(Boolean capabilityVersionSpecificFiling) {
        this.capabilityVersionSpecificFiling = capabilityVersionSpecificFiling;
    }
    
    public String getCapabilityJoin() {
        return capabilityJoin;
    }
    
    public void setCapabilityJoin(String capabilityJoin) {
        this.capabilityJoin = capabilityJoin;
    }
}
