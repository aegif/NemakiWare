package jp.aegif.nemaki.webhook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single webhook configuration stored in the nemaki:webhookConfigs JSON property.
 * 
 * Each WebhookConfig defines:
 * - Target URL for webhook delivery
 * - Events to monitor (CREATED, UPDATED, DELETED, SECURITY, etc.)
 * - Authentication settings (Basic, Bearer, API Key)
 * - HMAC signing secret
 * - Child monitoring settings (for folders)
 * - Retry configuration
 * 
 * @see WebhookConfigParser for JSON serialization/deserialization
 */
public class WebhookConfig {
    
    private String id;
    private boolean enabled;
    private String url;
    private List<String> events;
    private String authType;
    private String authCredential;
    private String secret;
    private Map<String, String> headers;
    private boolean includeChildren;
    private Integer maxDepth;
    private Integer retryCount;
    
    /**
     * The object ID where this webhook config was defined.
     * Used for tracking inherited configurations from parent folders.
     * This is a runtime field, not persisted in JSON.
     */
    private transient String sourceObjectId;
    
    /**
     * Default constructor.
     * Creates a disabled webhook config with empty events list.
     */
    public WebhookConfig() {
        this.enabled = false;
        this.events = new ArrayList<>();
        this.headers = new HashMap<>();
        this.includeChildren = false;
    }
    
    /**
     * Private constructor for Builder pattern.
     */
    private WebhookConfig(Builder builder) {
        this.id = builder.id;
        this.enabled = builder.enabled;
        this.url = builder.url;
        this.events = builder.events != null ? new ArrayList<>(builder.events) : new ArrayList<>();
        this.authType = builder.authType;
        this.authCredential = builder.authCredential;
        this.secret = builder.secret;
        this.headers = builder.headers != null ? new HashMap<>(builder.headers) : new HashMap<>();
        this.includeChildren = builder.includeChildren;
        this.maxDepth = builder.maxDepth;
        this.retryCount = builder.retryCount;
    }
    
    // ========================================
    // Getters and Setters
    // ========================================
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public List<String> getEvents() {
        return events;
    }
    
    public void setEvents(List<String> events) {
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
    }
    
    public String getAuthType() {
        return authType;
    }
    
    public void setAuthType(String authType) {
        this.authType = authType;
    }
    
    public String getAuthCredential() {
        return authCredential;
    }
    
    public void setAuthCredential(String authCredential) {
        this.authCredential = authCredential;
    }
    
    public String getSecret() {
        return secret;
    }
    
    public void setSecret(String secret) {
        this.secret = secret;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
    }
    
    public boolean isIncludeChildren() {
        return includeChildren;
    }
    
    public void setIncludeChildren(boolean includeChildren) {
        this.includeChildren = includeChildren;
    }
    
    public Integer getMaxDepth() {
        return maxDepth;
    }
    
    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }
    
    public Integer getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }
    
    public String getSourceObjectId() {
        return sourceObjectId;
    }
    
    public void setSourceObjectId(String sourceObjectId) {
        this.sourceObjectId = sourceObjectId;
    }
    
    // ========================================
    // Business Logic Methods
    // ========================================
    
    /**
     * Check if this webhook config matches the given event type.
     * Returns true only if the config is enabled and the event type is in the events list.
     * 
     * @param eventType The event type to check (e.g., "CREATED", "UPDATED")
     * @return true if this config should receive the event
     */
    public boolean matchesEvent(String eventType) {
        if (!enabled || eventType == null || events == null || events.isEmpty()) {
            return false;
        }
        
        String normalizedEventType = eventType.toUpperCase();
        return events.stream()
            .anyMatch(e -> e != null && e.toUpperCase().equals(normalizedEventType));
    }
    
    /**
     * Validate that this webhook config has all required fields.
     * 
     * Required fields:
     * - id: must not be null or empty
     * - url: must not be null or empty
     * - events: must not be null or empty
     * - authCredential: required if authType requires it (basic, bearer, apikey)
     * 
     * @return true if the config is valid
     */
    public boolean isValid() {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        if (events == null || events.isEmpty()) {
            return false;
        }
        // Check auth credential requirement
        if (requiresAuthCredential() && (authCredential == null || authCredential.trim().isEmpty())) {
            return false;
        }
        return true;
    }
    
    /**
     * Check if this auth type requires a credential.
     * 
     * @return true if authCredential is required for the current authType
     */
    public boolean requiresAuthCredential() {
        if (authType == null) {
            return false;
        }
        switch (authType.toLowerCase()) {
            case "none":
                return false;
            case "basic":
            case "bearer":
            case "apikey":
                return true;
            default:
                return false;
        }
    }
    
    // ========================================
    // Object Methods
    // ========================================
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebhookConfig that = (WebhookConfig) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "WebhookConfig{" +
            "id='" + id + '\'' +
            ", enabled=" + enabled +
            ", url='" + url + '\'' +
            ", events=" + events +
            ", authType='" + authType + '\'' +
            ", includeChildren=" + includeChildren +
            ", maxDepth=" + maxDepth +
            ", retryCount=" + retryCount +
            '}';
    }
    
    // ========================================
    // Builder Pattern
    // ========================================
    
    /**
     * Builder for creating WebhookConfig instances.
     */
    public static class Builder {
        private String id;
        private boolean enabled;
        private String url;
        private List<String> events;
        private String authType;
        private String authCredential;
        private String secret;
        private Map<String, String> headers;
        private boolean includeChildren;
        private Integer maxDepth;
        private Integer retryCount;
        
        public Builder() {
            this.events = new ArrayList<>();
            this.headers = new HashMap<>();
        }
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder url(String url) {
            this.url = url;
            return this;
        }
        
        public Builder events(List<String> events) {
            this.events = events;
            return this;
        }
        
        public Builder authType(String authType) {
            this.authType = authType;
            return this;
        }
        
        public Builder authCredential(String authCredential) {
            this.authCredential = authCredential;
            return this;
        }
        
        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }
        
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }
        
        public Builder includeChildren(boolean includeChildren) {
            this.includeChildren = includeChildren;
            return this;
        }
        
        public Builder maxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }
        
        public Builder retryCount(Integer retryCount) {
            this.retryCount = retryCount;
            return this;
        }
        
        public WebhookConfig build() {
            return new WebhookConfig(this);
        }
    }
}
