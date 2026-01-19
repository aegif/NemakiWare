package jp.aegif.nemaki.api.v1.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CMIS allowable actions for an object")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AllowableActionsResponse {
    
    @JsonProperty("canDeleteObject")
    private Boolean canDeleteObject;
    
    @JsonProperty("canUpdateProperties")
    private Boolean canUpdateProperties;
    
    @JsonProperty("canGetFolderTree")
    private Boolean canGetFolderTree;
    
    @JsonProperty("canGetProperties")
    private Boolean canGetProperties;
    
    @JsonProperty("canGetObjectRelationships")
    private Boolean canGetObjectRelationships;
    
    @JsonProperty("canGetObjectParents")
    private Boolean canGetObjectParents;
    
    @JsonProperty("canGetFolderParent")
    private Boolean canGetFolderParent;
    
    @JsonProperty("canGetDescendants")
    private Boolean canGetDescendants;
    
    @JsonProperty("canMoveObject")
    private Boolean canMoveObject;
    
    @JsonProperty("canDeleteContentStream")
    private Boolean canDeleteContentStream;
    
    @JsonProperty("canCheckOut")
    private Boolean canCheckOut;
    
    @JsonProperty("canCancelCheckOut")
    private Boolean canCancelCheckOut;
    
    @JsonProperty("canCheckIn")
    private Boolean canCheckIn;
    
    @JsonProperty("canSetContentStream")
    private Boolean canSetContentStream;
    
    @JsonProperty("canGetAllVersions")
    private Boolean canGetAllVersions;
    
    @JsonProperty("canAddObjectToFolder")
    private Boolean canAddObjectToFolder;
    
    @JsonProperty("canRemoveObjectFromFolder")
    private Boolean canRemoveObjectFromFolder;
    
    @JsonProperty("canGetContentStream")
    private Boolean canGetContentStream;
    
    @JsonProperty("canApplyPolicy")
    private Boolean canApplyPolicy;
    
    @JsonProperty("canGetAppliedPolicies")
    private Boolean canGetAppliedPolicies;
    
    @JsonProperty("canRemovePolicy")
    private Boolean canRemovePolicy;
    
    @JsonProperty("canGetChildren")
    private Boolean canGetChildren;
    
    @JsonProperty("canCreateDocument")
    private Boolean canCreateDocument;
    
    @JsonProperty("canCreateFolder")
    private Boolean canCreateFolder;
    
    @JsonProperty("canCreateRelationship")
    private Boolean canCreateRelationship;
    
    @JsonProperty("canCreateItem")
    private Boolean canCreateItem;
    
    @JsonProperty("canDeleteTree")
    private Boolean canDeleteTree;
    
    @JsonProperty("canGetRenditions")
    private Boolean canGetRenditions;
    
    @JsonProperty("canGetAcl")
    private Boolean canGetAcl;
    
    @JsonProperty("canApplyAcl")
    private Boolean canApplyAcl;
    
    public AllowableActionsResponse() {
    }
    
    public Boolean getCanDeleteObject() {
        return canDeleteObject;
    }
    
    public void setCanDeleteObject(Boolean canDeleteObject) {
        this.canDeleteObject = canDeleteObject;
    }
    
    public Boolean getCanUpdateProperties() {
        return canUpdateProperties;
    }
    
    public void setCanUpdateProperties(Boolean canUpdateProperties) {
        this.canUpdateProperties = canUpdateProperties;
    }
    
    public Boolean getCanGetFolderTree() {
        return canGetFolderTree;
    }
    
    public void setCanGetFolderTree(Boolean canGetFolderTree) {
        this.canGetFolderTree = canGetFolderTree;
    }
    
    public Boolean getCanGetProperties() {
        return canGetProperties;
    }
    
    public void setCanGetProperties(Boolean canGetProperties) {
        this.canGetProperties = canGetProperties;
    }
    
    public Boolean getCanGetObjectRelationships() {
        return canGetObjectRelationships;
    }
    
    public void setCanGetObjectRelationships(Boolean canGetObjectRelationships) {
        this.canGetObjectRelationships = canGetObjectRelationships;
    }
    
    public Boolean getCanGetObjectParents() {
        return canGetObjectParents;
    }
    
    public void setCanGetObjectParents(Boolean canGetObjectParents) {
        this.canGetObjectParents = canGetObjectParents;
    }
    
    public Boolean getCanGetFolderParent() {
        return canGetFolderParent;
    }
    
    public void setCanGetFolderParent(Boolean canGetFolderParent) {
        this.canGetFolderParent = canGetFolderParent;
    }
    
    public Boolean getCanGetDescendants() {
        return canGetDescendants;
    }
    
    public void setCanGetDescendants(Boolean canGetDescendants) {
        this.canGetDescendants = canGetDescendants;
    }
    
    public Boolean getCanMoveObject() {
        return canMoveObject;
    }
    
    public void setCanMoveObject(Boolean canMoveObject) {
        this.canMoveObject = canMoveObject;
    }
    
    public Boolean getCanDeleteContentStream() {
        return canDeleteContentStream;
    }
    
    public void setCanDeleteContentStream(Boolean canDeleteContentStream) {
        this.canDeleteContentStream = canDeleteContentStream;
    }
    
    public Boolean getCanCheckOut() {
        return canCheckOut;
    }
    
    public void setCanCheckOut(Boolean canCheckOut) {
        this.canCheckOut = canCheckOut;
    }
    
    public Boolean getCanCancelCheckOut() {
        return canCancelCheckOut;
    }
    
    public void setCanCancelCheckOut(Boolean canCancelCheckOut) {
        this.canCancelCheckOut = canCancelCheckOut;
    }
    
    public Boolean getCanCheckIn() {
        return canCheckIn;
    }
    
    public void setCanCheckIn(Boolean canCheckIn) {
        this.canCheckIn = canCheckIn;
    }
    
    public Boolean getCanSetContentStream() {
        return canSetContentStream;
    }
    
    public void setCanSetContentStream(Boolean canSetContentStream) {
        this.canSetContentStream = canSetContentStream;
    }
    
    public Boolean getCanGetAllVersions() {
        return canGetAllVersions;
    }
    
    public void setCanGetAllVersions(Boolean canGetAllVersions) {
        this.canGetAllVersions = canGetAllVersions;
    }
    
    public Boolean getCanAddObjectToFolder() {
        return canAddObjectToFolder;
    }
    
    public void setCanAddObjectToFolder(Boolean canAddObjectToFolder) {
        this.canAddObjectToFolder = canAddObjectToFolder;
    }
    
    public Boolean getCanRemoveObjectFromFolder() {
        return canRemoveObjectFromFolder;
    }
    
    public void setCanRemoveObjectFromFolder(Boolean canRemoveObjectFromFolder) {
        this.canRemoveObjectFromFolder = canRemoveObjectFromFolder;
    }
    
    public Boolean getCanGetContentStream() {
        return canGetContentStream;
    }
    
    public void setCanGetContentStream(Boolean canGetContentStream) {
        this.canGetContentStream = canGetContentStream;
    }
    
    public Boolean getCanApplyPolicy() {
        return canApplyPolicy;
    }
    
    public void setCanApplyPolicy(Boolean canApplyPolicy) {
        this.canApplyPolicy = canApplyPolicy;
    }
    
    public Boolean getCanGetAppliedPolicies() {
        return canGetAppliedPolicies;
    }
    
    public void setCanGetAppliedPolicies(Boolean canGetAppliedPolicies) {
        this.canGetAppliedPolicies = canGetAppliedPolicies;
    }
    
    public Boolean getCanRemovePolicy() {
        return canRemovePolicy;
    }
    
    public void setCanRemovePolicy(Boolean canRemovePolicy) {
        this.canRemovePolicy = canRemovePolicy;
    }
    
    public Boolean getCanGetChildren() {
        return canGetChildren;
    }
    
    public void setCanGetChildren(Boolean canGetChildren) {
        this.canGetChildren = canGetChildren;
    }
    
    public Boolean getCanCreateDocument() {
        return canCreateDocument;
    }
    
    public void setCanCreateDocument(Boolean canCreateDocument) {
        this.canCreateDocument = canCreateDocument;
    }
    
    public Boolean getCanCreateFolder() {
        return canCreateFolder;
    }
    
    public void setCanCreateFolder(Boolean canCreateFolder) {
        this.canCreateFolder = canCreateFolder;
    }
    
    public Boolean getCanCreateRelationship() {
        return canCreateRelationship;
    }
    
    public void setCanCreateRelationship(Boolean canCreateRelationship) {
        this.canCreateRelationship = canCreateRelationship;
    }
    
    public Boolean getCanCreateItem() {
        return canCreateItem;
    }
    
    public void setCanCreateItem(Boolean canCreateItem) {
        this.canCreateItem = canCreateItem;
    }
    
    public Boolean getCanDeleteTree() {
        return canDeleteTree;
    }
    
    public void setCanDeleteTree(Boolean canDeleteTree) {
        this.canDeleteTree = canDeleteTree;
    }
    
    public Boolean getCanGetRenditions() {
        return canGetRenditions;
    }
    
    public void setCanGetRenditions(Boolean canGetRenditions) {
        this.canGetRenditions = canGetRenditions;
    }
    
    public Boolean getCanGetAcl() {
        return canGetAcl;
    }
    
    public void setCanGetAcl(Boolean canGetAcl) {
        this.canGetAcl = canGetAcl;
    }
    
    public Boolean getCanApplyAcl() {
        return canApplyAcl;
    }
    
    public void setCanApplyAcl(Boolean canApplyAcl) {
        this.canApplyAcl = canApplyAcl;
    }
}
