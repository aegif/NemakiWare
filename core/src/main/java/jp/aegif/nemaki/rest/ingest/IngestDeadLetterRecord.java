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
     * <p>Null when there was no payload or when it was stored. Non-null means the bytes of the
     * attempt this row describes are NOT on it, and the retry door refuses a replay on that
     * alone. Three things write it, and the first is the only one that is a decision:
     *
     * <ul>
     *   <li>no encryption key is configured, so the bytes were deliberately not written —
     *       storing them in the clear in the configuration database is not an acceptable
     *       fallback for a system whose subject is evidence;</li>
     *   <li>the attachment write was refused and a read established that nothing is there;</li>
     *   <li>the attachment write's outcome could not be established at all (paired with
     *       {@code payloadPresenceAssumed}).</li>
     * </ul>
     *
     * <p>It does NOT mean "a write is in flight" — that is {@link #payloadWriteToken}. A round
     * that put the window's explanation here made the refusal permanent, because this field is
     * read as a settled fact.
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

    /**
     * True when {@code hasContent} was ASSUMED rather than read.
     *
     * <p>It is set when the row was being written over one this node could not decode AND the
     * raw attachment probe could not answer either. {@code hasContent} is then true on the
     * safe side — a retry that finds nothing refuses and KEEPS the row, where a false "no
     * payload" imports content-less and deletes it. This flag is what keeps the assumption
     * from laundering into an assertion: the next save re-probes instead of inheriting it,
     * and the retry door explains itself with it. A review found the assumption becoming a
     * settled fact one save later.
     */
    private boolean payloadPresenceAssumed;

    /**
     * True when this row records a source item that was never successfully READ.
     *
     * <p>The fetch threw before the import service was reached, so the row carries the request
     * but no content and — for the connectors that pass the attachment list in metadata — not
     * even the list. Replaying such a row can report "nothing to import", which the retry door
     * used to treat as an idempotent RESOLUTION and delete the row with: the only record that
     * the item was lost, destroyed by the tool that exists to recover it. A review traced it
     * through the Notion page arm. A skip on a row with this flag keeps the row.
     */
    private boolean sourceNeverRead;

    /**
     * Non-null while a payload write for ONE attempt is in flight, carrying that attempt's id.
     *
     * <p>A payload-bearing save publishes the row before it attaches the bytes, so between the
     * two writes there is no attachment — and a concurrent replay read that as proof there is
     * none. The first fix wrote the explanation into {@code payloadDropReason}, which the
     * retry door treats as PERMANENT: if the confirming write then lost a revision race, the
     * row refused every replay for ever for an entry whose content was in fact stored, and
     * DELETE was the only way out. Two reviewers found that in the round after. So the window
     * has its own field: the retry answers "retry shortly" for it, any later save clears it,
     * and the confirming write only acts when the token is still its own.
     */
    private String payloadWriteToken;

    public String getPayloadWriteToken() { return payloadWriteToken; }
    public void setPayloadWriteToken(String payloadWriteToken) {
        this.payloadWriteToken = payloadWriteToken;
    }

    public boolean isSourceNeverRead() { return sourceNeverRead; }
    public void setSourceNeverRead(boolean sourceNeverRead) {
        this.sourceNeverRead = sourceNeverRead;
    }

    public boolean isPayloadPresenceAssumed() { return payloadPresenceAssumed; }
    public void setPayloadPresenceAssumed(boolean payloadPresenceAssumed) {
        this.payloadPresenceAssumed = payloadPresenceAssumed;
    }

    public String getPayloadDropReason() { return payloadDropReason; }
    public void setPayloadDropReason(String payloadDropReason) {
        this.payloadDropReason = payloadDropReason;
    }

    public boolean isHasContent() { return hasContent; }
    public void setHasContent(boolean hasContent) { this.hasContent = hasContent; }

    /**
     * True for a row that records webhook deliveries this node accepted and did not fetch.
     * Such a row carries no item: the retry door refuses to replay it and says to re-fetch
     * through the connector. Only {@code IngestJobService.saveWebhookDeliveryRecordToDlq}
     * sets it — callers of the ingest API cannot (R10).
     */
    private boolean webhookDeliveryRecord;
    public boolean isWebhookDeliveryRecord() { return webhookDeliveryRecord; }
    public void setWebhookDeliveryRecord(boolean webhookDeliveryRecord) {
        this.webhookDeliveryRecord = webhookDeliveryRecord;
    }

    /**
     * Storage bookkeeping of the row this record was READ from — never persisted, never
     * inherited. A write that follows a read is conditioned on this revision (a
     * compare-and-swap), so a concurrent save is a 409 to re-merge from rather than a lost
     * update; the attachment stubs are those of the same revision, carried forward by the
     * write so CouchDB keeps the payload. Null for a record that was not read from the store
     * (R1: {@code upsertDocument} adopted "whatever is there now" and silently discarded the
     * other writer's fields).
     */
    private transient String storedId;
    private transient String storedRevision;
    private transient java.util.Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> storedAttachments;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getStoredId() { return storedId; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setStoredId(String storedId) { this.storedId = storedId; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getStoredRevision() { return storedRevision; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setStoredRevision(String storedRevision) { this.storedRevision = storedRevision; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> getStoredAttachments() {
        return storedAttachments;
    }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setStoredAttachments(
            java.util.Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> storedAttachments) {
        this.storedAttachments = storedAttachments;
    }
}
