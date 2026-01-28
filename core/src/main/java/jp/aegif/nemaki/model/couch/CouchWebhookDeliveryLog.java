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
 *     aegif - Webhook delivery log CouchDB model
 ******************************************************************************/
package jp.aegif.nemaki.model.couch;

import java.util.GregorianCalendar;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.webhook.WebhookDeliveryLog;
import jp.aegif.nemaki.webhook.WebhookDeliveryLog.DeliveryStatus;

/**
 * CouchDB model for WebhookDeliveryLog.
 * 
 * Document type: "webhookDeliveryLog"
 * View: webhookDeliveryLogs (by repositoryId, objectId, webhookId)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchWebhookDeliveryLog extends CouchNodeBase {
    
    private static final String TYPE = "webhookDeliveryLog";
    
    @JsonProperty("deliveryId")
    private String deliveryId;
    
    @JsonProperty("attemptId")
    private String attemptId;
    
    @JsonProperty("webhookId")
    private String webhookId;
    
    @JsonProperty("objectId")
    private String objectId;
    
    @JsonProperty("repositoryId")
    private String repositoryId;
    
    @JsonProperty("webhookUrl")
    private String webhookUrl;
    
    @JsonProperty("eventType")
    private String eventType;
    
    @JsonProperty("statusCode")
    private Integer statusCode;
    
    @JsonProperty("responseBody")
    private String responseBody;
    
    @JsonProperty("success")
    private boolean success;
    
    @JsonProperty("attemptNumber")
    private int attemptNumber;
    
    @JsonProperty("errorMessage")
    private String errorMessage;
    
    @JsonProperty("responseTimeMs")
    private Long responseTimeMs;
    
    @JsonProperty("payloadSizeBytes")
    private Long payloadSizeBytes;
    
    @JsonProperty("deliveryTimestamp")
    private Long deliveryTimestamp;
    
    @JsonProperty("changeToken")
    private String changeToken;
    
    @JsonProperty("deliveryStatus")
    private String deliveryStatus;
    
    public CouchWebhookDeliveryLog() {
        setType(TYPE);
    }
    
    @JsonCreator
    public CouchWebhookDeliveryLog(Map<String, Object> properties) {
        super(properties);
        setType(TYPE);
        
        if (properties != null) {
            if (properties.containsKey("deliveryId")) {
                this.deliveryId = (String) properties.get("deliveryId");
            }
            if (properties.containsKey("attemptId")) {
                this.attemptId = (String) properties.get("attemptId");
            }
            if (properties.containsKey("webhookId")) {
                this.webhookId = (String) properties.get("webhookId");
            }
            if (properties.containsKey("objectId")) {
                this.objectId = (String) properties.get("objectId");
            }
            if (properties.containsKey("repositoryId")) {
                this.repositoryId = (String) properties.get("repositoryId");
            }
            if (properties.containsKey("webhookUrl")) {
                this.webhookUrl = (String) properties.get("webhookUrl");
            }
            if (properties.containsKey("eventType")) {
                this.eventType = (String) properties.get("eventType");
            }
            if (properties.containsKey("statusCode")) {
                Object sc = properties.get("statusCode");
                if (sc instanceof Number) {
                    this.statusCode = ((Number) sc).intValue();
                }
            }
            if (properties.containsKey("responseBody")) {
                this.responseBody = (String) properties.get("responseBody");
            }
            if (properties.containsKey("success")) {
                Object s = properties.get("success");
                if (s instanceof Boolean) {
                    this.success = (Boolean) s;
                }
            }
            if (properties.containsKey("attemptNumber")) {
                Object an = properties.get("attemptNumber");
                if (an instanceof Number) {
                    this.attemptNumber = ((Number) an).intValue();
                }
            }
            if (properties.containsKey("errorMessage")) {
                this.errorMessage = (String) properties.get("errorMessage");
            }
            if (properties.containsKey("responseTimeMs")) {
                Object rt = properties.get("responseTimeMs");
                if (rt instanceof Number) {
                    this.responseTimeMs = ((Number) rt).longValue();
                }
            }
            if (properties.containsKey("payloadSizeBytes")) {
                Object ps = properties.get("payloadSizeBytes");
                if (ps instanceof Number) {
                    this.payloadSizeBytes = ((Number) ps).longValue();
                }
            }
            if (properties.containsKey("deliveryTimestamp")) {
                Object dt = properties.get("deliveryTimestamp");
                if (dt instanceof Number) {
                    this.deliveryTimestamp = ((Number) dt).longValue();
                }
            }
            if (properties.containsKey("changeToken")) {
                this.changeToken = (String) properties.get("changeToken");
            }
            if (properties.containsKey("deliveryStatus")) {
                this.deliveryStatus = (String) properties.get("deliveryStatus");
            }
        }
    }
    
    public CouchWebhookDeliveryLog(WebhookDeliveryLog log) {
        setType(TYPE);
        if (log.getId() != null) {
            setId(log.getId());
        }
        this.deliveryId = log.getDeliveryId();
        this.attemptId = log.getAttemptId();
        this.webhookId = log.getWebhookId();
        this.objectId = log.getObjectId();
        this.repositoryId = log.getRepositoryId();
        this.webhookUrl = log.getWebhookUrl();
        this.eventType = log.getEventType();
        this.statusCode = log.getStatusCode();
        this.responseBody = log.getResponseBody();
        this.success = log.isSuccess();
        this.attemptNumber = log.getAttemptNumber();
        this.errorMessage = log.getErrorMessage();
        this.responseTimeMs = log.getResponseTimeMs();
        this.payloadSizeBytes = log.getPayloadSizeBytes();
        this.changeToken = log.getChangeToken();
        
        if (log.getTimestamp() != null) {
            this.deliveryTimestamp = log.getTimestamp().getTimeInMillis();
        }
        if (log.getStatus() != null) {
            this.deliveryStatus = log.getStatus().name();
        }
    }
    
    public WebhookDeliveryLog convertToDeliveryLog() {
        WebhookDeliveryLog log = new WebhookDeliveryLog();
        log.setId(getId());
        log.setDeliveryId(deliveryId);
        log.setAttemptId(attemptId);
        log.setWebhookId(webhookId);
        log.setObjectId(objectId);
        log.setRepositoryId(repositoryId);
        log.setWebhookUrl(webhookUrl);
        log.setEventType(eventType);
        log.setStatusCode(statusCode);
        log.setResponseBody(responseBody);
        log.setSuccess(success);
        log.setAttemptNumber(attemptNumber);
        log.setErrorMessage(errorMessage);
        log.setResponseTimeMs(responseTimeMs);
        log.setPayloadSizeBytes(payloadSizeBytes);
        log.setChangeToken(changeToken);
        
        if (deliveryTimestamp != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTimeInMillis(deliveryTimestamp);
            log.setTimestamp(cal);
        }
        if (deliveryStatus != null) {
            try {
                log.setStatus(DeliveryStatus.valueOf(deliveryStatus));
            } catch (IllegalArgumentException e) {
                log.setStatus(DeliveryStatus.PENDING);
            }
        }
        
        return log;
    }
    
    // Getters and Setters
    
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
    
    public Long getDeliveryTimestamp() {
        return deliveryTimestamp;
    }
    
    public void setDeliveryTimestamp(Long deliveryTimestamp) {
        this.deliveryTimestamp = deliveryTimestamp;
    }
    
    public String getChangeToken() {
        return changeToken;
    }
    
    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
    }
    
    public String getDeliveryStatus() {
        return deliveryStatus;
    }
    
    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }
}
