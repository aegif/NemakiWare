package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Named connector definition — represents a connection to an external system.
 * Stored in CouchDB nemaki_conf with document type {@code connector_definition}.
 *
 * <p>A connector holds auth / endpoint / tenant information but does NOT hold
 * destination (target folder, retention, etc.). Destination is defined by
 * {@link ImportProfileDefinition}.
 *
 * <p><b>Delegation model.</b> By default a connector is admin-only. To let a
 * non-admin folder owner reference this connector from a delegated profile,
 * the operator must explicitly set {@link #delegated} to {@code true} AND
 * either:
 * <ul>
 *   <li>set {@link #delegateAllFolders} to {@code true} (repository-wide
 *       delegation — use sparingly), or</li>
 *   <li>list the eligible folder IDs in {@link #allowedFolderIds} (the
 *       connector becomes referenceable from profiles whose target folder
 *       IS one of those IDs OR is a descendant of one).</li>
 * </ul>
 * If {@code delegated=true} but neither knob above is set, the connector is
 * considered <b>not delegated</b> — non-admins cannot use it. The default
 * is therefore safe even if an admin toggles {@code delegated} without
 * scoping it. {@link #allowedPrincipalIds}, when non-empty, further
 * restricts which users / groups may reference the connector (subject to
 * {@code PrincipalService} group expansion).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConnectorDefinition {

    /** CouchDB document type discriminator. */
    public static final String DOC_TYPE = "connector_definition";

    private String connectorId;
    private String displayName;
    private SourceArchetype sourceArchetype;
    private String sourceSystem;
    private String authType;
    private String credentialRef;
    private String endpoint;
    private String tenantId;
    private String adapterKind;
    private Integer rateLimitRpm;
    /** Secret for verifying inbound webhook signatures (Slack signing secret, Graph clientState, etc.). */
    private String webhookSecret;
    private boolean enabled = true;
    /** When false (default), only admins may reference this connector from a profile. */
    private boolean delegated = false;
    /** When true, delegation applies repository-wide and {@link #allowedFolderIds} is ignored. */
    private boolean delegateAllFolders = false;
    /** Folder IDs (and their descendants) that delegated profiles may target. Required unless {@link #delegateAllFolders} is true. */
    private List<String> allowedFolderIds;
    /** User IDs and group IDs that may use this connector from a delegated profile. Empty/null = no principal restriction. */
    private List<String> allowedPrincipalIds;
    private String createdAt;
    private String updatedAt;

    public ConnectorDefinition() {}

    // --- Getters / Setters ---

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public SourceArchetype getSourceArchetype() { return sourceArchetype; }
    public void setSourceArchetype(SourceArchetype sourceArchetype) { this.sourceArchetype = sourceArchetype; }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAdapterKind() { return adapterKind; }
    public void setAdapterKind(String adapterKind) { this.adapterKind = adapterKind; }

    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDelegated() { return delegated; }
    public void setDelegated(boolean delegated) { this.delegated = delegated; }

    public boolean isDelegateAllFolders() { return delegateAllFolders; }
    public void setDelegateAllFolders(boolean delegateAllFolders) { this.delegateAllFolders = delegateAllFolders; }

    public List<String> getAllowedFolderIds() { return allowedFolderIds; }
    public void setAllowedFolderIds(List<String> allowedFolderIds) { this.allowedFolderIds = allowedFolderIds; }

    public List<String> getAllowedPrincipalIds() { return allowedPrincipalIds; }
    public void setAllowedPrincipalIds(List<String> allowedPrincipalIds) { this.allowedPrincipalIds = allowedPrincipalIds; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    /**
     * Returns true if this connector is configured for delegation in a way
     * that could grant access to non-admins. Specifically: {@code delegated}
     * is true AND either {@code delegateAllFolders} is true OR
     * {@code allowedFolderIds} is non-empty. {@code allowedPrincipalIds} is
     * NOT considered here — that's a per-user filter applied at use time.
     *
     * <p>This is the "is this connector even available for delegation?"
     * gate used by validation and listing. The "may THIS user use it for
     * THIS folder?" decision is in {@code IngestAuthorizationService}.
     */
    public boolean hasUsableDelegationScope() {
        if (!delegated) return false;
        if (delegateAllFolders) return true;
        return allowedFolderIds != null && !allowedFolderIds.isEmpty();
    }
}
