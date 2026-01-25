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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an audit event for CMIS/REST API operations.
 * This class is designed for JSON serialization to be consumed by
 * log aggregation systems like ELK Stack or Splunk.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "eventId", "timestamp", "repositoryId", "userId", "clientIp",
    "operation", "operationDescription", "objectId", "objectName",
    "objectPath", "objectType", "result", "errorMessage", "durationMs", "details"
})
public class AuditEvent {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    public enum Result {
        SUCCESS, FAILURE, PARTIAL
    }

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("clientIp")
    private String clientIp;

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

    @JsonProperty("details")
    private Map<String, Object> details;

    /**
     * Private constructor - use AuditEventBuilder to create instances.
     */
    AuditEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = ISO_FORMATTER.format(Instant.now());
        this.details = new HashMap<>();
    }

    // Getters

    public String getEventId() {
        return eventId;
    }

    public String getTimestamp() {
        return timestamp;
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

    void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    void setUserId(String userId) {
        this.userId = userId;
    }

    void setClientIp(String clientIp) {
        this.clientIp = clientIp;
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
                ", repositoryId='" + repositoryId + '\'' +
                ", userId='" + userId + '\'' +
                ", operation='" + operation + '\'' +
                ", objectId='" + objectId + '\'' +
                ", result='" + result + '\'' +
                '}';
    }
}
