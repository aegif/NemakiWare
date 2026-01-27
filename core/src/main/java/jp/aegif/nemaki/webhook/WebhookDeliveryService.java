package jp.aegif.nemaki.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;

/**
 * Service for delivering webhook notifications.
 * 
 * This service handles:
 * - Building webhook payloads for events
 * - Generating authentication headers (Basic, Bearer, API Key)
 * - Computing HMAC signatures for payload verification
 * - Managing delivery attempts with exponential backoff retry logic
 * 
 * Phase 1 supports: CREATED, UPDATED, DELETED, SECURITY events
 */
public class WebhookDeliveryService {
    
    private static final Log log = LogFactory.getLog(WebhookDeliveryService.class);
    
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 300000; // 5 minutes
    
    public WebhookDeliveryService() {
        log.debug("WebhookDeliveryService initialized");
    }
    
    /**
     * Build a webhook payload for an event.
     * 
     * @param eventType The type of event (CREATED, UPDATED, DELETED, SECURITY)
     * @param objectId The ID of the affected object
     * @param repositoryId The repository ID
     * @param properties Optional object properties to include
     * @param changeToken Optional change token for ordering
     * @return WebhookPayload ready for delivery
     */
    public WebhookPayload buildPayload(String eventType, String objectId, 
            String repositoryId, Map<String, Object> properties, String changeToken) {
        
        WebhookPayload payload = new WebhookPayload();
        payload.setEventType(eventType);
        payload.setObjectId(objectId);
        payload.setRepositoryId(repositoryId);
        payload.setDeliveryId(generateDeliveryId());
        payload.setTimestamp(System.currentTimeMillis());
        
        if (properties != null) {
            payload.setProperties(new HashMap<>(properties));
        }
        
        if (changeToken != null) {
            payload.setChangeToken(changeToken);
        }
        
        log.debug("Built payload for event: " + eventType + ", object: " + objectId);
        return payload;
    }
    
    /**
     * Generate authentication headers based on webhook configuration.
     * 
     * @param config The webhook configuration
     * @return Map of header name to header value
     */
    public Map<String, String> generateAuthHeaders(WebhookConfig config) {
        Map<String, String> headers = new HashMap<>();
        
        // Add custom headers first
        if (config.getHeaders() != null) {
            headers.putAll(config.getHeaders());
        }
        
        String authType = config.getAuthType();
        String authCredential = config.getAuthCredential();
        
        if (authType == null || "none".equalsIgnoreCase(authType)) {
            return headers;
        }
        
        if (authCredential == null || authCredential.isEmpty()) {
            log.warn("Auth type is " + authType + " but no credential provided for webhook: " + config.getId());
            return headers;
        }
        
        switch (authType.toLowerCase()) {
            case "basic":
                String encoded = Base64.getEncoder().encodeToString(
                    authCredential.getBytes(StandardCharsets.UTF_8)
                );
                headers.put("Authorization", "Basic " + encoded);
                break;
                
            case "bearer":
                headers.put("Authorization", "Bearer " + authCredential);
                break;
                
            case "apikey":
                headers.put("X-API-Key", authCredential);
                break;
                
            default:
                log.warn("Unknown auth type: " + authType + " for webhook: " + config.getId());
        }
        
        return headers;
    }
    
    /**
     * Compute HMAC-SHA256 signature for payload verification.
     * 
     * @param payload The JSON payload string
     * @param secret The secret key for signing
     * @return Hex-encoded signature, or null if secret is null
     */
    public String computeHmacSignature(String payload, String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM
            );
            mac.init(secretKeySpec);
            
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hmacBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute HMAC signature", e);
            return null;
        }
    }
    
    /**
     * Generate a unique delivery ID for idempotency.
     * 
     * @return A unique delivery ID
     */
    public String generateDeliveryId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Calculate exponential backoff delay for retry attempts.
     * 
     * @param attemptNumber The current attempt number (1-based)
     * @return Delay in milliseconds before next retry
     */
    public long calculateBackoffDelay(int attemptNumber) {
        // Exponential backoff: base * 2^(attempt-1)
        // With jitter to avoid thundering herd
        long delay = BASE_RETRY_DELAY_MS * (long) Math.pow(2, attemptNumber - 1);
        
        // Cap at maximum delay
        delay = Math.min(delay, MAX_RETRY_DELAY_MS);
        
        // Add jitter (up to 10% of delay)
        long jitter = (long) (delay * 0.1 * Math.random());
        delay += jitter;
        
        return delay;
    }
    
    /**
     * Determine if a delivery should be retried based on response status.
     * 
     * @param config The webhook configuration
     * @param attemptNumber The current attempt number (1-based)
     * @param statusCode The HTTP response status code
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(WebhookConfig config, int attemptNumber, int statusCode) {
        // Check if we've exceeded max retries
        int maxRetries = config.getRetryCount();
        if (attemptNumber > maxRetries) {
            return false;
        }
        
        // Success - no retry needed
        if (statusCode >= 200 && statusCode < 300) {
            return false;
        }
        
        // Client errors (4xx) except 429 - don't retry
        if (statusCode >= 400 && statusCode < 500 && statusCode != 429) {
            return false;
        }
        
        // Server errors (5xx) and 429 - retry
        return true;
    }
    
    /**
     * Serialize a webhook payload to JSON string.
     * 
     * @param payload The payload to serialize
     * @return JSON string representation
     */
    @SuppressWarnings("unchecked")
    public String serializePayload(WebhookPayload payload) {
        JSONObject json = new JSONObject();
        
        json.put("eventType", payload.getEventType());
        json.put("objectId", payload.getObjectId());
        json.put("repositoryId", payload.getRepositoryId());
        json.put("deliveryId", payload.getDeliveryId());
        json.put("timestamp", payload.getTimestamp());
        
        if (payload.getChangeToken() != null) {
            json.put("changeToken", payload.getChangeToken());
        }
        
        if (payload.getObjectPath() != null) {
            json.put("objectPath", payload.getObjectPath());
        }
        
        if (payload.getParentId() != null) {
            json.put("parentId", payload.getParentId());
        }
        
        if (payload.getUserId() != null) {
            json.put("userId", payload.getUserId());
        }
        
        if (payload.getProperties() != null && !payload.getProperties().isEmpty()) {
            JSONObject propsJson = new JSONObject();
            propsJson.putAll(payload.getProperties());
            json.put("properties", propsJson);
        }
        
        return json.toJSONString();
    }
}
