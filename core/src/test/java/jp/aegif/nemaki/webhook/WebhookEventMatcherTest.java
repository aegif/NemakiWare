package jp.aegif.nemaki.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for WebhookEventMatcher class.
 * 
 * TDD Approach: These tests define the expected behavior of WebhookEventMatcher
 * before implementation. Tests should fail initially and pass after
 * implementing the WebhookEventMatcher class.
 * 
 * WebhookEventMatcher is responsible for finding all webhook configurations
 * that match a given event type and object context.
 */
public class WebhookEventMatcherTest {
    
    private static final Log log = LogFactory.getLog(WebhookEventMatcherTest.class);
    
    private WebhookEventMatcher matcher;
    
    @BeforeEach
    public void setUp() {
        log.info("Setting up WebhookEventMatcherTest");
        matcher = new WebhookEventMatcher();
    }
    
    // ========================================
    // Basic Matching Tests
    // ========================================
    
    @Test
    public void testFindMatchingConfigsForCreatedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "UPDATED")),
            createConfig("webhook-2", true, Arrays.asList("DELETED")),
            createConfig("webhook-3", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertEquals(2, matches.size(), "Should find 2 matching configs");
        assertTrue(matches.stream().anyMatch(c -> "webhook-1".equals(c.getId())), "Should include webhook-1");
        assertTrue(matches.stream().anyMatch(c -> "webhook-3".equals(c.getId())), "Should include webhook-3");
    }
    
    @Test
    public void testFindMatchingConfigsForUpdatedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "UPDATED")),
            createConfig("webhook-2", true, Arrays.asList("UPDATED", "DELETED")),
            createConfig("webhook-3", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "UPDATED");
        
        assertEquals(2, matches.size(), "Should find 2 matching configs");
        assertTrue(matches.stream().anyMatch(c -> "webhook-1".equals(c.getId())), "Should include webhook-1");
        assertTrue(matches.stream().anyMatch(c -> "webhook-2".equals(c.getId())), "Should include webhook-2");
    }
    
    @Test
    public void testFindMatchingConfigsForDeletedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED")),
            createConfig("webhook-2", true, Arrays.asList("DELETED")),
            createConfig("webhook-3", true, Arrays.asList("SECURITY"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "DELETED");
        
        assertEquals(1, matches.size(), "Should find 1 matching config");
        assertEquals("webhook-2", matches.get(0).getId());
    }
    
    @Test
    public void testFindMatchingConfigsForSecurityEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED")),
            createConfig("webhook-2", true, Arrays.asList("SECURITY")),
            createConfig("webhook-3", true, Arrays.asList("SECURITY", "DELETED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "SECURITY");
        
        assertEquals(2, matches.size(), "Should find 2 matching configs");
    }
    
    // ========================================
    // Disabled Config Tests
    // ========================================
    
    @Test
    public void testDisabledConfigsAreExcluded() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED")),
            createConfig("webhook-2", false, Arrays.asList("CREATED")),  // disabled
            createConfig("webhook-3", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertEquals(2, matches.size(), "Should find 2 matching configs (excluding disabled)");
        assertFalse(matches.stream().anyMatch(c -> "webhook-2".equals(c.getId())), "Should not include disabled webhook-2");
    }
    
    @Test
    public void testAllDisabledConfigsReturnsEmpty() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", false, Arrays.asList("CREATED")),
            createConfig("webhook-2", false, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertTrue(matches.isEmpty(), "Should return empty list when all configs are disabled");
    }
    
    // ========================================
    // Empty/Null Input Tests
    // ========================================
    
    @Test
    public void testFindMatchingConfigsWithEmptyList() {
        List<WebhookConfig> configs = Arrays.asList();
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertNotNull(matches, "Should return empty list, not null");
        assertTrue(matches.isEmpty(), "Should return empty list");
    }
    
    @Test
    public void testFindMatchingConfigsWithNullList() {
        List<WebhookConfig> matches = matcher.findMatchingConfigs(null, "CREATED");
        
        assertNotNull(matches, "Should return empty list, not null");
        assertTrue(matches.isEmpty(), "Should return empty list");
    }
    
    @Test
    public void testFindMatchingConfigsWithNullEventType() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, null);
        
        assertNotNull(matches, "Should return empty list, not null");
        assertTrue(matches.isEmpty(), "Should return empty list for null event type");
    }
    
    @Test
    public void testFindMatchingConfigsWithEmptyEventType() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "");
        
        assertNotNull(matches, "Should return empty list, not null");
        assertTrue(matches.isEmpty(), "Should return empty list for empty event type");
    }
    
    // ========================================
    // Case Insensitivity Tests
    // ========================================
    
    @Test
    public void testEventMatchingIsCaseInsensitive() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED"))
        );
        
        // Test lowercase
        List<WebhookConfig> matches1 = matcher.findMatchingConfigs(configs, "created");
        assertEquals(1, matches1.size(), "Should match lowercase event");
        
        // Test mixed case
        List<WebhookConfig> matches2 = matcher.findMatchingConfigs(configs, "Created");
        assertEquals(1, matches2.size(), "Should match mixed case event");
    }
    
    // ========================================
    // No Match Tests
    // ========================================
    
    @Test
    public void testNoMatchingConfigs() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED")),
            createConfig("webhook-2", true, Arrays.asList("UPDATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "DELETED");
        
        assertTrue(matches.isEmpty(), "Should return empty list when no configs match");
    }
    
    // ========================================
    // All Event Types Tests (Phase 1)
    // ========================================
    
    @Test
    public void testAllPhase1EventTypes() {
        // Phase 1 events: CREATED, UPDATED, DELETED, SECURITY
        String[] phase1Events = {"CREATED", "UPDATED", "DELETED", "SECURITY"};
        
        for (String eventType : phase1Events) {
            List<WebhookConfig> configs = Arrays.asList(
                createConfig("webhook-1", true, Arrays.asList(eventType))
            );
            
            List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, eventType);
            
            assertEquals(1, matches.size(), "Should match " + eventType + " event");
        }
    }
    
    // ========================================
    // Multiple Events Per Config Tests
    // ========================================
    
    @Test
    public void testConfigWithAllEvents() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "UPDATED", "DELETED", "SECURITY"))
        );
        
        // Should match all event types
        assertEquals(1, matcher.findMatchingConfigs(configs, "CREATED").size());
        assertEquals(1, matcher.findMatchingConfigs(configs, "UPDATED").size());
        assertEquals(1, matcher.findMatchingConfigs(configs, "DELETED").size());
        assertEquals(1, matcher.findMatchingConfigs(configs, "SECURITY").size());
    }
    
    // ========================================
    // Event Type Validation Tests
    // ========================================
    
    @Test
    public void testIsValidEventType() {
        // Valid Phase 1 events
        assertTrue(matcher.isValidEventType("CREATED"), "CREATED should be valid");
        assertTrue(matcher.isValidEventType("UPDATED"), "UPDATED should be valid");
        assertTrue(matcher.isValidEventType("DELETED"), "DELETED should be valid");
        assertTrue(matcher.isValidEventType("SECURITY"), "SECURITY should be valid");
        
        // Case insensitive
        assertTrue(matcher.isValidEventType("created"), "created should be valid");
        
        // Invalid events
        assertFalse(matcher.isValidEventType("INVALID"), "INVALID should not be valid");
        assertFalse(matcher.isValidEventType(null), "null should not be valid");
        assertFalse(matcher.isValidEventType(""), "empty should not be valid");
    }
    
    @Test
    public void testGetSupportedEventTypes() {
        List<String> supportedTypes = matcher.getSupportedEventTypes();
        
        assertNotNull(supportedTypes, "Should return list of supported types");
        assertTrue(supportedTypes.contains("CREATED"), "Should include CREATED");
        assertTrue(supportedTypes.contains("UPDATED"), "Should include UPDATED");
        assertTrue(supportedTypes.contains("DELETED"), "Should include DELETED");
        assertTrue(supportedTypes.contains("SECURITY"), "Should include SECURITY");
    }
    
    // ========================================
    // CONTENT_UPDATED Event Type Tests (P2 Regression)
    // ========================================

    @Test
    public void testContentUpdatedIsValidEventType() {
        assertTrue(matcher.isValidEventType("CONTENT_UPDATED"), "CONTENT_UPDATED should be a valid event type");
        assertTrue(matcher.isValidEventType("content_updated"), "content_updated (lowercase) should be valid");
    }

    @Test
    public void testContentUpdatedInSupportedEventTypes() {
        List<String> supported = matcher.getSupportedEventTypes();
        assertTrue(supported.contains("CONTENT_UPDATED"), "Supported event types should include CONTENT_UPDATED");
    }

    @Test
    public void testFindMatchingConfigsForContentUpdatedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "CONTENT_UPDATED")),
            createConfig("webhook-2", true, Arrays.asList("UPDATED")),
            createConfig("webhook-3", true, Arrays.asList("CONTENT_UPDATED"))
        );

        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CONTENT_UPDATED");

        assertEquals(2, matches.size(), "Should find 2 matching configs for CONTENT_UPDATED");
        assertTrue(matches.stream().anyMatch(c -> "webhook-1".equals(c.getId())), "Should include webhook-1");
        assertTrue(matches.stream().anyMatch(c -> "webhook-3".equals(c.getId())), "Should include webhook-3");
        assertFalse(matches.stream().anyMatch(c -> "webhook-2".equals(c.getId())), "Should not include webhook-2 (only has UPDATED, not CONTENT_UPDATED)");
    }

    @Test
    public void testContentUpdatedIsNotChildEventType() {
        assertFalse(matcher.isChildEventType("CONTENT_UPDATED"), "CONTENT_UPDATED should not be a child event type");
    }

    @Test
    public void testContentUpdatedHasNoChildEventMapping() {
        assertNull(matcher.toChildEventType("CONTENT_UPDATED"), "CONTENT_UPDATED should not map to a CHILD_* event type");
    }

    // ========================================
    // Helper Methods
    // ========================================

    // ========================================
    // Unsupported Event Type Tests (Review Feedback)
    // ========================================
    
    @Test
    public void testFindMatchingConfigsWithUnsupportedEventType() {
        // Configs that have unsupported event types in their list
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "INVALID_EVENT")),
            createConfig("webhook-2", true, Arrays.asList("CREATED"))
        );
        
        // Searching for an unsupported event type should return empty list
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "INVALID_EVENT");
        
        assertTrue(matches.isEmpty(), "Should return empty list for unsupported event type");
    }
    
    @Test
    public void testFindMatchingConfigsWithChildEventType() {
        // CHILD_CREATED is a supported event type (Phase 4)
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CHILD_CREATED"))
        );

        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CHILD_CREATED");

        assertEquals(1, matches.size(), "Should match CHILD_CREATED event type");
        assertEquals("webhook-1", matches.get(0).getId());
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private WebhookConfig createConfig(String id, boolean enabled, List<String> events) {
        WebhookConfig config = new WebhookConfig();
        config.setId(id);
        config.setEnabled(enabled);
        config.setUrl("https://example.com/" + id);
        config.setEvents(events);
        return config;
    }
}
