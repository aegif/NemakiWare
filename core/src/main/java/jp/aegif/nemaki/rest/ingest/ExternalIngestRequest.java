package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical external ingest request. All entry points (UI, API, scheduler)
 * converge to this DTO before entering the {@link CanonicalImportService} pipeline.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalIngestRequest {

    private String requestId;
    private String profileId;
    private String connectorId;
    private String repositoryId;
    private String sourceObjectId;
    private String sourceObjectType;
    private String sourceUrl;
    private String executionMode = "api";
    private boolean dryRun;
    private String idempotencyKey;
    private String correlationId;
    private String fileName;
    private String mimeType;
    private Map<String, String> metadata;
    private Map<String, String> overrides;

    @JsonIgnore
    private InputStream contentStream;

    public ExternalIngestRequest() {
        this.requestId = UUID.randomUUID().toString();
    }

    // --- Getters / Setters ---

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getSourceObjectId() { return sourceObjectId; }
    public void setSourceObjectId(String sourceObjectId) { this.sourceObjectId = sourceObjectId; }

    public String getSourceObjectType() { return sourceObjectType; }
    public void setSourceObjectType(String sourceObjectType) { this.sourceObjectType = sourceObjectType; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Map<String, String> getOverrides() { return overrides; }
    public void setOverrides(Map<String, String> overrides) { this.overrides = overrides; }

    public InputStream getContentStream() { return contentStream; }
    public void setContentStream(InputStream contentStream) { this.contentStream = contentStream; }
}
