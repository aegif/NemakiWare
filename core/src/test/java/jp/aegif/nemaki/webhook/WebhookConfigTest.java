package jp.aegif.nemaki.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Unit tests for WebhookConfig model class.
 * 
 * TDD Approach: These tests define the expected behavior of WebhookConfig
 * before implementation. Tests should fail initially and pass after
 * implementing the WebhookConfig class.
 * 
 * WebhookConfig represents a single webhook configuration stored in
 * the nemaki:webhookConfigs JSON property.
 */
public class WebhookConfigTest {
    
    private static final Log log = LogFactory.getLog(WebhookConfigTest.class);
    
    @BeforeEach
    public void setUp() {
        log.info("Setting up WebhookConfigTest");
    }
    
    // ========================================
    // Basic Construction Tests
    // ========================================
    
    @Test
    public void testDefaultConstructor() {
        WebhookConfig config = new WebhookConfig();
        assertNotNull(config, "WebhookConfig should be created");
        assertNull(config.getId(), "ID should be null by default");
        assertFalse(config.isEnabled(), "Should be disabled by default");
        assertNull(config.getUrl(), "URL should be null by default");
        assertNotNull(config.getEvents(), "Events should be empty list, not null");
        assertTrue(config.getEvents().isEmpty(), "Events should be empty by default");
    }
    
    @Test
    public void testBuilderPattern() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED", "UPDATED"))
            .authType("bearer")
            .authCredential("test-token")
            .secret("hmac-secret")
            .includeChildren(true)
            .maxDepth(5)
            .retryCount(3)
            .build();
        
        assertEquals("webhook-1", config.getId());
        assertTrue(config.isEnabled());
        assertEquals("https://example.com/webhook", config.getUrl());
        assertEquals(2, config.getEvents().size());
        assertTrue(config.getEvents().contains("CREATED"));
        assertTrue(config.getEvents().contains("UPDATED"));
        assertEquals("bearer", config.getAuthType());
        assertEquals("test-token", config.getAuthCredential());
        assertEquals("hmac-secret", config.getSecret());
        assertTrue(config.isIncludeChildren());
        assertEquals(Integer.valueOf(5), config.getMaxDepth());
        assertEquals(Integer.valueOf(3), config.getRetryCount());
    }
    
    // ========================================
    // Getter/Setter Tests
    // ========================================
    
    @Test
    public void testIdGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setId("test-id");
        assertEquals("test-id", config.getId());
    }
    
    @Test
    public void testEnabledGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setEnabled(true);
        assertTrue(config.isEnabled());
        config.setEnabled(false);
        assertFalse(config.isEnabled());
    }
    
    @Test
    public void testUrlGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setUrl("https://example.com/webhook");
        assertEquals("https://example.com/webhook", config.getUrl());
    }
    
    @Test
    public void testEventsGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        List<String> events = Arrays.asList("CREATED", "UPDATED", "DELETED");
        config.setEvents(events);
        assertEquals(3, config.getEvents().size());
        assertTrue(config.getEvents().contains("CREATED"));
        assertTrue(config.getEvents().contains("UPDATED"));
        assertTrue(config.getEvents().contains("DELETED"));
    }
    
    @Test
    public void testAuthTypeGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthType("bearer");
        assertEquals("bearer", config.getAuthType());
    }
    
    @Test
    public void testAuthCredentialGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthCredential("my-token");
        assertEquals("my-token", config.getAuthCredential());
    }
    
    @Test
    public void testSecretGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setSecret("hmac-secret");
        assertEquals("hmac-secret", config.getSecret());
    }
    
    @Test
    public void testHeadersGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-Header", "value1");
        headers.put("X-Another-Header", "value2");
        config.setHeaders(headers);
        
        assertEquals(2, config.getHeaders().size());
        assertEquals("value1", config.getHeaders().get("X-Custom-Header"));
        assertEquals("value2", config.getHeaders().get("X-Another-Header"));
    }
    
    @Test
    public void testIncludeChildrenGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setIncludeChildren(true);
        assertTrue(config.isIncludeChildren());
        config.setIncludeChildren(false);
        assertFalse(config.isIncludeChildren());
    }
    
    @Test
    public void testMaxDepthGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setMaxDepth(10);
        assertEquals(Integer.valueOf(10), config.getMaxDepth());
    }
    
    @Test
    public void testRetryCountGetterSetter() {
        WebhookConfig config = new WebhookConfig();
        config.setRetryCount(5);
        assertEquals(Integer.valueOf(5), config.getRetryCount());
    }
    
    // ========================================
    // Event Matching Tests
    // ========================================
    
    @Test
    public void testMatchesEventWhenEnabled() {
        WebhookConfig config = new WebhookConfig();
        config.setEnabled(true);
        config.setEvents(Arrays.asList("CREATED", "UPDATED"));
        
        assertTrue(config.matchesEvent("CREATED"), "Should match CREATED event");
        assertTrue(config.matchesEvent("UPDATED"), "Should match UPDATED event");
        assertFalse(config.matchesEvent("DELETED"), "Should not match DELETED event");
    }
    
    @Test
    public void testMatchesEventWhenDisabled() {
        WebhookConfig config = new WebhookConfig();
        config.setEnabled(false);
        config.setEvents(Arrays.asList("CREATED", "UPDATED"));
        
        assertFalse(config.matchesEvent("CREATED"), "Should not match any event when disabled");
        assertFalse(config.matchesEvent("UPDATED"), "Should not match any event when disabled");
    }
    
    @Test
    public void testMatchesEventWithEmptyEventsList() {
        WebhookConfig config = new WebhookConfig();
        config.setEnabled(true);
        config.setEvents(Arrays.asList());
        
        assertFalse(config.matchesEvent("CREATED"), "Should not match any event with empty events list");
    }
    
    @Test
    public void testMatchesEventCaseInsensitive() {
        WebhookConfig config = new WebhookConfig();
        config.setEnabled(true);
        config.setEvents(Arrays.asList("CREATED", "UPDATED"));
        
        assertTrue(config.matchesEvent("created"), "Should match case-insensitively");
        assertTrue(config.matchesEvent("Created"), "Should match case-insensitively");
    }
    
    // ========================================
    // Validation Tests
    // ========================================
    
    @Test
    public void testIsValidWithAllRequiredFields() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .build();
        
        assertTrue(config.isValid(), "Config with all required fields should be valid");
    }
    
    @Test
    public void testIsValidWithoutId() {
        WebhookConfig config = new WebhookConfig.Builder()
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .build();
        
        assertFalse(config.isValid(), "Config without ID should be invalid");
    }
    
    @Test
    public void testIsValidWithoutUrl() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .events(Arrays.asList("CREATED"))
            .build();
        
        assertFalse(config.isValid(), "Config without URL should be invalid");
    }
    
    @Test
    public void testIsValidWithoutEvents() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .build();
        
        assertFalse(config.isValid(), "Config without events should be invalid");
    }
    
    @Test
    public void testIsValidWithEmptyEvents() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList())
            .build();
        
        assertFalse(config.isValid(), "Config with empty events should be invalid");
    }
    
    // ========================================
    // Auth Type Tests
    // ========================================
    
    @Test
    public void testAuthTypeNone() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthType("none");
        assertEquals("none", config.getAuthType());
        assertFalse(config.requiresAuthCredential(), "Should not require credential for 'none' auth");
    }
    
    @Test
    public void testAuthTypeBasic() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthType("basic");
        assertEquals("basic", config.getAuthType());
        assertTrue(config.requiresAuthCredential(), "Should require credential for 'basic' auth");
    }
    
    @Test
    public void testAuthTypeBearer() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthType("bearer");
        assertEquals("bearer", config.getAuthType());
        assertTrue(config.requiresAuthCredential(), "Should require credential for 'bearer' auth");
    }
    
    @Test
    public void testAuthTypeApiKey() {
        WebhookConfig config = new WebhookConfig();
        config.setAuthType("apikey");
        assertEquals("apikey", config.getAuthType());
        assertTrue(config.requiresAuthCredential(), "Should require credential for 'apikey' auth");
    }
    
    // ========================================
    // Equals and HashCode Tests
    // ========================================
    
    @Test
    public void testEqualsWithSameId() {
        WebhookConfig config1 = new WebhookConfig();
        config1.setId("webhook-1");
        
        WebhookConfig config2 = new WebhookConfig();
        config2.setId("webhook-1");
        
        assertEquals(config1, config2, "Configs with same ID should be equal");
        assertEquals(config1.hashCode(), config2.hashCode(), "Configs with same ID should have same hashCode");
    }
    
    @Test
    public void testEqualsWithDifferentId() {
        WebhookConfig config1 = new WebhookConfig();
        config1.setId("webhook-1");
        
        WebhookConfig config2 = new WebhookConfig();
        config2.setId("webhook-2");
        
        assertNotEquals(config1, config2, "Configs with different ID should not be equal");
    }
    
    // ========================================
    // ToString Test
    // ========================================
    
    @Test
    public void testToString() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .build();
        
        String str = config.toString();
        assertNotNull(str, "toString should not return null");
        assertTrue(str.contains("webhook-1"), "toString should contain ID");
        assertTrue(str.contains("https://example.com/webhook"), "toString should contain URL");
    }
    
    // ========================================
    // Auth Credential Validation Tests (Review Feedback)
    // ========================================
    
    @Test
    public void testIsValidWithAuthTypeRequiringCredential() {
        // Config with authType=bearer but no credential should be invalid
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .authType("bearer")
            // authCredential not set
            .build();
        
        assertFalse(config.isValid(), "Config with bearer auth but no credential should be invalid");
    }
    
    @Test
    public void testIsValidWithAuthTypeAndCredential() {
        // Config with authType=bearer and credential should be valid
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .authType("bearer")
            .authCredential("my-token")
            .build();
        
        assertTrue(config.isValid(), "Config with bearer auth and credential should be valid");
    }
    
    @Test
    public void testIsValidWithAuthTypeNone() {
        // Config with authType=none should be valid without credential
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .authType("none")
            .build();
        
        assertTrue(config.isValid(), "Config with none auth should be valid without credential");
    }
    
    @Test
    public void testIsValidWithBasicAuthMissingCredential() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .authType("basic")
            .build();
        
        assertFalse(config.isValid(), "Config with basic auth but no credential should be invalid");
    }
    
    @Test
    public void testIsValidWithApiKeyMissingCredential() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(Arrays.asList("CREATED"))
            .authType("apikey")
            .build();
        
        assertFalse(config.isValid(), "Config with apikey auth but no credential should be invalid");
    }
}
