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
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an audit event for CMIS/REST API operations.
 * This class is designed for JSON serialization to be consumed by
 * log aggregation systems like ELK Stack or Splunk.
 *
 * Includes ECS (Elastic Common Schema) compatible fields for better
 * integration with Elasticsearch/Kibana.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    // Core identification
    "eventId", "timestamp", "timestampMs", "traceId",
    // Server metadata
    "hostname", "service", "environment", "version",
    // Request context
    "repositoryId", "userId", "clientIp", "httpMethod", "requestPath", "userAgent",
    // Operation details
    "operation", "operationDescription", "objectId", "objectName",
    "objectPath", "objectType", "result", "errorMessage", "durationMs",
    // Extended details
    "details"
})
public class AuditEvent {

    // Use UTC for consistent timestamps across servers
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public enum Result {
        SUCCESS, FAILURE, PARTIAL
    }

    // Core identification
    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("timestampMs")
    private Long timestampMs;

    @JsonProperty("traceId")
    private String traceId;

    // Server metadata
    @JsonProperty("hostname")
    private String hostname;

    @JsonProperty("service")
    private String service;

    @JsonProperty("environment")
    private String environment;

    @JsonProperty("version")
    private String version;

    // Request context
    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("clientIp")
    private String clientIp;

    @JsonProperty("httpMethod")
    private String httpMethod;

    @JsonProperty("requestPath")
    private String requestPath;

    @JsonProperty("userAgent")
    private String userAgent;

    // Operation details
    @JsonProperty("operation")
    private String operation;

    @JsonProperty("operationDescription")
    private String operationDescription;

    @JsonProperty("objectId")
    private String objectId;

    @JsonProperty("objectName")
    private String objectName;

    @JsonProperty("objectPath")
    private String objectPath;

    @JsonProperty("objectType")
    private String objectType;

    @JsonProperty("result")
    private String result;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("durationMs")
    private Long durationMs;

    // Extended details
    @JsonProperty("details")
    private Map<String, Object> details;

    /**
     * Package-private constructor - use AuditEventBuilder to create instances.
     */
    AuditEvent() {
        Instant now = Instant.now();
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = ISO_FORMATTER.format(now);
        this.timestampMs = now.toEpochMilli();
        this.details = new HashMap<>();
    }

    // ECS (Elastic Common Schema) compatible getters
    // These provide aliases for standard ECS field names

    /**
     * ECS compatible timestamp field.
     * @return Same value as timestamp
     */
    @JsonProperty("@timestamp")
    public String getAtTimestamp() {
        return timestamp;
    }

    /**
     * ECS event.category field.
     * @return Always "audit" for audit events
     */
    @JsonProperty("event.category")
    public String getEventCategory() {
        return "audit";
    }

    /**
     * ECS event.type field derived from operation.
     * @return "change", "deletion", "access", or "info"
     */
    @JsonProperty("event.type")
    public String getEventType() {
        if (operation != null) {
            if (operation.contains("CREATE") || operation.contains("UPDATE") ||
                operation.contains("CHECK_IN") || operation.contains("CHECK_OUT") ||
                operation.contains("SET_") || operation.contains("APPEND_") ||
                operation.contains("APPLY_") || operation.contains("ADD_") ||
                operation.contains("MOVE") || operation.contains("COPY")) {
                return "change";
            } else if (operation.contains("DELETE") || operation.contains("REMOVE") ||
                       operation.contains("CANCEL")) {
                return "deletion";
            } else if (operation.contains("GET") || operation.contains("QUERY")) {
                return "access";
            }
        }
        return "info";
    }

    /**
     * ECS event.action field.
     * @return Same value as operation
     */
    @JsonProperty("event.action")
    public String getEventAction() {
        return operation;
    }

    /**
     * ECS event.outcome field.
     * @return "success", "failure", or lowercase result
     */
    @JsonProperty("event.outcome")
    public String getEventOutcome() {
        if ("SUCCESS".equals(result)) return "success";
        if ("FAILURE".equals(result)) return "failure";
        return result != null ? result.toLowerCase() : null;
    }

    /**
     * ECS user.id field alias.
     * @return Same value as userId
     */
    @JsonProperty("user.id")
    public String getUserIdEcs() {
        return userId;
    }

    /**
     * ECS source.ip field alias.
     * @return Same value as clientIp
     */
    @JsonProperty("source.ip")
    public String getSourceIp() {
        return clientIp;
    }

    /**
     * ECS service.name field alias.
     * @return Same value as service
     */
    @JsonProperty("service.name")
    public String getServiceName() {
        return service;
    }

    // Standard getters

    public String getEventId() {
        return eventId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Long getTimestampMs() {
        return timestampMs;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getHostname() {
        return hostname;
    }

    public String getService() {
        return service;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getVersion() {
        return version;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getUserId() {
        return userId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getOperation() {
        return operation;
    }

    public String getOperationDescription() {
        return operationDescription;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getObjectName() {
        return objectName;
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getObjectType() {
        return objectType;
    }

    public String getResult() {
        return result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    // Package-private setters for builder

    void setEventId(String eventId) {
        this.eventId = eventId;
    }

    void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    void setTimestampMs(Long timestampMs) {
        this.timestampMs = timestampMs;
    }

    void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    void setHostname(String hostname) {
        this.hostname = hostname;
    }

    void setService(String service) {
        this.service = service;
    }

    void setEnvironment(String environment) {
        this.environment = environment;
    }

    void setVersion(String version) {
        this.version = version;
    }

    void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    void setUserId(String userId) {
        this.userId = userId;
    }

    void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }

    void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    void setOperation(String operation) {
        this.operation = operation;
    }

    void setOperationDescription(String operationDescription) {
        this.operationDescription = operationDescription;
    }

    void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    void setObjectPath(String objectPath) {
        this.objectPath = objectPath;
    }

    void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    void setResult(String result) {
        this.result = result;
    }

    void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    void addDetail(String key, Object value) {
        if (this.details == null) {
            this.details = new HashMap<>();
        }
        if (value != null) {
            this.details.put(key, value);
        }
    }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "eventId='" + eventId + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", traceId='" + traceId + '\'' +
                ", hostname='" + hostname + '\'' +
                ", repositoryId='" + repositoryId + '\'' +
                ", userId='" + userId + '\'' +
                ", operation='" + operation + '\'' +
                ", objectId='" + objectId + '\'' +
                ", result='" + result + '\'' +
                '}';
    }
}
