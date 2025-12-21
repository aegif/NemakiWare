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
package jp.aegif.nemaki.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Structured audit logger for property coercion events.
 * 
 * This logger outputs machine-readable JSON events for data loss/coercion situations
 * that occur when property definitions change after documents have been created.
 * 
 * Events are logged at WARN level and can be parsed by log aggregators (ELK, Splunk, etc.)
 * to detect and monitor data integrity issues in production.
 * 
 * Event types:
 * - CARDINALITY_MISMATCH: When stored multi-value has >1 elements but definition expects single
 * - TYPE_COERCION_REJECTED: When type conversion is not possible (e.g., "abc" -> Integer)
 * - TYPE_COERCION_APPLIED: When type conversion was applied (informational)
 * - LIST_ELEMENT_DROPPED: When an element in a multi-value list could not be coerced
 * 
 * JSON format:
 * {"event":"COERCION_AUDIT","type":"CARDINALITY_MISMATCH","repositoryId":"bedroom",
 *  "objectId":"abc123","typeId":"nemaki:customDoc","propertyId":"nemaki:prop1",
 *  "originalType":"List","originalValue":"[a,b,c]","expectedType":"String",
 *  "result":"null","elementCount":3,"timestamp":1703145600000}
 */
public class CoercionAuditLogger {
    
    private static final Log auditLog = LogFactory.getLog("jp.aegif.nemaki.audit.coercion");
    
    // Event types
    public static final String EVENT_CARDINALITY_MISMATCH = "CARDINALITY_MISMATCH";
    public static final String EVENT_TYPE_COERCION_REJECTED = "TYPE_COERCION_REJECTED";
    public static final String EVENT_TYPE_COERCION_APPLIED = "TYPE_COERCION_APPLIED";
    public static final String EVENT_LIST_ELEMENT_DROPPED = "LIST_ELEMENT_DROPPED";
    
    // Thread-local context for object information (set by compileProperties, used by coercion methods)
    private static final ThreadLocal<CoercionContext> currentContext = new ThreadLocal<>();
    
    // Thread-local list of coercion warnings for CMIS Extension response
    private static final ThreadLocal<List<CoercionWarning>> currentWarnings = new ThreadLocal<>();
    
    /**
     * Context information for the current object being compiled.
     * Set this before calling addProperty to include object context in audit logs.
     */
    public static class CoercionContext {
        public final String repositoryId;
        public final String objectId;
        public final String typeId;
        
        public CoercionContext(String repositoryId, String objectId, String typeId) {
            this.repositoryId = repositoryId;
            this.objectId = objectId;
            this.typeId = typeId;
        }
    }
    
    /**
     * Represents a coercion warning that can be included in CMIS Extension response.
     * Does NOT include original values to avoid PII/size issues.
     */
    public static class CoercionWarning {
        public final String type;
        public final String propertyId;
        public final String reason;
        public final int elementCount; // for CARDINALITY_MISMATCH
        public final int elementIndex; // for LIST_ELEMENT_DROPPED
        
        public CoercionWarning(String type, String propertyId, String reason, int elementCount, int elementIndex) {
            this.type = type;
            this.propertyId = propertyId;
            this.reason = reason;
            this.elementCount = elementCount;
            this.elementIndex = elementIndex;
        }
        
        public static CoercionWarning cardinalityMismatch(String propertyId, int elementCount) {
            return new CoercionWarning(EVENT_CARDINALITY_MISMATCH, propertyId, 
                    "multi->single with " + elementCount + " elements", elementCount, -1);
        }
        
        public static CoercionWarning typeCoercionRejected(String propertyId, String originalType, 
                String expectedType, String reason) {
            return new CoercionWarning(EVENT_TYPE_COERCION_REJECTED, propertyId, 
                    originalType + "->" + expectedType + ": " + reason, -1, -1);
        }
        
        public static CoercionWarning listElementDropped(String propertyId, String originalType, 
                String expectedType, int elementIndex) {
            return new CoercionWarning(EVENT_LIST_ELEMENT_DROPPED, propertyId, 
                    originalType + "->" + expectedType + " at index " + elementIndex, -1, elementIndex);
        }
    }
    
    /**
     * Set the current coercion context for the current thread.
     * Call this at the start of compileProperties.
     * Also initializes the warnings list for collecting coercion events.
     */
    public static void setContext(String repositoryId, String objectId, String typeId) {
        currentContext.set(new CoercionContext(repositoryId, objectId, typeId));
        currentWarnings.set(new ArrayList<>());
    }
    
    /**
     * Clear the current coercion context and warnings list.
     * Call this at the end of compileProperties (in a finally block).
     */
    public static void clearContext() {
        currentContext.remove();
        currentWarnings.remove();
    }
    
    /**
     * Get the current coercion context.
     */
    public static CoercionContext getContext() {
        return currentContext.get();
    }
    
    /**
     * Get the collected coercion warnings for the current thread.
     * Returns an unmodifiable list. Call this before clearContext() to retrieve warnings.
     */
    public static List<CoercionWarning> getWarnings() {
        List<CoercionWarning> warnings = currentWarnings.get();
        if (warnings == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(warnings));
    }
    
    /**
     * Check if there are any coercion warnings for the current thread.
     */
    public static boolean hasWarnings() {
        List<CoercionWarning> warnings = currentWarnings.get();
        return warnings != null && !warnings.isEmpty();
    }
    
    /**
     * Add a warning to the current thread's warning list (for CMIS Extension response).
     */
    private static void addWarning(CoercionWarning warning) {
        List<CoercionWarning> warnings = currentWarnings.get();
        if (warnings != null) {
            warnings.add(warning);
        }
    }
    
    /**
     * Log a cardinality mismatch event (multi->single with >1 elements).
     * Also adds a warning to the collection for CMIS Extension response.
     */
    public static void logCardinalityMismatch(String propertyId, int elementCount, Object originalValue) {
        // Add to warnings collection for CMIS Extension response
        addWarning(CoercionWarning.cardinalityMismatch(propertyId, elementCount));
        
        // Log to audit log
        CoercionContext ctx = currentContext.get();
        StringBuilder json = new StringBuilder();
        json.append("{\"event\":\"COERCION_AUDIT\"");
        json.append(",\"type\":\"").append(EVENT_CARDINALITY_MISMATCH).append("\"");
        appendContext(json, ctx);
        json.append(",\"propertyId\":\"").append(escapeJson(propertyId)).append("\"");
        json.append(",\"elementCount\":").append(elementCount);
        json.append(",\"originalValue\":\"").append(escapeJson(truncateValue(originalValue))).append("\"");
        json.append(",\"result\":\"null\"");
        json.append(",\"timestamp\":").append(System.currentTimeMillis());
        json.append("}");
        
        auditLog.warn(json.toString());
    }
    
    /**
     * Log a type coercion rejection event (conversion not possible).
     * Also adds a warning to the collection for CMIS Extension response.
     */
    public static void logTypeCoercionRejected(String propertyId, String originalType, 
            Object originalValue, String expectedType, String reason) {
        // Add to warnings collection for CMIS Extension response
        addWarning(CoercionWarning.typeCoercionRejected(propertyId, originalType, expectedType, reason));
        
        // Log to audit log
        CoercionContext ctx = currentContext.get();
        StringBuilder json = new StringBuilder();
        json.append("{\"event\":\"COERCION_AUDIT\"");
        json.append(",\"type\":\"").append(EVENT_TYPE_COERCION_REJECTED).append("\"");
        appendContext(json, ctx);
        json.append(",\"propertyId\":\"").append(escapeJson(propertyId)).append("\"");
        json.append(",\"originalType\":\"").append(escapeJson(originalType)).append("\"");
        json.append(",\"originalValue\":\"").append(escapeJson(truncateValue(originalValue))).append("\"");
        json.append(",\"expectedType\":\"").append(escapeJson(expectedType)).append("\"");
        json.append(",\"reason\":\"").append(escapeJson(reason)).append("\"");
        json.append(",\"result\":\"null\"");
        json.append(",\"timestamp\":").append(System.currentTimeMillis());
        json.append("}");
        
        auditLog.warn(json.toString());
    }
    
    /**
     * Log a successful type coercion event (informational, logged at DEBUG level).
     */
    public static void logTypeCoercionApplied(String propertyId, String originalType, 
            Object originalValue, String expectedType) {
        if (!auditLog.isDebugEnabled()) {
            return;
        }
        
        CoercionContext ctx = currentContext.get();
        StringBuilder json = new StringBuilder();
        json.append("{\"event\":\"COERCION_AUDIT\"");
        json.append(",\"type\":\"").append(EVENT_TYPE_COERCION_APPLIED).append("\"");
        appendContext(json, ctx);
        json.append(",\"propertyId\":\"").append(escapeJson(propertyId)).append("\"");
        json.append(",\"originalType\":\"").append(escapeJson(originalType)).append("\"");
        json.append(",\"originalValue\":\"").append(escapeJson(truncateValue(originalValue))).append("\"");
        json.append(",\"expectedType\":\"").append(escapeJson(expectedType)).append("\"");
        json.append(",\"result\":\"coerced\"");
        json.append(",\"timestamp\":").append(System.currentTimeMillis());
        json.append("}");
        
        auditLog.debug(json.toString());
    }
    
    /**
     * Log a list element dropped event (element in multi-value list could not be coerced).
     * Also adds a warning to the collection for CMIS Extension response.
     */
    public static void logListElementDropped(String propertyId, String originalType, 
            Object originalValue, String expectedType, int elementIndex) {
        // Add to warnings collection for CMIS Extension response
        addWarning(CoercionWarning.listElementDropped(propertyId, originalType, expectedType, elementIndex));
        
        // Log to audit log
        CoercionContext ctx = currentContext.get();
        StringBuilder json = new StringBuilder();
        json.append("{\"event\":\"COERCION_AUDIT\"");
        json.append(",\"type\":\"").append(EVENT_LIST_ELEMENT_DROPPED).append("\"");
        appendContext(json, ctx);
        json.append(",\"propertyId\":\"").append(escapeJson(propertyId)).append("\"");
        json.append(",\"originalType\":\"").append(escapeJson(originalType)).append("\"");
        json.append(",\"originalValue\":\"").append(escapeJson(truncateValue(originalValue))).append("\"");
        json.append(",\"expectedType\":\"").append(escapeJson(expectedType)).append("\"");
        json.append(",\"elementIndex\":").append(elementIndex);
        json.append(",\"result\":\"dropped\"");
        json.append(",\"timestamp\":").append(System.currentTimeMillis());
        json.append("}");
        
        auditLog.warn(json.toString());
    }
    
    private static void appendContext(StringBuilder json, CoercionContext ctx) {
        if (ctx != null) {
            json.append(",\"repositoryId\":\"").append(escapeJson(ctx.repositoryId)).append("\"");
            json.append(",\"objectId\":\"").append(escapeJson(ctx.objectId)).append("\"");
            json.append(",\"typeId\":\"").append(escapeJson(ctx.typeId)).append("\"");
        } else {
            json.append(",\"repositoryId\":\"unknown\"");
            json.append(",\"objectId\":\"unknown\"");
            json.append(",\"typeId\":\"unknown\"");
        }
    }
    
    private static String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
    
    private static String truncateValue(Object value) {
        if (value == null) {
            return "null";
        }
        String str = value.toString();
        if (str.length() > 200) {
            return str.substring(0, 200) + "...(truncated)";
        }
        return str;
    }
}
