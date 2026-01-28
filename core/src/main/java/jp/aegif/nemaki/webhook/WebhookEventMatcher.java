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
 * a notification for a given event type. Supports:
 * - Phase 1 event types: CREATED, UPDATED, DELETED, SECURITY
 * - Phase 4 event types: CHILD_CREATED, CHILD_UPDATED, CHILD_DELETED, CHILD_BATCH
 */
public class WebhookEventMatcher {
    
    private static final Log log = LogFactory.getLog(WebhookEventMatcher.class);
    
    /**
     * Supported event types including Phase 1 and Phase 4 (CHILD_*) events.
     * Phase 1: CMIS ChangeType values
     * Phase 4: CHILD_* events for folder child monitoring
     */
    private static final List<String> SUPPORTED_EVENT_TYPES = Collections.unmodifiableList(
        Arrays.asList(
            // Phase 1 events (CMIS ChangeType)
            "CREATED",   // ChangeType.CREATED
            "UPDATED",   // ChangeType.UPDATED
            "DELETED",   // ChangeType.DELETED
            "SECURITY",  // ChangeType.SECURITY (ACL changes)
            // Phase 4 events (CHILD_* for folder monitoring)
            "CHILD_CREATED",  // Child object created in folder
            "CHILD_UPDATED",  // Child object updated in folder
            "CHILD_DELETED",  // Child object deleted from folder
            "CHILD_BATCH"     // Batched child events
        )
    );
    
    /**
     * CHILD_* event types that require special batch processing.
     */
    private static final List<String> CHILD_EVENT_TYPES = Collections.unmodifiableList(
        Arrays.asList(
            "CHILD_CREATED",
            "CHILD_UPDATED", 
            "CHILD_DELETED"
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
    
    /**
     * Check if the given event type is a CHILD_* event that requires batch processing.
     * 
     * @param eventType The event type to check
     * @return true if the event type is a CHILD_* event
     */
    public boolean isChildEventType(String eventType) {
        if (eventType == null || eventType.trim().isEmpty()) {
            return false;
        }
        
        String normalized = eventType.toUpperCase().trim();
        return CHILD_EVENT_TYPES.contains(normalized);
    }
    
    /**
     * Get the list of CHILD_* event types.
     * 
     * @return Unmodifiable list of CHILD_* event type strings
     */
    public List<String> getChildEventTypes() {
        return CHILD_EVENT_TYPES;
    }
    
    /**
     * Convert a standard event type to its corresponding CHILD_* event type.
     * For example, CREATED -> CHILD_CREATED, UPDATED -> CHILD_UPDATED, DELETED -> CHILD_DELETED.
     * 
     * @param eventType The standard event type
     * @return The corresponding CHILD_* event type, or null if no mapping exists
     */
    public String toChildEventType(String eventType) {
        if (eventType == null || eventType.trim().isEmpty()) {
            return null;
        }
        
        String normalized = eventType.toUpperCase().trim();
        switch (normalized) {
            case "CREATED":
                return "CHILD_CREATED";
            case "UPDATED":
                return "CHILD_UPDATED";
            case "DELETED":
                return "CHILD_DELETED";
            default:
                return null;
        }
    }
}
