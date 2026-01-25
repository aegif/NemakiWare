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

    // Configuration
    private boolean enabled = true;
    private boolean logReadOperations = false;
    private Set<String> excludedUsers = new HashSet<>();
    private Set<String> excludedOperations = new HashSet<>();
    private DetailLevel detailLevel = DetailLevel.STANDARD;

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
                // Enable/disable audit logging
                String enabledStr = propertyManager.readValue(PropertyKey.AUDIT_ENABLED);
                this.enabled = enabledStr == null || Boolean.parseBoolean(enabledStr);

                // Log read operations
                String logReadsStr = propertyManager.readValue(PropertyKey.AUDIT_LOG_READ_OPERATIONS);
                this.logReadOperations = logReadsStr != null && Boolean.parseBoolean(logReadsStr);

                // Detail level
                String levelStr = propertyManager.readValue(PropertyKey.AUDIT_DETAIL_LEVEL);
                if (levelStr != null) {
                    try {
                        this.detailLevel = DetailLevel.valueOf(levelStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid audit detail level: " + levelStr + ", using STANDARD");
                        this.detailLevel = DetailLevel.STANDARD;
                    }
                }

                // Excluded users
                String excludedUsersStr = propertyManager.readValue(PropertyKey.AUDIT_EXCLUDE_USERS);
                if (excludedUsersStr != null && !excludedUsersStr.isEmpty()) {
                    this.excludedUsers = new HashSet<>(Arrays.asList(excludedUsersStr.split(",")));
                }

                // Excluded operations
                String excludedOpsStr = propertyManager.readValue(PropertyKey.AUDIT_EXCLUDE_OPERATIONS);
                if (excludedOpsStr != null && !excludedOpsStr.isEmpty()) {
                    this.excludedOperations = new HashSet<>(Arrays.asList(excludedOpsStr.split(",")));
                }
            }
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * Spring AOP Around advice for auditing CMIS service methods.
     * @param jp The join point
     * @return The method result
     * @throws Throwable If the method throws an exception
     */
    public Object aroundMethod(ProceedingJoinPoint jp) throws Throwable {
        configLock.readLock().lock();
        try {
            if (!enabled) {
                return jp.proceed();
            }
            return aroundMethodBody(jp);
        } finally {
            configLock.readLock().unlock();
        }
    }

    private Object aroundMethodBody(ProceedingJoinPoint jp) throws Throwable {
        long startTime = System.currentTimeMillis();

        // Extract method information
        MethodSignature signature = (MethodSignature) jp.getSignature();
        String methodName = signature.getMethod().getName();
        Object[] args = jp.getArgs();

        // Determine operation
        AuditOperation operation = AuditOperation.fromMethodName(methodName);

        // Skip read operations if not configured to log them
        if (operation.isReadOnly() && !logReadOperations) {
            return jp.proceed();
        }

        // Skip excluded operations
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
     * @param event The audit event to log
     */
    public void logAuditEvent(AuditEvent event) {
        if (event == null) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            auditLogger.info(json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event: " + e.getMessage(), e);
            // Fallback to toString
            auditLogger.info(event.toString());
        }
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
        if (!enabled) {
            return;
        }

        if (operation.isReadOnly() && !logReadOperations) {
            return;
        }

        if (excludedUsers.contains(userId)) {
            return;
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

        // Add property count for verbose logging
        if (detailLevel == DetailLevel.VERBOSE) {
            builder.detail("propertyCount", properties.getProperties().size());
        }
    }

    /**
     * Adds result information to the audit event.
     */
    private void addResultInfo(AuditEventBuilder builder, Object result) {
        if (result instanceof String) {
            // Likely an object ID returned from create operations
            builder.detail("resultObjectId", result);
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
        configLock.readLock().lock();
        try {
            return enabled;
        } finally {
            configLock.readLock().unlock();
        }
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
