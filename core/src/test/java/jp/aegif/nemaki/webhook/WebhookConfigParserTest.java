package jp.aegif.nemaki.webhook;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

/**
 * Unit tests for WebhookConfigParser class.
 * 
 * TDD Approach: These tests define the expected behavior of WebhookConfigParser
 * before implementation. Tests should fail initially and pass after
 * implementing the WebhookConfigParser class.
 * 
 * WebhookConfigParser is responsible for parsing the nemaki:webhookConfigs
 * JSON property into a list of WebhookConfig objects.
 */
public class WebhookConfigParserTest {
    
    private static final Log log = LogFactory.getLog(WebhookConfigParserTest.class);
    
    private WebhookConfigParser parser;
    
    @Before
    public void setUp() {
        log.info("Setting up WebhookConfigParserTest");
        parser = new WebhookConfigParser();
    }
    
    // ========================================
    // Basic Parsing Tests
    // ========================================
    
    @Test
    public void testParseEmptyString() {
        List<WebhookConfig> configs = parser.parse("");
        assertNotNull("Should return empty list for empty string", configs);
        assertTrue("Should return empty list for empty string", configs.isEmpty());
    }
    
    @Test
    public void testParseNullString() {
        List<WebhookConfig> configs = parser.parse(null);
        assertNotNull("Should return empty list for null", configs);
        assertTrue("Should return empty list for null", configs.isEmpty());
    }
    
    @Test
    public void testParseEmptyArray() {
        List<WebhookConfig> configs = parser.parse("[]");
        assertNotNull("Should return empty list for empty array", configs);
        assertTrue("Should return empty list for empty array", configs.isEmpty());
    }
    
    @Test
    public void testParseSingleWebhookConfig() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\", \"UPDATED\"]" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        
        WebhookConfig config = configs.get(0);
        assertEquals("webhook-1", config.getId());
        assertTrue(config.isEnabled());
        assertEquals("https://example.com/webhook", config.getUrl());
        assertEquals(2, config.getEvents().size());
        assertTrue(config.getEvents().contains("CREATED"));
        assertTrue(config.getEvents().contains("UPDATED"));
    }
    
    @Test
    public void testParseMultipleWebhookConfigs() {
        String json = "[" +
            "{\"id\": \"webhook-1\", \"enabled\": true, \"url\": \"https://example.com/webhook1\", \"events\": [\"CREATED\"]}," +
            "{\"id\": \"webhook-2\", \"enabled\": false, \"url\": \"https://example.com/webhook2\", \"events\": [\"DELETED\"]}" +
            "]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 2 configs", 2, configs.size());
        
        assertEquals("webhook-1", configs.get(0).getId());
        assertTrue(configs.get(0).isEnabled());
        
        assertEquals("webhook-2", configs.get(1).getId());
        assertFalse(configs.get(1).isEnabled());
    }
    
    @Test
    public void testParseFullWebhookConfig() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\", \"UPDATED\", \"DELETED\"]," +
            "\"authType\": \"bearer\"," +
            "\"authCredential\": \"my-token\"," +
            "\"secret\": \"hmac-secret\"," +
            "\"headers\": {\"X-Custom-Header\": \"value1\"}," +
            "\"includeChildren\": true," +
            "\"maxDepth\": 5," +
            "\"retryCount\": 3" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        
        WebhookConfig config = configs.get(0);
        assertEquals("webhook-1", config.getId());
        assertTrue(config.isEnabled());
        assertEquals("https://example.com/webhook", config.getUrl());
        assertEquals(3, config.getEvents().size());
        assertEquals("bearer", config.getAuthType());
        assertEquals("my-token", config.getAuthCredential());
        assertEquals("hmac-secret", config.getSecret());
        assertEquals("value1", config.getHeaders().get("X-Custom-Header"));
        assertTrue(config.isIncludeChildren());
        assertEquals(Integer.valueOf(5), config.getMaxDepth());
        assertEquals(Integer.valueOf(3), config.getRetryCount());
    }
    
    // ========================================
    // Optional Fields Tests
    // ========================================
    
    @Test
    public void testParseWithMissingOptionalFields() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\"]" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        
        WebhookConfig config = configs.get(0);
        assertNull("authType should be null when not specified", config.getAuthType());
        assertNull("authCredential should be null when not specified", config.getAuthCredential());
        assertNull("secret should be null when not specified", config.getSecret());
        assertNotNull("headers should be empty map, not null", config.getHeaders());
        assertTrue("headers should be empty when not specified", config.getHeaders().isEmpty());
        assertFalse("includeChildren should be false when not specified", config.isIncludeChildren());
        assertNull("maxDepth should be null when not specified", config.getMaxDepth());
        assertNull("retryCount should be null when not specified", config.getRetryCount());
    }
    
    @Test
    public void testParseWithNullOptionalFields() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\"]," +
            "\"authType\": null," +
            "\"maxDepth\": null," +
            "\"retryCount\": null" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        
        WebhookConfig config = configs.get(0);
        assertNull("authType should be null", config.getAuthType());
        assertNull("maxDepth should be null", config.getMaxDepth());
        assertNull("retryCount should be null", config.getRetryCount());
    }
    
    // ========================================
    // Error Handling Tests
    // ========================================
    
    @Test
    public void testParseInvalidJson() {
        String invalidJson = "not valid json";
        
        List<WebhookConfig> configs = parser.parse(invalidJson);
        
        assertNotNull("Should return empty list for invalid JSON", configs);
        assertTrue("Should return empty list for invalid JSON", configs.isEmpty());
    }
    
    @Test
    public void testParseJsonObject() {
        // JSON object instead of array
        String json = "{\"id\": \"webhook-1\"}";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return empty list for non-array JSON", configs);
        assertTrue("Should return empty list for non-array JSON", configs.isEmpty());
    }
    
    @Test
    public void testParseWithMissingRequiredFields() {
        // Missing 'id' field
        String json = "[{" +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\"]" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        // Should still parse but config will be invalid
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        assertFalse("Config without ID should be invalid", configs.get(0).isValid());
    }
    
    // ========================================
    // Serialization Tests
    // ========================================
    
    @Test
    public void testSerializeEmptyList() {
        List<WebhookConfig> configs = List.of();
        String json = parser.serialize(configs);
        
        assertNotNull("Should return JSON string", json);
        assertEquals("[]", json);
    }
    
    @Test
    public void testSerializeSingleConfig() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED", "UPDATED"))
            .build();
        
        String json = parser.serialize(List.of(config));
        
        assertNotNull("Should return JSON string", json);
        assertTrue("Should contain id", json.contains("webhook-1"));
        assertTrue("Should contain url", json.contains("example.com") || json.contains("example.com\\/webhook"));
        assertTrue("Should contain events", json.contains("CREATED"));
    }
    
    @Test
    public void testRoundTrip() {
        String originalJson = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\", \"UPDATED\"]," +
            "\"authType\": \"bearer\"," +
            "\"authCredential\": \"my-token\"," +
            "\"includeChildren\": true," +
            "\"maxDepth\": 5" +
            "}]";
        
        // Parse
        List<WebhookConfig> configs = parser.parse(originalJson);
        assertEquals(1, configs.size());
        
        // Serialize
        String serializedJson = parser.serialize(configs);
        
        // Parse again
        List<WebhookConfig> reparsedConfigs = parser.parse(serializedJson);
        assertEquals(1, reparsedConfigs.size());
        
        // Verify data integrity
        WebhookConfig original = configs.get(0);
        WebhookConfig reparsed = reparsedConfigs.get(0);
        
        assertEquals(original.getId(), reparsed.getId());
        assertEquals(original.isEnabled(), reparsed.isEnabled());
        assertEquals(original.getUrl(), reparsed.getUrl());
        assertEquals(original.getEvents(), reparsed.getEvents());
        assertEquals(original.getAuthType(), reparsed.getAuthType());
        assertEquals(original.getAuthCredential(), reparsed.getAuthCredential());
        assertEquals(original.isIncludeChildren(), reparsed.isIncludeChildren());
        assertEquals(original.getMaxDepth(), reparsed.getMaxDepth());
    }
    
    // ========================================
    // Edge Cases
    // ========================================
    
    @Test
    public void testParseWithWhitespace() {
        String json = "  [  {  \"id\"  :  \"webhook-1\"  ,  \"enabled\"  :  true  ,  \"url\"  :  \"https://example.com/webhook\"  ,  \"events\"  :  [  \"CREATED\"  ]  }  ]  ";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should handle whitespace", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        assertEquals("webhook-1", configs.get(0).getId());
    }
    
    @Test
    public void testParseWithUnicodeCharacters() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": [\"CREATED\"]," +
            "\"headers\": {\"X-Custom\": \"\\u65e5\\u672c\\u8a9e\"}" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should handle unicode", configs);
        assertEquals("Should have 1 config", 1, configs.size());
    }
    
    @Test
    public void testParseWithEmptyEventsArray() {
        String json = "[{" +
            "\"id\": \"webhook-1\"," +
            "\"enabled\": true," +
            "\"url\": \"https://example.com/webhook\"," +
            "\"events\": []" +
            "}]";
        
        List<WebhookConfig> configs = parser.parse(json);
        
        assertNotNull("Should return list", configs);
        assertEquals("Should have 1 config", 1, configs.size());
        assertTrue("Events should be empty", configs.get(0).getEvents().isEmpty());
        assertFalse("Config with empty events should be invalid", configs.get(0).isValid());
    }
}
