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

import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.util.Map;

/**
 * Builder class for creating AuditEvent instances.
 * Provides a fluent API for constructing audit events.
 */
public class AuditEventBuilder {

    private final AuditEvent event;

    public AuditEventBuilder() {
        this.event = new AuditEvent();
    }

    /**
     * Creates a new builder with basic information from CallContext.
     * @param callContext The CMIS call context
     * @return A new builder instance
     */
    public static AuditEventBuilder fromCallContext(CallContext callContext) {
        AuditEventBuilder builder = new AuditEventBuilder();
        if (callContext != null) {
            builder.repositoryId(callContext.getRepositoryId());
            builder.userId(callContext.getUsername());
        }
        return builder;
    }

    /**
     * Creates a new builder for a specific operation.
     * @param operation The audit operation
     * @return A new builder instance
     */
    public static AuditEventBuilder forOperation(AuditOperation operation) {
        return new AuditEventBuilder().operation(operation);
    }

    public AuditEventBuilder repositoryId(String repositoryId) {
        event.setRepositoryId(repositoryId);
        return this;
    }

    public AuditEventBuilder userId(String userId) {
        event.setUserId(userId);
        return this;
    }

    public AuditEventBuilder clientIp(String clientIp) {
        event.setClientIp(clientIp);
        return this;
    }

    public AuditEventBuilder operation(AuditOperation operation) {
        if (operation != null) {
            event.setOperation(operation.name());
            event.setOperationDescription(operation.getDescription());
        }
        return this;
    }

    public AuditEventBuilder operation(String operationName) {
        event.setOperation(operationName);
        AuditOperation op = AuditOperation.fromMethodName(operationName);
        if (op != AuditOperation.UNKNOWN) {
            event.setOperationDescription(op.getDescription());
        }
        return this;
    }

    public AuditEventBuilder objectId(String objectId) {
        event.setObjectId(objectId);
        return this;
    }

    public AuditEventBuilder objectName(String objectName) {
        event.setObjectName(objectName);
        return this;
    }

    public AuditEventBuilder objectPath(String objectPath) {
        event.setObjectPath(objectPath);
        return this;
    }

    public AuditEventBuilder objectType(String objectType) {
        event.setObjectType(objectType);
        return this;
    }

    public AuditEventBuilder success() {
        event.setResult(AuditEvent.Result.SUCCESS.name());
        return this;
    }

    public AuditEventBuilder failure(String errorMessage) {
        event.setResult(AuditEvent.Result.FAILURE.name());
        // Sanitize error message to prevent sensitive data leakage
        event.setErrorMessage(sanitizeErrorMessage(errorMessage));
        return this;
    }

    public AuditEventBuilder failure(Throwable throwable) {
        event.setResult(AuditEvent.Result.FAILURE.name());
        if (throwable != null) {
            // Sanitize error message to prevent sensitive data leakage
            String sanitizedMessage = sanitizeErrorMessage(throwable.getMessage());
            event.setErrorMessage(throwable.getClass().getSimpleName() + ": " + sanitizedMessage);
        }
        return this;
    }

    /**
     * Sanitizes error messages to mask potentially sensitive information.
     * @param message The original error message
     * @return The sanitized message with sensitive data masked
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        // Mask password, token, key, secret patterns in error messages
        // Pattern: key = value or key : value (case-insensitive, allows spaces around delimiter)
        String sanitized = message;
        sanitized = sanitized.replaceAll(
            "(?i)(password|passwd|pwd|token|apikey|api_key|secret|credential|auth)\\s*[=:]\\s*[^\\s,;\"'\\]()]+",
            "$1=***");
        // Also mask Bearer tokens in error messages
        sanitized = sanitized.replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-_\\.]+", "Bearer ***");
        // Truncate very long messages (might contain stack traces)
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500) + "...[truncated]";
        }
        return sanitized;
    }

    public AuditEventBuilder partial(String message) {
        event.setResult(AuditEvent.Result.PARTIAL.name());
        // Sanitize partial result messages to prevent sensitive data leakage
        event.setErrorMessage(sanitizeErrorMessage(message));
        return this;
    }

    public AuditEventBuilder durationMs(long durationMs) {
        event.setDurationMs(durationMs);
        return this;
    }

    public AuditEventBuilder detail(String key, Object value) {
        event.addDetail(key, value);
        return this;
    }

    public AuditEventBuilder details(Map<String, Object> details) {
        if (details != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                event.addDetail(entry.getKey(), entry.getValue());
            }
        }
        return this;
    }

    /**
     * Adds parent folder information.
     * @param parentId The parent folder ID
     * @return This builder
     */
    public AuditEventBuilder parentId(String parentId) {
        return detail("parentId", parentId);
    }

    /**
     * Adds content stream information.
     * @param mimeType The MIME type
     * @param length The content length in bytes
     * @return This builder
     */
    public AuditEventBuilder contentStream(String mimeType, Long length) {
        detail("mimeType", mimeType);
        detail("contentLength", length);
        return this;
    }

    /**
     * Adds versioning information.
     * @param versionLabel The version label
     * @param versionSeriesId The version series ID
     * @return This builder
     */
    public AuditEventBuilder versioning(String versionLabel, String versionSeriesId) {
        detail("versionLabel", versionLabel);
        detail("versionSeriesId", versionSeriesId);
        return this;
    }

    /**
     * Adds ACL change information.
     * @param principalId The principal affected
     * @param permission The permission changed
     * @return This builder
     */
    public AuditEventBuilder aclChange(String principalId, String permission) {
        detail("principalId", principalId);
        detail("permission", permission);
        return this;
    }

    /**
     * Adds query information.
     * @param statement The CMIS query statement
     * @param resultCount The number of results
     * @return This builder
     */
    public AuditEventBuilder query(String statement, Integer resultCount) {
        // Sanitize query statement to mask potential sensitive values in WHERE clauses
        String sanitizedStatement = sanitizeQueryStatement(statement);
        detail("queryStatement", truncateForLog(sanitizedStatement, 500));
        detail("resultCount", resultCount);
        return this;
    }

    /**
     * Sanitizes CMIS query statements to mask potentially sensitive values.
     * @param statement The original query statement
     * @return The sanitized statement with sensitive values masked
     */
    private String sanitizeQueryStatement(String statement) {
        if (statement == null) {
            return null;
        }
        // Mask string literals in WHERE clauses that might contain sensitive data
        // Pattern: 'value' or "value" - mask values that look like passwords, tokens, etc.
        String sanitized = statement;
        // Mask patterns that look like password/token comparisons
        sanitized = sanitized.replaceAll("(?i)(password|token|secret|credential)\\s*=\\s*'[^']*'", "$1='***'");
        sanitized = sanitized.replaceAll("(?i)(password|token|secret|credential)\\s*=\\s*\"[^\"]*\"", "$1=\"***\"");
        return sanitized;
    }

    /**
     * Adds move operation information.
     * @param sourceFolderId The source folder ID
     * @param targetFolderId The target folder ID
     * @return This builder
     */
    public AuditEventBuilder move(String sourceFolderId, String targetFolderId) {
        detail("sourceFolderId", sourceFolderId);
        detail("targetFolderId", targetFolderId);
        return this;
    }

    /**
     * Builds the AuditEvent instance.
     * @return The constructed AuditEvent
     */
    public AuditEvent build() {
        // If no result is set, default to SUCCESS
        if (event.getResult() == null) {
            event.setResult(AuditEvent.Result.SUCCESS.name());
        }
        return event;
    }

    /**
     * Truncates a string for safe logging.
     * @param value The value to truncate
     * @param maxLength Maximum length
     * @return Truncated string
     */
    private String truncateForLog(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
