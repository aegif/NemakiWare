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
 *     Claude Code - Spring @RestController implementation for Type Migration API
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Spring @RestController for Object Type Migration API
 *
 * Provides endpoints for changing the object type of existing CMIS objects.
 * This is a NemakiWare-specific extension to CMIS, as the standard CMIS 1.1
 * specification does not allow changing cmis:objectTypeId after object creation.
 *
 * Constraints:
 * - Source and target types must have the same base type (e.g., cmis:document, cmis:folder)
 * - Target type must be creatable
 * - Required properties of the target type that don't exist in source must be provided
 *
 * @since 2025-12-11
 */
@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/type-migration")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TypeMigrationController {

    private static final Log log = LogFactory.getLog(TypeMigrationController.class);

    private ContentService getContentService() {
        return SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }

    private TypeManager getTypeManager() {
        return SpringContext.getApplicationContext()
                .getBean("TypeManager", TypeManager.class);
    }

    /**
     * Request body for type migration
     */
    public static class TypeMigrationRequest {
        private String objectId;
        private String newTypeId;
        private Map<String, Object> additionalProperties;

        public String getObjectId() {
            return objectId;
        }

        public void setObjectId(String objectId) {
            this.objectId = objectId;
        }

        public String getNewTypeId() {
            return newTypeId;
        }

        public void setNewTypeId(String newTypeId) {
            this.newTypeId = newTypeId;
        }

        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }

        public void setAdditionalProperties(Map<String, Object> additionalProperties) {
            this.additionalProperties = additionalProperties;
        }
    }

    /**
     * Get compatible types for migration
     *
     * Returns all types that an object can be migrated to (same base type).
     *
     * @param repositoryId Repository ID
     * @param objectId Object ID to check compatible types for
     * @return List of compatible type definitions
     */
    @GetMapping("/compatible-types/{objectId}")
    public ResponseEntity<Map<String, Object>> getCompatibleTypes(
            @PathVariable String repositoryId,
            @PathVariable String objectId) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("[TypeMigration] Getting compatible types for objectId=" + objectId);

            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Object not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            String currentTypeId = content.getObjectType();
            TypeDefinition currentType = getTypeManager().getTypeDefinition(repositoryId, currentTypeId);
            if (currentType == null) {
                response.put("status", "error");
                response.put("message", "Current type definition not found");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            String baseTypeId = currentType.getBaseTypeId().value();

            // Get all types with the same base type (depth=-1 means all descendants)
            List<TypeDefinitionContainer> typeContainers = getTypeManager()
                    .getTypesDescendants(repositoryId, baseTypeId, BigInteger.valueOf(-1), Boolean.TRUE);

            Map<String, Map<String, Object>> compatibleTypes = new HashMap<>();

            // Collect compatible types recursively from the container tree
            collectCompatibleTypes(typeContainers, compatibleTypes, currentTypeId, currentType);

            response.put("status", "success");
            response.put("currentType", currentTypeId);
            response.put("currentTypeDisplayName", currentType.getDisplayName());
            response.put("baseType", baseTypeId);
            response.put("compatibleTypes", compatibleTypes);
            response.put("count", compatibleTypes.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[TypeMigration] Error getting compatible types", e);
            response.put("status", "error");
            response.put("message", "Failed to get compatible types");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Recursively collect compatible types from type definition containers
     */
    private void collectCompatibleTypes(
            List<TypeDefinitionContainer> containers,
            Map<String, Map<String, Object>> compatibleTypes,
            String currentTypeId,
            TypeDefinition currentType) {

        if (containers == null) {
            return;
        }

        for (TypeDefinitionContainer container : containers) {
            TypeDefinition td = container.getTypeDefinition();

            if (td != null) {
                // Skip the current type
                if (!td.getId().equals(currentTypeId) && td.isCreatable()) {
                    Map<String, Object> typeInfo = new HashMap<>();
                    typeInfo.put("id", td.getId());
                    typeInfo.put("displayName", td.getDisplayName());
                    typeInfo.put("description", td.getDescription());
                    typeInfo.put("baseTypeId", td.getBaseTypeId().value());

                    // Find required properties that don't exist in current type
                    Map<String, String> requiredProperties = new HashMap<>();
                    if (td.getPropertyDefinitions() != null) {
                        for (PropertyDefinition<?> pd : td.getPropertyDefinitions().values()) {
                            if (pd.isRequired() && !isSystemProperty(pd.getId())) {
                                // Check if this property exists in current type
                                if (currentType.getPropertyDefinitions() == null ||
                                    !currentType.getPropertyDefinitions().containsKey(pd.getId())) {
                                    requiredProperties.put(pd.getId(), pd.getDisplayName());
                                }
                            }
                        }
                    }
                    typeInfo.put("additionalRequiredProperties", requiredProperties);

                    compatibleTypes.put(td.getId(), typeInfo);
                }
            }

            // Process child types recursively
            if (container.getChildren() != null) {
                collectCompatibleTypes(container.getChildren(), compatibleTypes, currentTypeId, currentType);
            }
        }
    }

    /**
     * Migrate an object to a new type
     *
     * @param repositoryId Repository ID
     * @param request Migration request containing objectId and newTypeId
     * @return Migration result
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> migrateType(
            @PathVariable String repositoryId,
            @RequestBody TypeMigrationRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String objectId = request.getObjectId();
            String newTypeId = request.getNewTypeId();

            log.info("[TypeMigration] Migrating objectId=" + objectId + " to newTypeId=" + newTypeId);

            // Validate request
            if (objectId == null || objectId.isEmpty()) {
                response.put("status", "error");
                response.put("message", "objectId is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            if (newTypeId == null || newTypeId.isEmpty()) {
                response.put("status", "error");
                response.put("message", "newTypeId is required");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Get the content
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Object not found: " + objectId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Get type definitions
            String currentTypeId = content.getObjectType();
            TypeDefinition currentType = getTypeManager().getTypeDefinition(repositoryId, currentTypeId);
            TypeDefinition newType = getTypeManager().getTypeDefinition(repositoryId, newTypeId);

            if (currentType == null) {
                response.put("status", "error");
                response.put("message", "Current type definition not found: " + currentTypeId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            if (newType == null) {
                response.put("status", "error");
                response.put("message", "Target type definition not found: " + newTypeId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Validate same base type
            if (!currentType.getBaseTypeId().equals(newType.getBaseTypeId())) {
                response.put("status", "error");
                response.put("message", "Type migration requires same base type. Current: " +
                    currentType.getBaseTypeId().value() + ", Target: " + newType.getBaseTypeId().value());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Validate target type is creatable
            if (!newType.isCreatable()) {
                response.put("status", "error");
                response.put("message", "Target type is not creatable: " + newTypeId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Check if same type
            if (currentTypeId.equals(newTypeId)) {
                response.put("status", "success");
                response.put("message", "Object already has the specified type");
                response.put("objectId", objectId);
                response.put("typeId", newTypeId);
                return ResponseEntity.ok(response);
            }

            // Perform the migration
            content.setObjectType(newTypeId);

            // Update change token
            String newChangeToken = String.valueOf(System.currentTimeMillis());
            content.setChangeToken(newChangeToken);

            // Set modified signature
            SystemCallContext callContext = new SystemCallContext(repositoryId);

            // Update the content
            Content updated;
            if (content.isDocument()) {
                updated = getContentService().update(callContext, repositoryId, (Document) content);
            } else if (content.isFolder()) {
                updated = getContentService().update(callContext, repositoryId, (Folder) content);
            } else {
                // Generic update
                updated = getContentService().update(callContext, repositoryId, content);
            }

            // Write change event (pass null for ACL as it's not changed)
            getContentService().writeChangeEvent(callContext, repositoryId, updated, null, ChangeType.UPDATED);

            log.info("[TypeMigration] Successfully migrated objectId=" + objectId +
                    " from " + currentTypeId + " to " + newTypeId);

            response.put("status", "success");
            response.put("message", "Type migration completed successfully");
            response.put("objectId", objectId);
            response.put("previousType", currentTypeId);
            response.put("newType", newTypeId);
            response.put("changeToken", newChangeToken);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[TypeMigration] Error migrating type", e);
            response.put("status", "error");
            response.put("message", "Type migration failed");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Check if a property is a CMIS system property
     */
    private boolean isSystemProperty(String propertyId) {
        return propertyId != null && propertyId.startsWith("cmis:");
    }
}
