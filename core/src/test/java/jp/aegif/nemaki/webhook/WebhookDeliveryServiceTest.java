package jp.aegif.nemaki.webhook;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
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
    
    @Before
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
        
        assertNotNull("Payload should not be null", payload);
        assertEquals("CREATED", payload.getEventType());
        assertEquals("doc-123", payload.getObjectId());
        assertEquals("bedroom", payload.getRepositoryId());
        assertNotNull("Timestamp should be set", payload.getTimestamp());
        assertNotNull("DeliveryId should be generated", payload.getDeliveryId());
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
        
        assertNotNull("Payload should not be null", payload);
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
        
        assertNotNull("Payload should not be null", payload);
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
        
        assertNotNull("Payload should not be null", payload);
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
        
        assertNotNull("Payload should not be null", payload);
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
        
        assertNotNull("Headers should not be null", headers);
        assertFalse("Should not contain Authorization header", headers.containsKey("Authorization"));
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
        
        assertNotNull("Headers should not be null", headers);
        assertTrue("Should contain Authorization header", headers.containsKey("Authorization"));
        assertTrue("Should be Basic auth", headers.get("Authorization").startsWith("Basic "));
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
        
        assertNotNull("Headers should not be null", headers);
        assertTrue("Should contain Authorization header", headers.containsKey("Authorization"));
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
        
        assertNotNull("Headers should not be null", headers);
        assertTrue("Should contain X-API-Key header", headers.containsKey("X-API-Key"));
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
        
        assertNotNull("Headers should not be null", headers);
        assertEquals("custom-value", headers.get("X-Custom-Header"));
        assertEquals("another-value", headers.get("X-Another-Header"));
        assertTrue("Should also contain Authorization", headers.containsKey("Authorization"));
    }
    
    // ========================================
    // HMAC Signature Tests
    // ========================================
    
    @Test
    public void testComputeHmacSignature() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        String secret = "my-secret-key";
        
        String signature = deliveryService.computeHmacSignature(payload, secret);
        
        assertNotNull("Signature should not be null", signature);
        assertFalse("Signature should not be empty", signature.isEmpty());
        // HMAC-SHA256 produces 64 hex characters
        assertEquals("HMAC-SHA256 should produce 64 hex chars", 64, signature.length());
    }
    
    @Test
    public void testComputeHmacSignatureConsistency() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        String secret = "my-secret-key";
        
        String signature1 = deliveryService.computeHmacSignature(payload, secret);
        String signature2 = deliveryService.computeHmacSignature(payload, secret);
        
        assertEquals("Same payload and secret should produce same signature", signature1, signature2);
    }
    
    @Test
    public void testComputeHmacSignatureDifferentSecrets() {
        String payload = "{\"eventType\":\"CREATED\",\"objectId\":\"doc-123\"}";
        
        String signature1 = deliveryService.computeHmacSignature(payload, "secret1");
        String signature2 = deliveryService.computeHmacSignature(payload, "secret2");
        
        assertNotEquals("Different secrets should produce different signatures", signature1, signature2);
    }
    
    @Test
    public void testComputeHmacSignatureNullSecret() {
        String payload = "{\"eventType\":\"CREATED\"}";
        
        String signature = deliveryService.computeHmacSignature(payload, null);
        
        assertNull("Null secret should return null signature", signature);
    }
    
    // ========================================
    // Delivery ID Tests
    // ========================================
    
    @Test
    public void testGenerateDeliveryId() {
        String deliveryId = deliveryService.generateDeliveryId();
        
        assertNotNull("DeliveryId should not be null", deliveryId);
        assertFalse("DeliveryId should not be empty", deliveryId.isEmpty());
    }
    
    @Test
    public void testGenerateDeliveryIdUniqueness() {
        String id1 = deliveryService.generateDeliveryId();
        String id2 = deliveryService.generateDeliveryId();
        
        assertNotEquals("Each delivery ID should be unique", id1, id2);
    }
    
    // ========================================
    // Retry Logic Tests
    // ========================================
    
    @Test
    public void testCalculateBackoffDelay() {
        // First retry (attempt 1)
        long delay1 = deliveryService.calculateBackoffDelay(1);
        assertTrue("First retry should have delay >= 1000ms", delay1 >= 1000);
        
        // Second retry (attempt 2)
        long delay2 = deliveryService.calculateBackoffDelay(2);
        assertTrue("Second retry should have longer delay", delay2 > delay1);
        
        // Third retry (attempt 3)
        long delay3 = deliveryService.calculateBackoffDelay(3);
        assertTrue("Third retry should have even longer delay", delay3 > delay2);
    }
    
    @Test
    public void testCalculateBackoffDelayMaxCap() {
        // Very high attempt number should be capped
        long delay = deliveryService.calculateBackoffDelay(100);
        
        // Max delay should be capped at 5 minutes (300000ms)
        assertTrue("Delay should be capped at max value", delay <= 300000);
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
        
        assertTrue("Should retry on attempt 1", deliveryService.shouldRetry(config, 1, 500));
        assertTrue("Should retry on attempt 2", deliveryService.shouldRetry(config, 2, 503));
        assertTrue("Should retry on attempt 3", deliveryService.shouldRetry(config, 3, 502));
        assertFalse("Should not retry after max attempts", deliveryService.shouldRetry(config, 4, 500));
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
        
        assertFalse("Should not retry on 200", deliveryService.shouldRetry(config, 1, 200));
        assertFalse("Should not retry on 201", deliveryService.shouldRetry(config, 1, 201));
        assertFalse("Should not retry on 204", deliveryService.shouldRetry(config, 1, 204));
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
        assertFalse("Should not retry on 400", deliveryService.shouldRetry(config, 1, 400));
        assertFalse("Should not retry on 401", deliveryService.shouldRetry(config, 1, 401));
        assertFalse("Should not retry on 403", deliveryService.shouldRetry(config, 1, 403));
        assertFalse("Should not retry on 404", deliveryService.shouldRetry(config, 1, 404));
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
        assertTrue("Should retry on 429", deliveryService.shouldRetry(config, 1, 429));
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
        
        assertNotNull("JSON should not be null", json);
        assertTrue("Should contain eventType", json.contains("CREATED"));
        assertTrue("Should contain objectId", json.contains("doc-123"));
        assertTrue("Should contain repositoryId", json.contains("bedroom"));
        assertTrue("Should contain deliveryId", json.contains("delivery-abc"));
    }
    
    // ========================================
    // Null Safety Tests (Review Feedback)
    // ========================================
    
    @Test
    public void testShouldRetryWithNullConfig() {
        // Should return false when config is null (not throw NPE)
        assertFalse("Should return false for null config", 
            deliveryService.shouldRetry(null, 1, 500));
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
        assertFalse("Should not retry when retryCount is null (defaults to 0)", 
            deliveryService.shouldRetry(config, 1, 500));
    }
    
    @Test
    public void testGenerateAuthHeadersWithNullConfig() {
        // Should return empty map when config is null (not throw NPE)
        Map<String, String> headers = deliveryService.generateAuthHeaders(null);
        
        assertNotNull("Should return empty map, not null", headers);
        assertTrue("Should return empty map for null config", headers.isEmpty());
    }
    
    @Test
    public void testCalculateBackoffDelayWithNonPositiveAttempt() {
        // Should handle non-positive attempt numbers gracefully
        long delay0 = deliveryService.calculateBackoffDelay(0);
        long delay1 = deliveryService.calculateBackoffDelay(1);
        long delayNegative = deliveryService.calculateBackoffDelay(-1);
        
        // All should return the base delay (normalized to attempt 1)
        assertTrue("Attempt 0 should return valid delay", delay0 >= 1000);
        assertTrue("Attempt 1 should return valid delay", delay1 >= 1000);
        assertTrue("Negative attempt should return valid delay", delayNegative >= 1000);
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
        assertNull("nemaki:webhookConfigs should be filtered", 
            payload.getProperties().get("nemaki:webhookConfigs"));
        assertNull("nemaki:webhookSecret should be filtered", 
            payload.getProperties().get("nemaki:webhookSecret"));
        assertNull("nemaki:authCredential should be filtered", 
            payload.getProperties().get("nemaki:authCredential"));
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
        assertTrue("Should contain cmis:name", json.contains("test-document.txt"));
        
        // Sensitive properties should be filtered out even in serializePayload
        assertFalse("nemaki:webhookConfigs should be filtered in serialization", 
            json.contains("nemaki:webhookConfigs"));
        assertFalse("Secret value should not appear in JSON", 
            json.contains("my-secret"));
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
        assertFalse("Should not retry when retryCount is negative (treated as 0)", 
            deliveryService.shouldRetry(config, 1, 500));
    }
}
