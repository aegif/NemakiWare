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
    /**
     * Controls how an existing document is identified for deduplication.
     * <ul>
     *   <li>{@code source_id} (default) — match by sourceObjectId + sourceSystem</li>
     *   <li>{@code filename} — match by filename within target folder (useful for chat
     *       attachments where external IDs are unstable)</li>
     *   <li>{@code source_id_or_filename} — try source_id first, fall back to filename</li>
     * </ul>
     */
    private String dedupeMatchBy = "source_id";
    private String updatePolicy = "version_up_on_content_change";
    private String versioningPolicy = "major";
    private String relationshipPolicy;
    private String defaultClassification;
    private Integer retentionDays;
    private String aclSyncPolicy = "inherit_from_folder";
    private boolean enabled = true;
    private boolean schedulerEnabled;
    private boolean preserveOriginalEml;
    /**
     * What to import from sources that carry both a body and attachments
     * (Slack messages, Notion pages). {@code "files_only"} (default) imports
     * only the attached files and keeps the surrounding body text as metadata
     * (nemaki:externalContext); {@code "files_and_body"} additionally imports
     * the message text / page HTML as its own document (legacy behaviour).
     * Adapters without a body/attachment split ignore this.
     */
    private String importPolicy = "files_only";
    /** Marks this profile as the default for its repository when multiple profiles match. */
    private boolean defaultProfile;
    /** Source-scope parameters for scheduled fetches (e.g. channelId, teamId, query, soql, folderId). */
    private Map<String, String> schedulerParams;
    /** Username of the principal who created this profile. {@code null} for legacy admin-created profiles. */
    private String createdByUserId;
    /**
     * True for profiles created by a non-admin via folder delegation.
     * {@code false} for admin-managed profiles (default; preserves backward compat).
     * Non-admins may only edit/delete profiles where this is true.
     */
    private boolean delegated = false;
    private String createdAt;
    private String updatedAt;

    /**
     * RC5 (v2 §12.1 / V1 extension): set by
     * {@code IngestSchedulerService.handleInactiveCreator} when the
     * profile is auto-disabled because the creator's UserItem has been
     * inactive for {@code inactiveOwnerFailureThreshold} consecutive
     * ticks. Lets the admin UI distinguish a profile the admin
     * manually disabled from one the scheduler shut down — and shows
     * WHY. Cleared on the next admin {@code enabled=true} update so
     * deliberate re-enable starts with a clean slate.
     *
     * <p>Null on profiles that were never auto-disabled.
     */
    private String lastAutoDisabledAt;
    private String lastAutoDisabledReason;

    /**
     * Who last configured this profile's SCHEDULING (enabled / schedulerEnabled / delegated /
     * connector wiring / schedulerParams) — server-stamped, never client-supplied. This is what
     * lets the execution attribution of an autonomous run say "schedule configured by X"
     * instead of "unknown" (P1-1(e) §1.3). A legacy profile without it is reported as
     * configured-by UNRECORDED, not silently substituted with the creator (Codex H4 — creation
     * and schedule-enablement can be different people, and admin create takes the body's
     * createdByUserId verbatim).
     */
    private String scheduleConfiguredByUserId;
    private Long scheduleConfiguredAtMs;

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

    public String getImportPolicy() { return importPolicy; }
    public void setImportPolicy(String importPolicy) { this.importPolicy = importPolicy; }

    public String getDedupeMatchBy() { return dedupeMatchBy; }
    public void setDedupeMatchBy(String dedupeMatchBy) { this.dedupeMatchBy = dedupeMatchBy; }

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

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public boolean isDelegated() { return delegated; }
    public void setDelegated(boolean delegated) { this.delegated = delegated; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLastAutoDisabledAt() { return lastAutoDisabledAt; }
    public void setLastAutoDisabledAt(String lastAutoDisabledAt) { this.lastAutoDisabledAt = lastAutoDisabledAt; }

    public String getLastAutoDisabledReason() { return lastAutoDisabledReason; }
    public void setLastAutoDisabledReason(String lastAutoDisabledReason) { this.lastAutoDisabledReason = lastAutoDisabledReason; }

    public String getScheduleConfiguredByUserId() { return scheduleConfiguredByUserId; }
    public void setScheduleConfiguredByUserId(String scheduleConfiguredByUserId) { this.scheduleConfiguredByUserId = scheduleConfiguredByUserId; }
    public Long getScheduleConfiguredAtMs() { return scheduleConfiguredAtMs; }
    public void setScheduleConfiguredAtMs(Long scheduleConfiguredAtMs) { this.scheduleConfiguredAtMs = scheduleConfiguredAtMs; }

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
