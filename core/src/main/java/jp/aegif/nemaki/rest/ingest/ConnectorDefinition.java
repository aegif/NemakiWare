package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Named connector definition — represents a connection to an external system.
 * Stored in CouchDB nemaki_conf with document type {@code connector_definition}.
 *
 * <p>A connector holds auth / endpoint / tenant information but does NOT hold
 * destination (target folder, retention, etc.). Destination is defined by
 * {@link ImportProfileDefinition}.
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

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
