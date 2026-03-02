package jp.aegif.nemaki.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Unit tests for WebhookDeliveryService class.
 * 
 * TDD Approach: These tests define the expected behavior of WebhookDeliveryService
 * before implementation. Tests should fail initially and pass after
 * implementing the WebhookDeliveryService class.
 * 
 * WebhookDeliveryService is responsible for:
 * - Building webhook payloads for events
 * - Generating authentication headers
 * - Computing HMAC signatures
 * - Managing delivery attempts with retry logic
 */
public class WebhookDeliveryServiceTest {
    
    private static final Log log = LogFactory.getLog(WebhookDeliveryServiceTest.class);
    
    private WebhookDeliveryService deliveryService;
    
    @BeforeEach
    public void setUp() {
        log.info("Setting up WebhookDeliveryServiceTest");
        deliveryService = new WebhookDeliveryService();
    }
    
    // ========================================
    // Payload Building Tests
    // ========================================
    
    @Test
    public void testBuildPayloadForCreatedEvent() {
        String eventType = "CREATED";
        String objectId = "doc-123";
        String repositoryId = "bedroom";
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", "test-document.txt");
        properties.put("cmis:objectTypeId", "cmis:document");
        
        WebhookPayload payload = deliveryService.buildPayload(
            eventType, objectId, repositoryId, properties, null
        );
        
        assertNotNull(payload, "Payload should not be null");
        assertEquals("CREATED", payload.getEventType());
        assertEquals("doc-123", payload.getObjectId());
        assertEquals("bedroom", payload.getRepositoryId());
        assertNotNull(payload.getTimestamp(), "Timestamp should be set");
        assertNotNull(payload.getDeliveryId(), "DeliveryId should be generated");
        assertEquals("test-document.txt", payload.getProperties().get("cmis:name"));
    }
    
    @Test
    public void testBuildPayloadForUpdatedEvent() {
        String eventType = "UPDATED";
        String objectId = "doc-456";
        String repositoryId = "bedroom";
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", "updated-document.txt");
        
        WebhookPayload payload = deliveryService.buildPayload(
            eventType, objectId, repositoryId, properties, null
        );
        
        assertNotNull(payload, "Payload should not be null");
        assertEquals("UPDATED", payload.getEventType());
        assertEquals("doc-456", payload.getObjectId());
    }
    
    @Test
    public void testBuildPayloadForDeletedEvent() {
        String eventType = "DELETED";
        String objectId = "doc-789";
        String repositoryId = "bedroom";
        
        WebhookPayload payload = deliveryService.buildPayload(
            eventType, objectId, repositoryId, null, null
        );
        
        assertNotNull(payload, "Payload should not be null");
        assertEquals("DELETED", payload.getEventType());
        assertEquals("doc-789", payload.getObjectId());
    }
    
    @Test
    public void testBuildPayloadForSecurityEvent() {
        String eventType = "SECURITY";
        String objectId = "folder-123";
        String repositoryId = "bedroom";
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", "secure-folder");
        
        WebhookPayload payload = deliveryService.buildPayload(
            eventType, objectId, repositoryId, properties, null
        );
        
        assertNotNull(payload, "Payload should not be null");
        assertEquals("SECURITY", payload.getEventType());
    }
    
    @Test
    public void testBuildPayloadWithChangeToken() {
        String eventType = "UPDATED";
        String objectId = "doc-123";
        String repositoryId = "bedroom";
        String changeToken = "change-token-12345";
        
        WebhookPayload payload = deliveryService.buildPayload(
            eventType, objectId, repositoryId, null, changeToken
        );
        
        assertNotNull(payload, "Payload should not be null");
        assertEquals(changeToken, payload.getChangeToken());
    }
    
    // ========================================
    // Authentication Header Tests
    // ========================================
    
    @Test
    public void testGenerateAuthHeaderNone() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .authType("none")
            .build();
        
        Map<String, String> headers = deliveryService.generateAuthHeaders(config);
        
        assertNotNull(headers, "Headers should not be null");
        assertFalse(headers.containsKey("Authorization"), "Should not contain Authorization header");
    }
    
    @Test
    public void testGenerateAuthHeaderBasic() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .authType("basic")
            .authCredential("user:password")
            .build();
        
        Map<String, String> headers = deliveryService.generateAuthHeaders(config);
        
        assertNotNull(headers, "Headers should not be null");
        assertTrue(headers.containsKey("Authorization"), "Should contain Authorization header");
        assertTrue(headers.get("Authorization").startsWith("Basic "), "Should be Basic auth");
    }
    
    @Test
    public void testGenerateAuthHeaderBearer() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .authType("bearer")
            .authCredential("my-token-12345")
            .build();
        
        Map<String, String> headers = deliveryService.generateAuthHeaders(config);
        
        assertNotNull(headers, "Headers should not be null");
        assertTrue(headers.containsKey("Authorization"), "Should contain Authorization header");
        assertEquals("Bearer my-token-12345", headers.get("Authorization"));
    }
    
    @Test
    public void testGenerateAuthHeaderApiKey() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .authType("apikey")
            .authCredential("api-key-secret")
            .build();
        
        Map<String, String> headers = deliveryService.generateAuthHeaders(config);
        
        assertNotNull(headers, "Headers should not be null");
        assertTrue(headers.containsKey("X-API-Key"), "Should contain X-API-Key header");
        assertEquals("api-key-secret", headers.get("X-API-Key"));
    }
    
    @Test
    public void testGenerateAuthHeaderWithCustomHeaders() {
        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("X-Custom-Header", "custom-value");
        customHeaders.put("X-Another-Header", "another-value");
        
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .authType("bearer")
            .authCredential("token")
            .headers(customHeaders)
            .build();
        
        Map<String, String> headers = deliveryService.generateAuthHeaders(config);
        
        assertNotNull(headers, "Headers should not be null");
        assertEquals("custom-value", headers.get("X-Custom-Header"));
        assertEquals("another-value", headers.get("X-Another-Header"));
        assertTrue(headers.containsKey("Authorization"), "Should also contain Authorization");
    }
    
    // ========================================
    // HMAC Signature Tests
    // ========================================
    
    @Test
    public void testComputeHmacSignature() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        String secret = "my-secret-key";
        
        String signature = deliveryService.computeHmacSignature(payload, secret);
        
        assertNotNull(signature, "Signature should not be null");
        assertFalse(signature.isEmpty(), "Signature should not be empty");
        // HMAC-SHA256 produces 64 hex characters
        assertEquals(64, signature.length(), "HMAC-SHA256 should produce 64 hex chars");
    }
    
    @Test
    public void testComputeHmacSignatureConsistency() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        String secret = "my-secret-key";
        
        String signature1 = deliveryService.computeHmacSignature(payload, secret);
        String signature2 = deliveryService.computeHmacSignature(payload, secret);
        
        assertEquals(signature1, signature2, "Same payload and secret should produce same signature");
    }
    
    @Test
    public void testComputeHmacSignatureDifferentSecrets() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        
        String signature1 = deliveryService.computeHmacSignature(payload, "secret1");
        String signature2 = deliveryService.computeHmacSignature(payload, "secret2");
        
        assertNotEquals(signature1, signature2, "Different secrets should produce different signatures");
    }
    
    @Test
    public void testComputeHmacSignatureNullSecret() {
        String payload = "{\"eventType\":\"CREATED\"}";
        
        String signature = deliveryService.computeHmacSignature(payload, null);
        
        assertNull(signature, "Null secret should return null signature");
    }
    
    // ========================================
    // Delivery ID Tests
    // ========================================
    
    @Test
    public void testGenerateDeliveryId() {
        String deliveryId = deliveryService.generateDeliveryId();
        
        assertNotNull(deliveryId, "DeliveryId should not be null");
        assertFalse(deliveryId.isEmpty(), "DeliveryId should not be empty");
    }
    
    @Test
    public void testGenerateDeliveryIdUniqueness() {
        String id1 = deliveryService.generateDeliveryId();
        String id2 = deliveryService.generateDeliveryId();
        
        assertNotEquals(id1, id2, "Each delivery ID should be unique");
    }
    
    // ========================================
    // Retry Logic Tests
    // ========================================
    
    @Test
    public void testCalculateBackoffDelay() {
        // First retry (attempt 1)
        long delay1 = deliveryService.calculateBackoffDelay(1);
        assertTrue(delay1 >= 1000, "First retry should have delay >= 1000ms");
        
        // Second retry (attempt 2)
        long delay2 = deliveryService.calculateBackoffDelay(2);
        assertTrue(delay2 > delay1, "Second retry should have longer delay");
        
        // Third retry (attempt 3)
        long delay3 = deliveryService.calculateBackoffDelay(3);
        assertTrue(delay3 > delay2, "Third retry should have even longer delay");
    }
    
    @Test
    public void testCalculateBackoffDelayMaxCap() {
        // Very high attempt number should be capped
        long delay = deliveryService.calculateBackoffDelay(100);
        
        // Max delay should be capped at 5 minutes (300000ms)
        assertTrue(delay <= 300000, "Delay should be capped at max value");
    }
    
    @Test
    public void testShouldRetry() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .retryCount(3)
            .build();
        
        assertTrue(deliveryService.shouldRetry(config, 1, 500), "Should retry on attempt 1");
        assertTrue(deliveryService.shouldRetry(config, 2, 503), "Should retry on attempt 2");
        assertTrue(deliveryService.shouldRetry(config, 3, 502), "Should retry on attempt 3");
        assertFalse(deliveryService.shouldRetry(config, 4, 500), "Should not retry after max attempts");
    }
    
    @Test
    public void testShouldNotRetryOnSuccess() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .retryCount(3)
            .build();
        
        assertFalse(deliveryService.shouldRetry(config, 1, 200), "Should not retry on 200");
        assertFalse(deliveryService.shouldRetry(config, 1, 201), "Should not retry on 201");
        assertFalse(deliveryService.shouldRetry(config, 1, 204), "Should not retry on 204");
    }
    
    @Test
    public void testShouldNotRetryOnClientError() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .retryCount(3)
            .build();
        
        // 4xx errors (except 429) should not be retried
        assertFalse(deliveryService.shouldRetry(config, 1, 400), "Should not retry on 400");
        assertFalse(deliveryService.shouldRetry(config, 1, 401), "Should not retry on 401");
        assertFalse(deliveryService.shouldRetry(config, 1, 403), "Should not retry on 403");
        assertFalse(deliveryService.shouldRetry(config, 1, 404), "Should not retry on 404");
    }
    
    @Test
    public void testShouldRetryOn429() {
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .retryCount(3)
            .build();
        
        // 429 Too Many Requests should be retried
        assertTrue(deliveryService.shouldRetry(config, 1, 429), "Should retry on 429");
    }
    
    // ========================================
    // Payload Serialization Tests
    // ========================================
    
    @Test
    public void testSerializePayload() {
        WebhookPayload payload = new WebhookPayload();
        payload.setEventType("CREATED");
        payload.setObjectId("doc-123");
        payload.setRepositoryId("bedroom");
        payload.setDeliveryId("delivery-abc");
        payload.setTimestamp(System.currentTimeMillis());
        
        String json = deliveryService.serializePayload(payload);
        
        assertNotNull(json, "JSON should not be null");
        assertTrue(json.contains("CREATED"), "Should contain eventType");
        assertTrue(json.contains("doc-123"), "Should contain objectId");
        assertTrue(json.contains("bedroom"), "Should contain repositoryId");
        assertTrue(json.contains("delivery-abc"), "Should contain deliveryId");
    }
    
    // ========================================
    // Null Safety Tests (Review Feedback)
    // ========================================
    
    @Test
    public void testShouldRetryWithNullConfig() {
        // Should return false when config is null (not throw NPE)
        assertFalse(deliveryService.shouldRetry(null, 1, 500), "Should return false for null config");
    }
    
    @Test
    public void testShouldRetryWithNullRetryCount() {
        // Config with null retryCount should default to 0 retries
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            // retryCount not set (null)
            .build();
        
        // With null retryCount (defaults to 0), attempt 1 should not retry
        assertFalse(deliveryService.shouldRetry(config, 1, 500), "Should not retry when retryCount is null (defaults to 0)");
    }
    
    @Test
    public void testGenerateAuthHeadersWithNullConfig() {
        // Should return empty map when config is null (not throw NPE)
        Map<String, String> headers = deliveryService.generateAuthHeaders(null);
        
        assertNotNull(headers, "Should return empty map, not null");
        assertTrue(headers.isEmpty(), "Should return empty map for null config");
    }
    
    @Test
    public void testCalculateBackoffDelayWithNonPositiveAttempt() {
        // Should handle non-positive attempt numbers gracefully
        long delay0 = deliveryService.calculateBackoffDelay(0);
        long delay1 = deliveryService.calculateBackoffDelay(1);
        long delayNegative = deliveryService.calculateBackoffDelay(-1);
        
        // All should return the base delay (normalized to attempt 1)
        assertTrue(delay0 >= 1000, "Attempt 0 should return valid delay");
        assertTrue(delay1 >= 1000, "Attempt 1 should return valid delay");
        assertTrue(delayNegative >= 1000, "Negative attempt should return valid delay");
    }
    
    // ========================================
    // Sensitive Property Filtering Tests
    // ========================================
    
    @Test
    public void testBuildPayloadFiltersSensitiveProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", "test-document.txt");
        properties.put("cmis:objectTypeId", "cmis:document");
        properties.put("nemaki:webhookConfigs", "[{\"secret\":\"my-secret\"}]");
        properties.put("nemaki:webhookSecret", "secret-value");
        properties.put("nemaki:authCredential", "auth-credential");
        
        WebhookPayload payload = deliveryService.buildPayload(
            "CREATED", "doc-123", "bedroom", properties, null
        );
        
        // Non-sensitive properties should be included
        assertEquals("test-document.txt", payload.getProperties().get("cmis:name"));
        assertEquals("cmis:document", payload.getProperties().get("cmis:objectTypeId"));
        
        // Sensitive properties should be filtered out
        assertNull(payload.getProperties().get("nemaki:webhookConfigs"), "nemaki:webhookConfigs should be filtered");
        assertNull(payload.getProperties().get("nemaki:webhookSecret"), "nemaki:webhookSecret should be filtered");
        assertNull(payload.getProperties().get("nemaki:authCredential"), "nemaki:authCredential should be filtered");
    }
    
    @Test
    public void testSerializePayloadFiltersSensitiveProperties() {
        // Create payload directly (simulating alternate creation path)
        WebhookPayload payload = new WebhookPayload();
        payload.setEventType("CREATED");
        payload.setObjectId("doc-123");
        payload.setRepositoryId("bedroom");
        payload.setDeliveryId("delivery-abc");
        payload.setTimestamp(System.currentTimeMillis());
        
        // Set properties directly with sensitive data
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", "test-document.txt");
        properties.put("nemaki:webhookConfigs", "[{\"secret\":\"my-secret\"}]");
        payload.setProperties(properties);
        
        String json = deliveryService.serializePayload(payload);
        
        // Non-sensitive properties should be included
        assertTrue(json.contains("test-document.txt"), "Should contain cmis:name");
        
        // Sensitive properties should be filtered out even in serializePayload
        assertFalse(json.contains("nemaki:webhookConfigs"), "nemaki:webhookConfigs should be filtered in serialization");
        assertFalse(json.contains("my-secret"), "Secret value should not appear in JSON");
    }
    
    @Test
    public void testShouldRetryWithNegativeRetryCount() {
        // Config with negative retryCount should be treated as 0
        WebhookConfig config = new WebhookConfig.Builder()
            .id("webhook-1")
            .enabled(true)
            .url("https://example.com/webhook")
            .events(List.of("CREATED"))
            .retryCount(-5)
            .build();
        
        // With negative retryCount (treated as 0), attempt 1 should not retry
        assertFalse(deliveryService.shouldRetry(config, 1, 500), "Should not retry when retryCount is negative (treated as 0)");
    }
}
