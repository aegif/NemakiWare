/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.webhook;

import java.util.GregorianCalendar;

/**
 * Represents a single webhook delivery attempt log entry.
 * 
 * Design: 1 Attempt = 1 Log model
 * - Each delivery attempt creates a new log entry
 * - Manual resend maintains the same deliveryId but creates a new attemptId
 * - This allows tracking of retry history and idempotency
 */
public class WebhookDeliveryLog {
    
    /**
     * Unique identifier for this log entry (CouchDB document ID)
     */
    private String id;
    
    /**
     * Delivery ID - maintained across retries for idempotency
     * Manual resend keeps the same deliveryId
     */
    private String deliveryId;
    
    /**
     * Attempt ID - unique for each delivery attempt
     * Format: {deliveryId}-{attemptNumber}
     */
    private String attemptId;
    
    /**
     * The webhook configuration ID that triggered this delivery
     */
    private String webhookId;
    
    /**
     * The object ID that triggered the event
     */
    private String objectId;
    
    /**
     * The repository ID
     */
    private String repositoryId;
    
    /**
     * The webhook URL that was called
     */
    private String webhookUrl;
    
    /**
     * The event type that triggered this delivery
     */
    private String eventType;
    
    /**
     * The HTTP status code returned by the webhook endpoint
     */
    private Integer statusCode;
    
    /**
     * The response body from the webhook endpoint (truncated if too long)
     */
    private String responseBody;
    
    /**
     * Whether the delivery was successful (2xx status code)
     */
    private boolean success;
    
    /**
     * The attempt number (1-based)
     */
    private int attemptNumber;
    
    /**
     * Error message if delivery failed
     */
    private String errorMessage;
    
    /**
     * Response time in milliseconds
     */
    private Long responseTimeMs;
    
    /**
     * Payload size in bytes
     */
    private Long payloadSizeBytes;
    
    /**
     * Timestamp when this delivery attempt was made
     */
    private GregorianCalendar timestamp;
    
    /**
     * The change token at the time of the event
     */
    private String changeToken;
    
    /**
     * Delivery status for tracking
     */
    private DeliveryStatus status;
    
    /**
     * Enum for delivery status tracking
     */
    public enum DeliveryStatus {
        PENDING,        // Queued for delivery
        PROCESSING,     // Currently being delivered
        SUCCESS,        // Successfully delivered
        FAILED,         // Failed after all retries
        RETRY_PENDING   // Waiting for retry
    }
    
    public WebhookDeliveryLog() {
        this.timestamp = new GregorianCalendar();
        this.status = DeliveryStatus.PENDING;
        this.attemptNumber = 1;
    }
    
    // Getters and Setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getDeliveryId() {
        return deliveryId;
    }
    
    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }
    
    public String getAttemptId() {
        return attemptId;
    }
    
    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }
    
    public String getWebhookId() {
        return webhookId;
    }
    
    public void setWebhookId(String webhookId) {
        this.webhookId = webhookId;
    }
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public String getWebhookUrl() {
        return webhookUrl;
    }
    
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public Integer getStatusCode() {
        return statusCode;
    }
    
    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }
    
    public String getResponseBody() {
        return responseBody;
    }
    
    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public int getAttemptNumber() {
        return attemptNumber;
    }
    
    public void setAttemptNumber(int attemptNumber) {
        this.attemptNumber = attemptNumber;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public Long getResponseTimeMs() {
        return responseTimeMs;
    }
    
    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }
    
    public Long getPayloadSizeBytes() {
        return payloadSizeBytes;
    }
    
    public void setPayloadSizeBytes(Long payloadSizeBytes) {
        this.payloadSizeBytes = payloadSizeBytes;
    }
    
    public GregorianCalendar getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(GregorianCalendar timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getChangeToken() {
        return changeToken;
    }
    
    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
    }
    
    public DeliveryStatus getStatus() {
        return status;
    }
    
    public void setStatus(DeliveryStatus status) {
        this.status = status;
    }
    
    /**
     * Generate attempt ID from delivery ID and attempt number
     */
    public void generateAttemptId() {
        if (deliveryId != null) {
            this.attemptId = deliveryId + "-" + attemptNumber;
        }
    }
    
    /**
     * Mark this delivery as successful
     */
    public void markSuccess(int statusCode, String responseBody, long responseTimeMs) {
        this.success = true;
        this.statusCode = statusCode;
        this.responseBody = truncateResponse(responseBody);
        this.responseTimeMs = responseTimeMs;
        this.status = DeliveryStatus.SUCCESS;
    }
    
    /**
     * Mark this delivery as failed
     */
    public void markFailed(Integer statusCode, String errorMessage, long responseTimeMs) {
        this.success = false;
        this.statusCode = statusCode;
        this.errorMessage = errorMessage;
        this.responseTimeMs = responseTimeMs;
        this.status = DeliveryStatus.FAILED;
    }
    
    /**
     * Truncate response body to prevent storing large responses
     */
    private String truncateResponse(String response) {
        if (response == null) {
            return null;
        }
        int maxLength = 4096;
        if (response.length() > maxLength) {
            return response.substring(0, maxLength) + "... (truncated)";
        }
        return response;
    }
    
    @Override
    public String toString() {
        return "WebhookDeliveryLog{" +
            "deliveryId='" + deliveryId + '\'' +
            ", attemptId='" + attemptId + '\'' +
            ", webhookId='" + webhookId + '\'' +
            ", objectId='" + objectId + '\'' +
            ", eventType='" + eventType + '\'' +
            ", status=" + status +
            ", success=" + success +
            ", statusCode=" + statusCode +
            '}';
    }
}
