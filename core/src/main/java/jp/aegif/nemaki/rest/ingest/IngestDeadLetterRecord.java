package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Dead-letter record for failed ingest requests.
 * Stored in CouchDB nemaki_conf as type "ingest_dead_letter".
 * Supports manual retry via admin API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestDeadLetterRecord {

    public static final String DOC_TYPE = "ingest_dead_letter";

    private String dlqId;
    private String profileId;
    private String connectorId;
    private String repositoryId;
    private String sourceObjectId;
    private String sourceObjectType;
    private String fileName;
    private String failedAt;
    private String errorMessage;
    private int retryCount;
    private String lastRetryAt;
    /** Serialized ExternalIngestRequest JSON for replay. */
    private String originalRequestJson;
    /** Whether binary content is stored as a CouchDB attachment. */
    private boolean hasContent;

    public IngestDeadLetterRecord() {}

    // --- Getters / Setters ---

    public String getDlqId() { return dlqId; }
    public void setDlqId(String dlqId) { this.dlqId = dlqId; }

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

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFailedAt() { return failedAt; }
    public void setFailedAt(String failedAt) { this.failedAt = failedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getLastRetryAt() { return lastRetryAt; }
    public void setLastRetryAt(String lastRetryAt) { this.lastRetryAt = lastRetryAt; }

    public String getOriginalRequestJson() { return originalRequestJson; }
    public void setOriginalRequestJson(String originalRequestJson) { this.originalRequestJson = originalRequestJson; }

    public boolean isHasContent() { return hasContent; }
    public void setHasContent(boolean hasContent) { this.hasContent = hasContent; }
}
