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
 *     Devin - Rendition feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;

/**
 * Spring @RestController for Rendition Management API
 * Provides endpoints for rendition generation and retrieval
 */
@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/renditions")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RenditionController {

    private static final Logger log = LoggerFactory.getLogger(RenditionController.class);

    private ContentService contentService;
    private RenditionManager renditionManager;
    private PrincipalService principalService;
    private PermissionService permissionService;

    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }

    private RenditionManager getRenditionManager() {
        if (renditionManager != null) {
            return renditionManager;
        }
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("renditionManager", RenditionManager.class);
    }

    private PrincipalService getPrincipalService() {
        if (principalService != null) {
            return principalService;
        }
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("PrincipalService", PrincipalService.class);
    }

    private PermissionService getPermissionService() {
        if (permissionService != null) {
            return permissionService;
        }
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("PermissionService", PermissionService.class);
    }

    /**
     * Check if user has read permission on the content
     * Refactored to accept Content directly to avoid double lookups
     */
    private boolean hasReadPermission(CallContext callContext, String repositoryId, Content content) {
        try {
            if (content == null) {
                return false;
            }
            // Use objectType if available, fallback to type for better CMIS compliance
            String baseObjectType = content.getObjectType() != null ? content.getObjectType() : content.getType();
            Boolean hasPermission = getPermissionService().checkPermission(
                    callContext, repositoryId, "cmis:read", content.getAcl(), baseObjectType, content);
            return hasPermission != null && hasPermission;
        } catch (Exception e) {
            log.warn("Error checking read permission for object: " + (content != null ? content.getId() : "unknown"), e);
            return false;
        }
    }

    /**
     * Check if current user is repository admin
     */
    private boolean isAdmin(CallContext callContext, String repositoryId) {
        if (callContext == null) {
            return false;
        }
        String userId = callContext.getUsername();
        List<String> admins = getPrincipalService().getAdmins(repositoryId);
        return admins != null && admins.contains(userId);
    }

    /**
     * Get renditions for a document
     * Requires authentication and read permission on the document
     */
    @GetMapping("/{objectId}")
    public ResponseEntity<Map<String, Object>> getRenditions(
            @PathVariable String repositoryId,
            @PathVariable String objectId,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // SECURITY: Require authenticated CallContext
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            // Fetch content once to avoid double lookups
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // SECURITY: Check read permission on the document
            if (!hasReadPermission(callContext, repositoryId, content)) {
                response.put("status", "error");
                response.put("message", "Access denied: insufficient permissions");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            // Only documents support renditions in current implementation
            if (!(content instanceof Document)) {
                response.put("status", "error");
                response.put("message", "Renditions are only supported for documents");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            List<Rendition> renditions = getContentService().getRenditions(repositoryId, objectId);
            List<Map<String, Object>> renditionList = new ArrayList<>();

            if (renditions != null) {
                for (Rendition rendition : renditions) {
                    renditionList.add(convertRenditionToMap(rendition));
                }
            }

            response.put("status", "success");
            response.put("renditions", renditionList);
            response.put("count", renditionList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to retrieve renditions for object: " + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to retrieve renditions");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Generate rendition for a single document
     * Requires authentication and read permission on the document
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateRendition(
            @PathVariable String repositoryId,
            @RequestParam String objectId,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Check if rendition generation is enabled
            if (!getRenditionManager().isRenditionEnabled()) {
                response.put("status", "error");
                response.put("message", "Rendition generation is disabled");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            // SECURITY: Require authenticated CallContext (no SystemCallContext fallback)
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            // Fetch content once to avoid double lookups
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null || !(content instanceof Document)) {
                response.put("status", "error");
                response.put("message", "Document not found or not a document");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // SECURITY: Check read permission on the document
            if (!hasReadPermission(callContext, repositoryId, content)) {
                response.put("status", "error");
                response.put("message", "Access denied: insufficient permissions");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            // Only admins can force regeneration to avoid abuse
            if (force && !isAdmin(callContext, repositoryId)) {
                response.put("status", "error");
                response.put("message", "Admin privileges required for force regeneration");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            String renditionId = getContentService().generateRendition(callContext, repositoryId, objectId, force);

            if (renditionId != null) {
                response.put("status", "success");
                response.put("message", "Rendition generated successfully");
                response.put("renditionId", renditionId);
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "success");
                response.put("message", "No rendition generated (document may not be convertible or already has rendition)");
                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            log.error("Failed to generate rendition for object: " + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to generate rendition");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Batch generate renditions for multiple documents
     * Admin only operation
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> generateRenditionsBatch(
            @PathVariable String repositoryId,
            @RequestBody BatchRequest batchRequest,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Check if rendition generation is enabled
            if (!getRenditionManager().isRenditionEnabled()) {
                response.put("status", "error");
                response.put("message", "Rendition generation is disabled");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            // Get CallContext from request (set by AuthenticationFilter)
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required for batch operations");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            // Check if user is admin for batch operations
            if (!isAdmin(callContext, repositoryId)) {
                response.put("status", "error");
                response.put("message", "Admin privileges required for batch operations");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            List<String> objectIds = batchRequest.getObjectIds();
            boolean force = batchRequest.isForce();
            int requestedMaxItems = batchRequest.getMaxItems() > 0 ? batchRequest.getMaxItems() : 100;

            // Hard cap on maxItems to prevent overloading the converter
            final int MAX_BATCH_ITEMS = 500;
            if (requestedMaxItems > MAX_BATCH_ITEMS) {
                response.put("status", "error");
                response.put("message", "maxItems cannot exceed " + MAX_BATCH_ITEMS);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            int maxItems = requestedMaxItems;

            if (objectIds == null || objectIds.isEmpty()) {
                response.put("status", "error");
                response.put("message", "objectIds list is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Filter objects by permission and document type before batch processing
            List<String> permittedObjectIds = new ArrayList<>();
            List<Map<String, String>> skippedObjects = new ArrayList<>();

            for (String oid : objectIds) {
                Content content = getContentService().getContent(repositoryId, oid);
                if (content == null) {
                    Map<String, String> skip = new HashMap<>();
                    skip.put("objectId", oid);
                    skip.put("reason", "Object not found");
                    skippedObjects.add(skip);
                    continue;
                }
                if (!(content instanceof Document)) {
                    Map<String, String> skip = new HashMap<>();
                    skip.put("objectId", oid);
                    skip.put("reason", "Not a document");
                    skippedObjects.add(skip);
                    continue;
                }
                if (!hasReadPermission(callContext, repositoryId, content)) {
                    Map<String, String> skip = new HashMap<>();
                    skip.put("objectId", oid);
                    skip.put("reason", "Insufficient permissions");
                    skippedObjects.add(skip);
                    continue;
                }
                permittedObjectIds.add(oid);
            }

            // Apply maxItems limit to permitted objects
            if (permittedObjectIds.size() > maxItems) {
                permittedObjectIds = permittedObjectIds.subList(0, maxItems);
            }

            List<String> generatedIds = new ArrayList<>();
            if (!permittedObjectIds.isEmpty()) {
                generatedIds = getContentService().generateRenditionsBatch(
                        callContext, repositoryId, permittedObjectIds, force, permittedObjectIds.size());
            }

            response.put("status", "success");
            response.put("message", "Batch rendition generation completed");
            response.put("generatedCount", generatedIds.size());
            response.put("requestedCount", objectIds.size());
            response.put("permittedCount", permittedObjectIds.size());
            response.put("generatedIds", generatedIds);
            if (!skippedObjects.isEmpty()) {
                response.put("skipped", skippedObjects);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to generate batch renditions", e);
            response.put("status", "error");
            response.put("message", "Failed to generate batch renditions");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get supported source mimetypes for rendition generation
     * Requires authentication
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedTypes(
            @PathVariable String repositoryId,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // SECURITY: Require authenticated CallContext
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            List<String> supportedTypes = getRenditionManager().getSupportedSourceMimeTypes();

            response.put("status", "success");
            response.put("supportedTypes", supportedTypes);
            response.put("enabled", getRenditionManager().isRenditionEnabled());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to retrieve supported types", e);
            response.put("status", "error");
            response.put("message", "Failed to retrieve supported types");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Convert Rendition to Map for JSON response
     */
    private Map<String, Object> convertRenditionToMap(Rendition rendition) {
        Map<String, Object> map = new HashMap<>();

        if (rendition != null) {
            map.put("id", rendition.getId());
            map.put("title", rendition.getTitle());
            map.put("kind", rendition.getKind());
            map.put("mimetype", rendition.getMimetype());
            map.put("length", rendition.getLength());
        }

        return map;
    }

    /**
     * Request body for batch rendition generation
     */
    public static class BatchRequest {
        private List<String> objectIds;
        private boolean force;
        private int maxItems;

        public List<String> getObjectIds() {
            return objectIds;
        }

        public void setObjectIds(List<String> objectIds) {
            this.objectIds = objectIds;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }

        public int getMaxItems() {
            return maxItems;
        }

        public void setMaxItems(int maxItems) {
            this.maxItems = maxItems;
        }
    }
}
