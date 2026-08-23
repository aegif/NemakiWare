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
    /**
     * How many {@code contentBase64} values were removed from {@link #originalRequestJson}.
     *
     * <p>Non-zero means a replay will NOT restore those attachment bytes: they travel only
     * through the encrypted payload attachment. Orchestrator-fetched attachments come back on
     * the next poll (the dedupe-skip fall-through retries them); caller-supplied ones need
     * re-sending.
     */
    private int requestBinaryStrippedCount;
    /** Whether binary content is stored as a CouchDB attachment. */
    private boolean hasContent;

    /**
     * How many times this same source item has failed.
     *
     * <p>Entries used to get a random id, so a persistent outage wrote a NEW document on every
     * poll — the same item, over and over, in the configuration database, with its payload
     * attached each time and no deduplication (external review). The id is now derived from the
     * item's identity, which makes a repeat an update; this counts the repeats.
     */
    private int failureCount = 1;

    /** When this item first failed. {@code failedAt} moves; this does not. */
    private String firstFailedAt;

    /**
     * Why the payload is absent even though the item had one.
     *
     * <p>Null when there was no payload or when it was stored. Non-null means the bytes were
     * deliberately NOT written — the only current reason is that no encryption key is
     * configured, and writing them in the clear into the configuration database is not an
     * acceptable fallback for a system whose subject is evidence.
     */
    private String payloadDropReason;

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

    public int getRequestBinaryStrippedCount() { return requestBinaryStrippedCount; }
    public void setRequestBinaryStrippedCount(int requestBinaryStrippedCount) { this.requestBinaryStrippedCount = requestBinaryStrippedCount; }
    public String getOriginalRequestJson() { return originalRequestJson; }
    public void setOriginalRequestJson(String originalRequestJson) { this.originalRequestJson = originalRequestJson; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public String getFirstFailedAt() { return firstFailedAt; }
    public void setFirstFailedAt(String firstFailedAt) { this.firstFailedAt = firstFailedAt; }

    public String getPayloadDropReason() { return payloadDropReason; }
    public void setPayloadDropReason(String payloadDropReason) {
        this.payloadDropReason = payloadDropReason;
    }

    public boolean isHasContent() { return hasContent; }
    public void setHasContent(boolean hasContent) { this.hasContent = hasContent; }
}
