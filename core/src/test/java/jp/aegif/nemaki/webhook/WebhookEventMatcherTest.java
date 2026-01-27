package jp.aegif.nemaki.webhook;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
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
    
    @Before
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
        
        assertEquals("Should find 2 matching configs", 2, matches.size());
        assertTrue("Should include webhook-1", matches.stream().anyMatch(c -> "webhook-1".equals(c.getId())));
        assertTrue("Should include webhook-3", matches.stream().anyMatch(c -> "webhook-3".equals(c.getId())));
    }
    
    @Test
    public void testFindMatchingConfigsForUpdatedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED", "UPDATED")),
            createConfig("webhook-2", true, Arrays.asList("UPDATED", "DELETED")),
            createConfig("webhook-3", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "UPDATED");
        
        assertEquals("Should find 2 matching configs", 2, matches.size());
        assertTrue("Should include webhook-1", matches.stream().anyMatch(c -> "webhook-1".equals(c.getId())));
        assertTrue("Should include webhook-2", matches.stream().anyMatch(c -> "webhook-2".equals(c.getId())));
    }
    
    @Test
    public void testFindMatchingConfigsForDeletedEvent() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED")),
            createConfig("webhook-2", true, Arrays.asList("DELETED")),
            createConfig("webhook-3", true, Arrays.asList("SECURITY"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "DELETED");
        
        assertEquals("Should find 1 matching config", 1, matches.size());
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
        
        assertEquals("Should find 2 matching configs", 2, matches.size());
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
        
        assertEquals("Should find 2 matching configs (excluding disabled)", 2, matches.size());
        assertFalse("Should not include disabled webhook-2", 
            matches.stream().anyMatch(c -> "webhook-2".equals(c.getId())));
    }
    
    @Test
    public void testAllDisabledConfigsReturnsEmpty() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", false, Arrays.asList("CREATED")),
            createConfig("webhook-2", false, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertTrue("Should return empty list when all configs are disabled", matches.isEmpty());
    }
    
    // ========================================
    // Empty/Null Input Tests
    // ========================================
    
    @Test
    public void testFindMatchingConfigsWithEmptyList() {
        List<WebhookConfig> configs = Arrays.asList();
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "CREATED");
        
        assertNotNull("Should return empty list, not null", matches);
        assertTrue("Should return empty list", matches.isEmpty());
    }
    
    @Test
    public void testFindMatchingConfigsWithNullList() {
        List<WebhookConfig> matches = matcher.findMatchingConfigs(null, "CREATED");
        
        assertNotNull("Should return empty list, not null", matches);
        assertTrue("Should return empty list", matches.isEmpty());
    }
    
    @Test
    public void testFindMatchingConfigsWithNullEventType() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, null);
        
        assertNotNull("Should return empty list, not null", matches);
        assertTrue("Should return empty list for null event type", matches.isEmpty());
    }
    
    @Test
    public void testFindMatchingConfigsWithEmptyEventType() {
        List<WebhookConfig> configs = Arrays.asList(
            createConfig("webhook-1", true, Arrays.asList("CREATED"))
        );
        
        List<WebhookConfig> matches = matcher.findMatchingConfigs(configs, "");
        
        assertNotNull("Should return empty list, not null", matches);
        assertTrue("Should return empty list for empty event type", matches.isEmpty());
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
        assertEquals("Should match lowercase event", 1, matches1.size());
        
        // Test mixed case
        List<WebhookConfig> matches2 = matcher.findMatchingConfigs(configs, "Created");
        assertEquals("Should match mixed case event", 1, matches2.size());
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
        
        assertTrue("Should return empty list when no configs match", matches.isEmpty());
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
            
            assertEquals("Should match " + eventType + " event", 1, matches.size());
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
        assertTrue("CREATED should be valid", matcher.isValidEventType("CREATED"));
        assertTrue("UPDATED should be valid", matcher.isValidEventType("UPDATED"));
        assertTrue("DELETED should be valid", matcher.isValidEventType("DELETED"));
        assertTrue("SECURITY should be valid", matcher.isValidEventType("SECURITY"));
        
        // Case insensitive
        assertTrue("created should be valid", matcher.isValidEventType("created"));
        
        // Invalid events
        assertFalse("INVALID should not be valid", matcher.isValidEventType("INVALID"));
        assertFalse("null should not be valid", matcher.isValidEventType(null));
        assertFalse("empty should not be valid", matcher.isValidEventType(""));
    }
    
    @Test
    public void testGetSupportedEventTypes() {
        List<String> supportedTypes = matcher.getSupportedEventTypes();
        
        assertNotNull("Should return list of supported types", supportedTypes);
        assertTrue("Should include CREATED", supportedTypes.contains("CREATED"));
        assertTrue("Should include UPDATED", supportedTypes.contains("UPDATED"));
        assertTrue("Should include DELETED", supportedTypes.contains("DELETED"));
        assertTrue("Should include SECURITY", supportedTypes.contains("SECURITY"));
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
