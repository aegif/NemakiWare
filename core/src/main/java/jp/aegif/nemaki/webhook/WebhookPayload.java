package jp.aegif.nemaki.webhook;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a webhook delivery payload.
 * 
 * This class contains all the information that will be sent to the webhook endpoint
 * when an event occurs. The payload includes event details, object information,
 * and metadata for idempotency and ordering.
 */
public class WebhookPayload {
    
    private String eventType;
    private String objectId;
    private String repositoryId;
    private String deliveryId;
    private String changeToken;
    private long timestamp;
    private Map<String, Object> properties;
    private String objectPath;
    private String parentId;
    private String userId;
    
    public WebhookPayload() {
        this.properties = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public String getDeliveryId() {
        return deliveryId;
    }
    
    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }
    
    public String getChangeToken() {
        return changeToken;
    }
    
    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
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
        this.properties = properties != null ? properties : new HashMap<>();
    }
    
    public String getObjectPath() {
        return objectPath;
    }
    
    public void setObjectPath(String objectPath) {
        this.objectPath = objectPath;
    }
    
    public String getParentId() {
        return parentId;
    }
    
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    @Override
    public String toString() {
        return "WebhookPayload{" +
            "eventType='" + eventType + '\'' +
            ", objectId='" + objectId + '\'' +
            ", repositoryId='" + repositoryId + '\'' +
            ", deliveryId='" + deliveryId + '\'' +
            ", changeToken='" + changeToken + '\'' +
            ", timestamp=" + timestamp +
            ", objectPath='" + objectPath + '\'' +
            ", parentId='" + parentId + '\'' +
            ", userId='" + userId + '\'' +
            '}';
    }
}
