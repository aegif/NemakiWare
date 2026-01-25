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
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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

    // Server metadata - initialized once at startup
    private static final String HOSTNAME = initHostname();
    private static final String SERVICE = "nemakiware";
    private static final String ENVIRONMENT = initEnvironment();
    private static final String VERSION = initVersion();

    private final ObjectMapper objectMapper;
    private final ReadWriteLock configLock = new ReentrantReadWriteLock(true);

    // Configuration - use volatile for fast reads without lock
    // Default to false for safety (requires explicit enablement in production)
    private volatile boolean enabled = false;
    private volatile boolean logReadOperations = false;  // Deprecated: use readAuditLevel
    private volatile ReadAuditLevel readAuditLevel = ReadAuditLevel.NONE;  // Default: no READ logging
    private volatile DetailLevel detailLevel = DetailLevel.STANDARD;
    private volatile boolean logFailuresAsWarn = true;  // Log failures at WARN level

    // These require lock for iteration, but are read less frequently
    private Set<String> excludedUsers = new HashSet<>();
    private Set<String> excludedOperations = new HashSet<>();

    // ThreadLocal for storing request context (IP address, traceId, HTTP info, etc.)
    private static final ThreadLocal<RequestContext> requestContext = new ThreadLocal<>();

    private PropertyManager propertyManager;

    // Server metadata initialization methods

    private static String initHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getProperty("nemakiware.hostname", "unknown");
        }
    }

    private static String initEnvironment() {
        String env = System.getProperty("nemakiware.environment");
        if (env == null || env.isEmpty()) {
            env = System.getenv("NEMAKIWARE_ENV");
        }
        return env;
    }

    private static String initVersion() {
        return System.getProperty("nemakiware.version", "3.0.0");
    }

    public enum DetailLevel {
        MINIMAL,    // Only basic info (user, operation, object ID, result)
        STANDARD,   // Basic + object name, type, duration
        VERBOSE     // All details including properties and content info
    }

    /**
     * Controls which READ operations are logged.
     * WRITE/DELETE/ACL operations are ALWAYS logged regardless of this setting.
     */
    public enum ReadAuditLevel {
        NONE,       // No READ operations logged (default - low overhead)
        DOWNLOAD,   // Content downloads only (getContentStream, getRenditions) - highest risk
        METADATA,   // + Object/property reads (getObject, getObjectByPath, getAcl)
        ALL         // + Navigation (getChildren, getFolderTree, query) - high volume
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
                    ", ReadAuditLevel: " + readAuditLevel);
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

                // Read audit level (NONE, DOWNLOAD, METADATA, ALL)
                String readLevelStr = propertyManager.readValue(PropertyKey.AUDIT_READ_LEVEL);
                if (readLevelStr != null && !readLevelStr.trim().isEmpty()) {
                    try {
                        this.readAuditLevel = ReadAuditLevel.valueOf(readLevelStr.trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid audit read level: " + readLevelStr + ", using NONE");
                        this.readAuditLevel = ReadAuditLevel.NONE;
                    }
                }

                // Deprecated: Log read operations (for backward compatibility)
                // If audit.log.read.operations=true and audit.read.level not set, use ALL
                String logReadsStr = propertyManager.readValue(PropertyKey.AUDIT_LOG_READ_OPERATIONS);
                this.logReadOperations = logReadsStr != null &&
                                         "true".equalsIgnoreCase(logReadsStr.trim());
                if (this.logReadOperations && readLevelStr == null) {
                    this.readAuditLevel = ReadAuditLevel.ALL;
                    log.info("Deprecated audit.log.read.operations=true, migrated to audit.read.level=ALL");
                }

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

        // WRITE/DELETE/ACL operations are ALWAYS logged
        // READ operations are filtered by readAuditLevel
        if (operation.isReadOnly()) {
            // Volatile read for fast path
            ReadAuditLevel level = readAuditLevel;
            switch (level) {
                case NONE:
                    return jp.proceed();  // Skip all read operations
                case DOWNLOAD:
                    // Only log content downloads (highest risk)
                    if (!operation.isDownloadOperation()) {
                        return jp.proceed();
                    }
                    break;
                case METADATA:
                    // Log downloads and metadata reads
                    if (!operation.isDownloadOperation() && !operation.isMetadataOperation()) {
                        return jp.proceed();
                    }
                    break;
                case ALL:
                    // Log all read operations (including navigation)
                    break;
            }
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

        // Build audit event
        AuditEventBuilder builder = AuditEventBuilder.fromCallContext(callContext)
                .operation(operation);

        // Add server metadata (static fields, always available)
        builder.hostname(HOSTNAME)
               .service(SERVICE)
               .version(VERSION);

        // Add environment if configured
        if (ENVIRONMENT != null && !ENVIRONMENT.isEmpty()) {
            builder.environment(ENVIRONMENT);
        }

        // Add HTTP request context if available (from ThreadLocal)
        RequestContext reqCtx = requestContext.get();
        if (reqCtx != null) {
            builder.clientIp(reqCtx.getClientIp())
                   .traceId(reqCtx.getTraceId())
                   .httpMethod(reqCtx.getHttpMethod())
                   .requestPath(reqCtx.getRequestPath())
                   .userAgent(reqCtx.getUserAgent());
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
     * This uses parameters passed to CMIS operations - no additional DB queries needed.
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
            // Set as top-level field (not just detail)
            if ("versionSeriesId".equals(paramName) && arg instanceof String) {
                builder.versionSeriesId((String) arg);
            }

            // Parent folder detection (for create operations)
            if ("parentId".equals(paramName) && arg instanceof String) {
                builder.parentId((String) arg);
            }

            // Move/Copy operation: source folder
            if ("sourceFolderId".equals(paramName) && arg instanceof String) {
                builder.detail("sourceFolderId", arg);
            }

            // Move/Copy operation: target folder
            if ("targetFolderId".equals(paramName) && arg instanceof String) {
                builder.detail("targetFolderId", arg);
            }

            // Path parameter (for getObjectByPath operations)
            if ("path".equals(paramName) && arg instanceof String) {
                builder.objectPath((String) arg);
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
     * This leverages data already loaded during CMIS operation processing,
     * avoiding additional database queries.
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

        // Extract path (available in folder objects and documents with path)
        PropertyData<?> pathProp = properties.getProperties().get("cmis:path");
        if (pathProp != null && pathProp.getFirstValue() != null) {
            builder.objectPath(pathProp.getFirstValue().toString());
        }

        // Extract parent ID (useful for tracking document location)
        PropertyData<?> parentIdProp = properties.getProperties().get("cmis:parentId");
        if (parentIdProp != null && parentIdProp.getFirstValue() != null) {
            builder.parentId(parentIdProp.getFirstValue().toString());
        }

        // Extract version series ID (key identifier for document tracking across versions)
        PropertyData<?> versionSeriesProp = properties.getProperties().get("cmis:versionSeriesId");
        if (versionSeriesProp != null && versionSeriesProp.getFirstValue() != null) {
            builder.versionSeriesId(versionSeriesProp.getFirstValue().toString());
        }

        // Extract version label if available (for identifying specific version)
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
     * This uses data already returned from CMIS operations - no additional DB queries.
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

            // Extract info from result properties
            if (objData.getProperties() != null && objData.getProperties().getProperties() != null) {
                java.util.Map<String, PropertyData<?>> props = objData.getProperties().getProperties();

                // Version series ID (top-level field - key for document tracking)
                PropertyData<?> versionSeriesProp = props.get("cmis:versionSeriesId");
                if (versionSeriesProp != null && versionSeriesProp.getFirstValue() != null) {
                    builder.versionSeriesId(versionSeriesProp.getFirstValue().toString());
                }

                // Version label (detail)
                PropertyData<?> versionLabelProp = props.get("cmis:versionLabel");
                if (versionLabelProp != null && versionLabelProp.getFirstValue() != null) {
                    builder.detail("versionLabel", versionLabelProp.getFirstValue().toString());
                }

                // Object name if not already set
                PropertyData<?> nameProp = props.get("cmis:name");
                if (nameProp != null && nameProp.getFirstValue() != null) {
                    builder.objectName(nameProp.getFirstValue().toString());
                }

                // Object path (available in results for fileable objects)
                PropertyData<?> pathProp = props.get("cmis:path");
                if (pathProp != null && pathProp.getFirstValue() != null) {
                    builder.objectPath(pathProp.getFirstValue().toString());
                }

                // Parent ID
                PropertyData<?> parentIdProp = props.get("cmis:parentId");
                if (parentIdProp != null && parentIdProp.getFirstValue() != null) {
                    builder.parentId(parentIdProp.getFirstValue().toString());
                }

                // Object type if not already set
                PropertyData<?> typeProp = props.get("cmis:objectTypeId");
                if (typeProp != null && typeProp.getFirstValue() != null) {
                    builder.objectType(typeProp.getFirstValue().toString());
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
     * Captures HTTP request details and generates/extracts trace ID.
     */
    public static void setRequestContext(HttpServletRequest request) {
        if (request != null) {
            RequestContext ctx = new RequestContext();

            // Client IP (handles proxies)
            ctx.setClientIp(getClientIp(request));

            // Trace ID - check common headers or generate new
            String traceId = request.getHeader("X-Request-ID");
            if (traceId == null || traceId.isEmpty()) {
                traceId = request.getHeader("X-Trace-ID");
            }
            if (traceId == null || traceId.isEmpty()) {
                traceId = request.getHeader("X-Correlation-ID");
            }
            if (traceId == null || traceId.isEmpty()) {
                // Generate a new trace ID
                traceId = java.util.UUID.randomUUID().toString();
            }
            ctx.setTraceId(traceId);

            // HTTP method
            ctx.setHttpMethod(request.getMethod());

            // Request path (URI + query string if present)
            String requestPath = request.getRequestURI();
            String queryString = request.getQueryString();
            if (queryString != null && !queryString.isEmpty()) {
                // Mask sensitive query parameters
                requestPath = requestPath + "?" + maskSensitiveQueryParams(queryString);
            }
            ctx.setRequestPath(requestPath);

            // User-Agent (truncated for safety)
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null && userAgent.length() > 200) {
                userAgent = userAgent.substring(0, 200) + "...";
            }
            ctx.setUserAgent(userAgent);

            requestContext.set(ctx);
        }
    }

    /**
     * Masks sensitive query parameters in a query string.
     * @param queryString The original query string
     * @return The query string with sensitive values masked
     */
    private static String maskSensitiveQueryParams(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return queryString;
        }
        // Mask common sensitive parameter names
        return queryString.replaceAll(
            "(?i)(password|passwd|pwd|token|apikey|api_key|secret|credential|auth)=([^&]*)",
            "$1=***");
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
     * Stores HTTP request details for audit logging.
     */
    private static class RequestContext {
        private String clientIp;
        private String traceId;
        private String httpMethod;
        private String requestPath;
        private String userAgent;

        public String getClientIp() {
            return clientIp;
        }

        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public void setHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
        }

        public String getRequestPath() {
            return requestPath;
        }

        public void setRequestPath(String requestPath) {
            this.requestPath = requestPath;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }
}
