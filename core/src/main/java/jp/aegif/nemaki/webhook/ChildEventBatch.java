package jp.aegif.nemaki.webhook;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a batch of child events to be delivered as a single webhook payload.
 * 
 * When multiple CHILD_* events occur within a short time window for the same
 * parent folder, they are aggregated into a single batch to reduce webhook
 * traffic and improve efficiency.
 * 
 * The batch payload includes:
 * - Parent folder information
 * - List of individual changes (CHILD_CREATED, CHILD_UPDATED, CHILD_DELETED)
 * - Batch metadata (window times, event count)
 */
public class ChildEventBatch {
    
    private String batchId;
    private String repositoryId;
    private String parentFolderId;
    private String parentFolderPath;
    private String eventType;
    private List<ChildEvent> events;
    private long windowStart;
    private long windowEnd;
    private String webhookConfigId;
    private String webhookUrl;
    
    public ChildEventBatch() {
        this.batchId = UUID.randomUUID().toString();
        this.events = new ArrayList<>();
        this.eventType = "CHILD_BATCH";
    }
    
    public ChildEventBatch(String repositoryId, String parentFolderId) {
        this();
        this.repositoryId = repositoryId;
        this.parentFolderId = parentFolderId;
    }
    
    public String getBatchId() {
        return batchId;
    }
    
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public String getParentFolderId() {
        return parentFolderId;
    }
    
    public void setParentFolderId(String parentFolderId) {
        this.parentFolderId = parentFolderId;
    }
    
    public String getParentFolderPath() {
        return parentFolderPath;
    }
    
    public void setParentFolderPath(String parentFolderPath) {
        this.parentFolderPath = parentFolderPath;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public List<ChildEvent> getEvents() {
        return events;
    }
    
    public void setEvents(List<ChildEvent> events) {
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
    }
    
    public void addEvent(ChildEvent event) {
        if (event != null) {
            this.events.add(event);
            if (this.parentFolderPath == null && event.getParentFolderPath() != null) {
                this.parentFolderPath = event.getParentFolderPath();
            }
        }
    }
    
    public long getWindowStart() {
        return windowStart;
    }
    
    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }
    
    public long getWindowEnd() {
        return windowEnd;
    }
    
    public void setWindowEnd(long windowEnd) {
        this.windowEnd = windowEnd;
    }
    
    public String getWebhookConfigId() {
        return webhookConfigId;
    }
    
    public void setWebhookConfigId(String webhookConfigId) {
        this.webhookConfigId = webhookConfigId;
    }
    
    public String getWebhookUrl() {
        return webhookUrl;
    }
    
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
    
    public int getEventCount() {
        return events.size();
    }
    
    public boolean isEmpty() {
        return events.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ChildEventBatch{" +
            "batchId='" + batchId + '\'' +
            ", repositoryId='" + repositoryId + '\'' +
            ", parentFolderId='" + parentFolderId + '\'' +
            ", eventCount=" + events.size() +
            ", windowStart=" + windowStart +
            ", windowEnd=" + windowEnd +
            '}';
    }
}
