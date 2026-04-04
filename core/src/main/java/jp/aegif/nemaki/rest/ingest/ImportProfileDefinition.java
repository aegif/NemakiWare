package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Named import profile — defines WHERE and HOW imported content is stored
 * in a specific repository. Scoped to a single repositoryId.
 *
 * <p>A profile does NOT hold auth or endpoint information — that belongs to
 * {@link ConnectorDefinition}. The same profile can be used with multiple
 * connectors via {@code allowedConnectorIds}.
 *
 * <p><b>Enforced at runtime:</b> {@code targetFolderId}, {@code targetFolderPath},
 * {@code defaultObjectTypeId}, {@code allowedArchetypes}, {@code allowedConnectorIds},
 * {@code enabled}, {@code dedupePolicy}, {@code updatePolicy}, {@code versioningPolicy},
 * {@code secondaryTypeIds}, {@code retentionDays}, {@code relationshipPolicy},
 * {@code aclSyncPolicy} (inherit_from_folder is CMIS default), {@code schedulerEnabled}
 * (scheduling infrastructure via {@code IngestSchedulerService}),
 * {@code defaultClassification} (applied as {@code nemaki:classificationInfo} on import).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportProfileDefinition {

    /** CouchDB document type discriminator. */
    public static final String DOC_TYPE = "import_profile_definition";

    private String profileId;
    private String displayName;
    private String repositoryId;
    private String targetFolderId;
    private String targetFolderPath;
    private String defaultObjectTypeId = "cmis:document";
    private List<String> secondaryTypeIds;
    private List<SourceArchetype> allowedArchetypes;
    private List<String> allowedConnectorIds;
    private String defaultConnectorId;
    private String dedupePolicy = "skip_if_same_version";
    private String updatePolicy = "version_up_on_content_change";
    private String versioningPolicy = "major";
    private String relationshipPolicy;
    private String defaultClassification;
    private Integer retentionDays;
    private String aclSyncPolicy = "inherit_from_folder";
    private boolean enabled = true;
    private boolean schedulerEnabled;
    private boolean preserveOriginalEml;
    /** Marks this profile as the default for its repository when multiple profiles match. */
    private boolean defaultProfile;
    /** Source-scope parameters for scheduled fetches (e.g. channelId, teamId, query, soql, folderId). */
    private Map<String, String> schedulerParams;
    private String createdAt;
    private String updatedAt;

    public ImportProfileDefinition() {}

    // --- Getters / Setters ---

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getTargetFolderId() { return targetFolderId; }
    public void setTargetFolderId(String targetFolderId) { this.targetFolderId = targetFolderId; }

    public String getTargetFolderPath() { return targetFolderPath; }
    public void setTargetFolderPath(String targetFolderPath) { this.targetFolderPath = targetFolderPath; }

    public String getDefaultObjectTypeId() { return defaultObjectTypeId; }
    public void setDefaultObjectTypeId(String defaultObjectTypeId) { this.defaultObjectTypeId = defaultObjectTypeId; }

    public List<String> getSecondaryTypeIds() { return secondaryTypeIds; }
    public void setSecondaryTypeIds(List<String> secondaryTypeIds) { this.secondaryTypeIds = secondaryTypeIds; }

    public List<SourceArchetype> getAllowedArchetypes() { return allowedArchetypes; }
    public void setAllowedArchetypes(List<SourceArchetype> allowedArchetypes) { this.allowedArchetypes = allowedArchetypes; }

    public List<String> getAllowedConnectorIds() { return allowedConnectorIds; }
    public void setAllowedConnectorIds(List<String> allowedConnectorIds) { this.allowedConnectorIds = allowedConnectorIds; }

    public String getDefaultConnectorId() { return defaultConnectorId; }
    public void setDefaultConnectorId(String defaultConnectorId) { this.defaultConnectorId = defaultConnectorId; }

    public String getDedupePolicy() { return dedupePolicy; }
    public void setDedupePolicy(String dedupePolicy) { this.dedupePolicy = dedupePolicy; }

    public String getUpdatePolicy() { return updatePolicy; }
    public void setUpdatePolicy(String updatePolicy) { this.updatePolicy = updatePolicy; }

    public String getVersioningPolicy() { return versioningPolicy; }
    public void setVersioningPolicy(String versioningPolicy) { this.versioningPolicy = versioningPolicy; }

    public String getRelationshipPolicy() { return relationshipPolicy; }
    public void setRelationshipPolicy(String relationshipPolicy) { this.relationshipPolicy = relationshipPolicy; }

    public String getDefaultClassification() { return defaultClassification; }
    public void setDefaultClassification(String defaultClassification) { this.defaultClassification = defaultClassification; }

    public Integer getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }

    public String getAclSyncPolicy() { return aclSyncPolicy; }
    public void setAclSyncPolicy(String aclSyncPolicy) { this.aclSyncPolicy = aclSyncPolicy; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }

    public boolean isPreserveOriginalEml() { return preserveOriginalEml; }
    public void setPreserveOriginalEml(boolean preserveOriginalEml) { this.preserveOriginalEml = preserveOriginalEml; }

    public boolean isDefaultProfile() { return defaultProfile; }
    public void setDefaultProfile(boolean defaultProfile) { this.defaultProfile = defaultProfile; }

    public Map<String, String> getSchedulerParams() { return schedulerParams; }
    public void setSchedulerParams(Map<String, String> schedulerParams) { this.schedulerParams = schedulerParams; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Checks whether the given connector is allowed by this profile.
     *
     * @return true if allowedConnectorIds is null/empty (any connector allowed)
     *         or if connectorId is in the list
     */
    public boolean isConnectorAllowed(String connectorId) {
        if (allowedConnectorIds == null || allowedConnectorIds.isEmpty()) {
            return true;
        }
        return allowedConnectorIds.contains(connectorId);
    }

    /**
     * Checks whether the given archetype is allowed by this profile.
     */
    public boolean isArchetypeAllowed(SourceArchetype archetype) {
        if (allowedArchetypes == null || allowedArchetypes.isEmpty()) {
            return true;
        }
        return allowedArchetypes.contains(archetype);
    }
}
