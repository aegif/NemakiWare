package jp.aegif.nemaki.webhook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Matches webhook configurations against event types.
 * 
 * Responsible for finding all webhook configurations that should receive
 * a notification for a given event type. Supports Phase 1 event types:
 * CREATED, UPDATED, DELETED, SECURITY.
 * 
 * Future phases will add support for:
 * - CONTENT_UPDATED, CHECKED_OUT, CHECKED_IN, VERSION_CREATED, MOVED (Phase 2)
 * - CHILD_CREATED, CHILD_DELETED, CHILD_UPDATED (Phase 3+)
 */
public class WebhookEventMatcher {
    
    private static final Log log = LogFactory.getLog(WebhookEventMatcher.class);
    
    /**
     * Phase 1 supported event types.
     * These correspond to CMIS ChangeType values.
     */
    private static final List<String> SUPPORTED_EVENT_TYPES = Collections.unmodifiableList(
        Arrays.asList(
            "CREATED",   // ChangeType.CREATED
            "UPDATED",   // ChangeType.UPDATED
            "DELETED",   // ChangeType.DELETED
            "SECURITY"   // ChangeType.SECURITY (ACL changes)
        )
    );
    
    /**
     * Find all webhook configurations that match the given event type.
     * 
     * A configuration matches if:
     * 1. The event type is a supported/valid event type
     * 2. It is enabled (enabled=true)
     * 3. Its events list contains the given event type (case-insensitive)
     * 
     * @param configs List of webhook configurations to search
     * @param eventType The event type to match (e.g., "CREATED", "UPDATED")
     * @return List of matching configurations, empty list if none match or event type is unsupported
     */
    public List<WebhookConfig> findMatchingConfigs(List<WebhookConfig> configs, String eventType) {
        if (configs == null || configs.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (eventType == null || eventType.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // Early return for unsupported event types
        if (!isValidEventType(eventType)) {
            log.warn("Unsupported event type: " + eventType + ". Supported types: " + SUPPORTED_EVENT_TYPES);
            return new ArrayList<>();
        }
        
        String normalizedEventType = eventType.toUpperCase().trim();
        
        return configs.stream()
            .filter(config -> config != null)
            .filter(config -> config.isEnabled())
            .filter(config -> config.matchesEvent(normalizedEventType))
            .collect(Collectors.toList());
    }
    
    /**
     * Check if the given event type is a valid/supported event type.
     * 
     * @param eventType The event type to check
     * @return true if the event type is supported
     */
    public boolean isValidEventType(String eventType) {
        if (eventType == null || eventType.trim().isEmpty()) {
            return false;
        }
        
        String normalized = eventType.toUpperCase().trim();
        return SUPPORTED_EVENT_TYPES.contains(normalized);
    }
    
    /**
     * Get the list of supported event types.
     * 
     * @return Unmodifiable list of supported event type strings
     */
    public List<String> getSupportedEventTypes() {
        return SUPPORTED_EVENT_TYPES;
    }
}
