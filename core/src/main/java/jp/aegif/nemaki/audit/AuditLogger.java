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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Audit logger that intercepts CMIS and REST API operations using Spring AOP.
 * Outputs structured JSON logs suitable for ELK Stack, Splunk, and other
 * log aggregation platforms.
 */
public class AuditLogger {

    private static final Log log = LogFactory.getLog(AuditLogger.class);

    // Dedicated SLF4J logger for audit events (separate log file)
    private static final Logger auditLogger = LoggerFactory.getLogger("jp.aegif.nemaki.audit.AUDIT");

    private final ObjectMapper objectMapper;
    private final ReadWriteLock configLock = new ReentrantReadWriteLock(true);

    // Configuration - use volatile for fast reads without lock
    // Default to false for safety (requires explicit enablement in production)
    private volatile boolean enabled = false;
    private volatile boolean logReadOperations = false;
    private volatile DetailLevel detailLevel = DetailLevel.STANDARD;
    private volatile boolean logFailuresAsWarn = true;  // Log failures at WARN level

    // These require lock for iteration, but are read less frequently
    private Set<String> excludedUsers = new HashSet<>();
    private Set<String> excludedOperations = new HashSet<>();

    // ThreadLocal for storing request context (IP address, etc.)
    private static final ThreadLocal<RequestContext> requestContext = new ThreadLocal<>();

    private PropertyManager propertyManager;

    public enum DetailLevel {
        MINIMAL,    // Only basic info (user, operation, object ID, result)
        STANDARD,   // Basic + object name, type, duration
        VERBOSE     // All details including properties and content info
    }

    public AuditLogger() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    /**
     * Initializes the audit logger from configuration.
     */
    public void init() {
        loadConfiguration();
        if (log.isInfoEnabled()) {
            log.info("AuditLogger initialized. Enabled: " + enabled +
                    ", DetailLevel: " + detailLevel +
                    ", LogReadOperations: " + logReadOperations);
        }
    }

    /**
     * Loads configuration from property manager.
     */
    private void loadConfiguration() {
        configLock.writeLock().lock();
        try {
            if (propertyManager != null) {
                // Enable/disable audit logging (strict parsing - only "true" enables)
                // Default to false for safety - requires explicit enablement
                String enabledStr = propertyManager.readValue(PropertyKey.AUDIT_ENABLED);
                this.enabled = enabledStr != null &&
                               !enabledStr.trim().isEmpty() &&
                               "true".equalsIgnoreCase(enabledStr.trim());

                // Log read operations (strict parsing)
                String logReadsStr = propertyManager.readValue(PropertyKey.AUDIT_LOG_READ_OPERATIONS);
                this.logReadOperations = logReadsStr != null &&
                                         "true".equalsIgnoreCase(logReadsStr.trim());

                // Detail level
                String levelStr = propertyManager.readValue(PropertyKey.AUDIT_DETAIL_LEVEL);
                if (levelStr != null && !levelStr.trim().isEmpty()) {
                    try {
                        this.detailLevel = DetailLevel.valueOf(levelStr.trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid audit detail level: " + levelStr + ", using STANDARD");
                        this.detailLevel = DetailLevel.STANDARD;
                    }
                }

                // Excluded users (trim each entry)
                String excludedUsersStr = propertyManager.readValue(PropertyKey.AUDIT_EXCLUDE_USERS);
                if (excludedUsersStr != null && !excludedUsersStr.trim().isEmpty()) {
                    Set<String> users = new HashSet<>();
                    for (String user : excludedUsersStr.split(",")) {
                        String trimmed = user.trim();
                        if (!trimmed.isEmpty()) {
                            users.add(trimmed);
                        }
                    }
                    this.excludedUsers = users;
                }

                // Excluded operations (trim each entry)
                String excludedOpsStr = propertyManager.readValue(PropertyKey.AUDIT_EXCLUDE_OPERATIONS);
                if (excludedOpsStr != null && !excludedOpsStr.trim().isEmpty()) {
                    Set<String> ops = new HashSet<>();
                    for (String op : excludedOpsStr.split(",")) {
                        String trimmed = op.trim();
                        if (!trimmed.isEmpty()) {
                            ops.add(trimmed);
                        }
                    }
                    this.excludedOperations = ops;
                }

                // Log failures as WARN level (default: true if not specified)
                String logFailuresStr = propertyManager.readValue(PropertyKey.AUDIT_LOG_FAILURES_AS_WARN);
                this.logFailuresAsWarn = logFailuresStr == null ||
                                         logFailuresStr.trim().isEmpty() ||
                                         "true".equalsIgnoreCase(logFailuresStr.trim());
            }
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * Spring AOP Around advice for auditing CMIS service methods.
     * Uses volatile reads for fast path when audit is disabled.
     * @param jp The join point
     * @return The method result
     * @throws Throwable If the method throws an exception
     */
    public Object aroundMethod(ProceedingJoinPoint jp) throws Throwable {
        // Fast path: volatile read without lock for disabled check
        if (!enabled) {
            return jp.proceed();
        }
        // Audit is enabled - proceed with full processing
        return aroundMethodBody(jp);
    }

    private Object aroundMethodBody(ProceedingJoinPoint jp) throws Throwable {
        long startTime = System.currentTimeMillis();

        // Extract method information
        MethodSignature signature = (MethodSignature) jp.getSignature();
        String methodName = signature.getMethod().getName();
        Object[] args = jp.getArgs();

        // Determine operation
        AuditOperation operation = AuditOperation.fromMethodName(methodName);

        // Skip read operations if not configured to log them (volatile read)
        if (operation.isReadOnly() && !logReadOperations) {
            return jp.proceed();
        }

        // Skip excluded operations/users (requires lock for Set iteration)
        configLock.readLock().lock();
        try {
            if (excludedOperations.contains(operation.name()) ||
                    excludedOperations.contains(methodName)) {
                return jp.proceed();
            }

            // Extract context information
            CallContext callContext = extractCallContext(args);
            String userId = callContext != null ? callContext.getUsername() : null;

            // Skip excluded users
            if (userId != null && excludedUsers.contains(userId)) {
                return jp.proceed();
            }
        } finally {
            configLock.readLock().unlock();
        }

        // Re-extract context for building audit event (after lock release)
        CallContext callContext = extractCallContext(args);
        String userId = callContext != null ? callContext.getUsername() : null;

        // Build audit event
        AuditEventBuilder builder = AuditEventBuilder.fromCallContext(callContext)
                .operation(operation);

        // Add client IP if available
        RequestContext reqCtx = requestContext.get();
        if (reqCtx != null) {
            builder.clientIp(reqCtx.getClientIp());
        }

        // Extract object information from arguments
        extractObjectInfo(builder, args, signature.getParameterNames());

        // Execute the method
        Object result = null;
        Throwable error = null;
        try {
            result = jp.proceed();
            builder.success();
        } catch (Throwable t) {
            error = t;
            builder.failure(t);
        }

        // Calculate duration
        long durationMs = System.currentTimeMillis() - startTime;
        builder.durationMs(durationMs);

        // Add result information if verbose
        if (detailLevel == DetailLevel.VERBOSE && result != null) {
            addResultInfo(builder, result);
        }

        // Log the audit event
        AuditEvent event = builder.build();
        logAuditEvent(event);

        // Re-throw exception if occurred
        if (error != null) {
            throw error;
        }

        return result;
    }

    /**
     * Logs an audit event to the dedicated audit log.
     * Uses dynamic log level: WARN for failures, INFO for success.
     * @param event The audit event to log
     */
    public void logAuditEvent(AuditEvent event) {
        if (event == null) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            // Dynamic log level: WARN for failures if configured, INFO otherwise
            if (logFailuresAsWarn && AuditEvent.Result.FAILURE.name().equals(event.getResult())) {
                auditLogger.warn(json);
            } else {
                auditLogger.info(json);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event: " + e.getMessage(), e);
            // Fallback: output minimal structured JSON with essential fields only
            try {
                String fallbackJson = String.format(
                    "{\"eventId\":\"%s\",\"timestamp\":\"%s\",\"operation\":\"%s\",\"userId\":\"%s\",\"result\":\"%s\",\"_serializationError\":\"%s\"}",
                    escapeJson(event.getEventId()),
                    escapeJson(event.getTimestamp()),
                    escapeJson(event.getOperation()),
                    escapeJson(event.getUserId()),
                    escapeJson(event.getResult()),
                    escapeJson(e.getClass().getSimpleName())
                );
                auditLogger.warn(fallbackJson);
            } catch (Exception e2) {
                // Last resort: log critical error - audit log loss is serious
                log.error("CRITICAL: Failed to log audit event even with fallback. EventId: " +
                         event.getEventId() + ", Operation: " + event.getOperation(), e2);
            }
        } catch (Exception e) {
            // Unexpected exception (logger issues, etc.)
            log.error("Unexpected error logging audit event: " + e.getMessage(), e);
        }
    }

    /**
     * Escapes special characters for JSON string values.
     * Simple implementation for fallback logging only.
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    /**
     * Manually logs an audit event (for use by REST resources).
     * @param operation The operation being performed
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @param objectId The object ID (optional)
     * @param success Whether the operation succeeded
     * @param errorMessage Error message if failed (optional)
     */
    public void logOperation(AuditOperation operation, String repositoryId,
                             String userId, String objectId, boolean success, String errorMessage) {
        // Fast path: volatile reads
        if (!enabled) {
            return;
        }

        if (operation.isReadOnly() && !logReadOperations) {
            return;
        }

        // Check excluded users with lock
        configLock.readLock().lock();
        try {
            if (userId != null && excludedUsers.contains(userId)) {
                return;
            }
        } finally {
            configLock.readLock().unlock();
        }

        AuditEventBuilder builder = AuditEventBuilder.forOperation(operation)
                .repositoryId(repositoryId)
                .userId(userId)
                .objectId(objectId);

        RequestContext reqCtx = requestContext.get();
        if (reqCtx != null) {
            builder.clientIp(reqCtx.getClientIp());
        }

        if (success) {
            builder.success();
        } else {
            builder.failure(errorMessage);
        }

        logAuditEvent(builder.build());
    }

    /**
     * Extracts CallContext from method arguments.
     */
    private CallContext extractCallContext(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof CallContext) {
                return (CallContext) arg;
            }
        }
        return null;
    }

    /**
     * Extracts object information from method arguments.
     */
    private void extractObjectInfo(AuditEventBuilder builder, Object[] args, String[] paramNames) {
        if (args == null) {
            return;
        }

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            String paramName = (paramNames != null && i < paramNames.length) ? paramNames[i] : null;

            if (arg == null) {
                continue;
            }

            // Object ID detection
            if ("objectId".equals(paramName) && arg instanceof String) {
                builder.objectId((String) arg);
            } else if ("folderId".equals(paramName) && arg instanceof String) {
                builder.objectId((String) arg);
                builder.objectType("cmis:folder");
            } else if ("documentId".equals(paramName) && arg instanceof String) {
                builder.objectId((String) arg);
                builder.objectType("cmis:document");
            }

            // Version series ID detection (for versioning operations)
            if ("versionSeriesId".equals(paramName) && arg instanceof String) {
                builder.detail("versionSeriesId", arg);
            }

            // Parent folder detection
            if ("parentId".equals(paramName) && arg instanceof String) {
                builder.parentId((String) arg);
            }

            // Properties extraction (for create/update operations)
            if (arg instanceof Properties && detailLevel != DetailLevel.MINIMAL) {
                extractPropertiesInfo(builder, (Properties) arg);
            }

            // Content stream info
            if (arg instanceof ContentStream && detailLevel == DetailLevel.VERBOSE) {
                ContentStream cs = (ContentStream) arg;
                builder.contentStream(cs.getMimeType(), cs.getLength());
            }

            // Repository ID (backup)
            if ("repositoryId".equals(paramName) && arg instanceof String) {
                builder.repositoryId((String) arg);
            }
        }
    }

    /**
     * Extracts information from CMIS Properties.
     */
    private void extractPropertiesInfo(AuditEventBuilder builder, Properties properties) {
        if (properties == null || properties.getProperties() == null) {
            return;
        }

        // Extract common properties
        PropertyData<?> nameProp = properties.getProperties().get("cmis:name");
        if (nameProp != null && nameProp.getFirstValue() != null) {
            builder.objectName(nameProp.getFirstValue().toString());
        }

        PropertyData<?> typeProp = properties.getProperties().get("cmis:objectTypeId");
        if (typeProp != null && typeProp.getFirstValue() != null) {
            builder.objectType(typeProp.getFirstValue().toString());
        }

        // Extract version series ID (useful for versioning operations audit trail)
        PropertyData<?> versionSeriesProp = properties.getProperties().get("cmis:versionSeriesId");
        if (versionSeriesProp != null && versionSeriesProp.getFirstValue() != null) {
            builder.detail("versionSeriesId", versionSeriesProp.getFirstValue().toString());
        }

        // Extract version label if available
        PropertyData<?> versionLabelProp = properties.getProperties().get("cmis:versionLabel");
        if (versionLabelProp != null && versionLabelProp.getFirstValue() != null) {
            builder.detail("versionLabel", versionLabelProp.getFirstValue().toString());
        }

        // Add property count for verbose logging
        if (detailLevel == DetailLevel.VERBOSE) {
            builder.detail("propertyCount", properties.getProperties().size());
        }
    }

    /**
     * Adds result information to the audit event.
     * Extracts useful information from result objects for audit trail.
     */
    private void addResultInfo(AuditEventBuilder builder, Object result) {
        if (result instanceof String) {
            // Likely an object ID returned from create operations
            builder.detail("resultObjectId", result);
        } else if (result instanceof org.apache.chemistry.opencmis.commons.data.ObjectData) {
            // Extract info from ObjectData result (common return type)
            org.apache.chemistry.opencmis.commons.data.ObjectData objData =
                (org.apache.chemistry.opencmis.commons.data.ObjectData) result;

            if (objData.getId() != null) {
                builder.detail("resultObjectId", objData.getId());
            }

            // Extract version info from result properties
            if (objData.getProperties() != null && objData.getProperties().getProperties() != null) {
                PropertyData<?> versionSeriesProp = objData.getProperties().getProperties().get("cmis:versionSeriesId");
                if (versionSeriesProp != null && versionSeriesProp.getFirstValue() != null) {
                    builder.detail("versionSeriesId", versionSeriesProp.getFirstValue().toString());
                }

                PropertyData<?> versionLabelProp = objData.getProperties().getProperties().get("cmis:versionLabel");
                if (versionLabelProp != null && versionLabelProp.getFirstValue() != null) {
                    builder.detail("versionLabel", versionLabelProp.getFirstValue().toString());
                }

                // Also extract name if not already set
                PropertyData<?> nameProp = objData.getProperties().getProperties().get("cmis:name");
                if (nameProp != null && nameProp.getFirstValue() != null) {
                    builder.objectName(nameProp.getFirstValue().toString());
                }
            }
        } else if (result instanceof org.apache.chemistry.opencmis.commons.data.ObjectList) {
            // Query results - add count
            org.apache.chemistry.opencmis.commons.data.ObjectList list =
                (org.apache.chemistry.opencmis.commons.data.ObjectList) result;
            if (list.getNumItems() != null) {
                builder.detail("resultCount", list.getNumItems().intValue());
            } else if (list.getObjects() != null) {
                builder.detail("resultCount", list.getObjects().size());
            }
        }
    }

    // Request context management

    /**
     * Sets the request context for the current thread.
     * Should be called by filters at the start of request processing.
     */
    public static void setRequestContext(HttpServletRequest request) {
        if (request != null) {
            RequestContext ctx = new RequestContext();
            ctx.setClientIp(getClientIp(request));
            requestContext.set(ctx);
        }
    }

    /**
     * Clears the request context for the current thread.
     * Should be called by filters at the end of request processing.
     */
    public static void clearRequestContext() {
        requestContext.remove();
    }

    /**
     * Gets the client IP address from the request.
     */
    private static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // If multiple IPs (X-Forwarded-For chain), take the first one
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    // Configuration management

    public void setEnabled(boolean enabled) {
        configLock.writeLock().lock();
        try {
            this.enabled = enabled;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public boolean isEnabled() {
        // Volatile read - no lock needed for simple boolean check
        return enabled;
    }

    public void setDetailLevel(DetailLevel level) {
        configLock.writeLock().lock();
        try {
            this.detailLevel = level;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public void setLogReadOperations(boolean logReads) {
        configLock.writeLock().lock();
        try {
            this.logReadOperations = logReads;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public PropertyManager getPropertyManager() {
        return propertyManager;
    }

    /**
     * Internal class to hold request context information.
     */
    private static class RequestContext {
        private String clientIp;

        public String getClientIp() {
            return clientIp;
        }

        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }
    }
}
