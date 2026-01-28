package jp.aegif.nemaki.webhook;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single child event (CHILD_CREATED, CHILD_UPDATED, CHILD_DELETED)
 * that will be batched before webhook delivery.
 * 
 * Child events are generated when content is created, updated, or deleted
 * within a folder that has a webhook configuration with CHILD_* event types.
 */
public class ChildEvent {
    
    private String parentFolderId;
    private String parentFolderPath;
    private String objectId;
    private String objectName;
    private String objectType;
    private String eventType;
    private long timestamp;
    private Map<String, Object> properties;
    private String changeToken;
    private String userId;
    
    public ChildEvent() {
        this.properties = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
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
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public String getObjectName() {
        return objectName;
    }
    
    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }
    
    public String getObjectType() {
        return objectType;
    }
    
    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties != null ? new HashMap<>(properties) : new HashMap<>();
    }
    
    public String getChangeToken() {
        return changeToken;
    }
    
    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    @Override
    public String toString() {
        return "ChildEvent{" +
            "parentFolderId='" + parentFolderId + '\'' +
            ", objectId='" + objectId + '\'' +
            ", objectName='" + objectName + '\'' +
            ", eventType='" + eventType + '\'' +
            ", timestamp=" + timestamp +
            '}';
    }
}
