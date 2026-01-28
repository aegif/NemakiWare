package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhook Secondary Type Initialization Patch
 *
 * Registers the nemaki:webhookable secondary type that enables webhook
 * functionality on folders and documents.
 *
 * Type registered:
 * - nemaki:webhookable - Secondary type for webhook configuration
 *
 * Properties:
 * - nemaki:webhookConfigs (STRING, SINGLE) - JSON array of webhook configurations
 * - nemaki:webhookMaxDepth (INTEGER, SINGLE) - DEPRECATED: Max depth for child events
 *
 * This patch is idempotent - it will not create duplicate types on restart.
 *
 * CRITICAL: This patch must execute AFTER Patch_StandardCmisViews to ensure
 * basic CMIS types (cmis:secondary) are available.
 */
public class Patch_WebhookableSecondaryType extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_WebhookableSecondaryType.class);

    private static final String PATCH_NAME = "webhookable-secondary-type-20260127";
    private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        log.info("No system-wide configuration needed for webhookable secondary type");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== WEBHOOKABLE SECONDARY TYPE PATCH STARTED for repository: " + repositoryId + " ===");

        if ("canopy".equals(repositoryId)) {
            log.info("Skipping webhookable type for canopy - information management area");
            return;
        }

        if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
            log.info("Skipping webhookable type for archive repositories");
            return;
        }

        try {
            TypeService typeService = patchUtil.getTypeService();
            if (typeService == null) {
                log.error("TypeService not available, cannot apply webhookable secondary type patch");
                return;
            }

            // Create nemaki:webhookable secondary type
            createWebhookableType(typeService, repositoryId);

            // Invalidate type cache to reflect new types
            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                log.info("Type cache invalidated for repository: " + repositoryId);
            }

            log.info("=== WEBHOOKABLE SECONDARY TYPE PATCH COMPLETED for repository: " + repositoryId + " ===");

        } catch (Exception e) {
            log.error("=== ERROR DURING WEBHOOKABLE SECONDARY TYPE PATCH for repository: " + repositoryId + " ===", e);
        }
    }

    /**
     * Create nemaki:webhookable secondary type
     * Properties:
     * - nemaki:webhookConfigs (STRING, SINGLE) - JSON array of webhook configurations
     * - nemaki:webhookMaxDepth (INTEGER, SINGLE) - DEPRECATED: Max depth for child events
     */
    private void createWebhookableType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:webhookable";

        // Check if type already exists
        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - skipping");
            return;
        }

        log.info("Creating secondary type: " + TYPE_ID);

        try {
            // Create property definitions
            String webhookConfigsDetailId = createWebhookConfigsPropertyDefinition(typeService, repositoryId);
            String webhookMaxDepthDetailId = createWebhookMaxDepthPropertyDefinition(typeService, repositoryId);

            List<String> propertyIds = new ArrayList<>();
            if (webhookConfigsDetailId != null) {
                propertyIds.add(webhookConfigsDetailId);
            }
            if (webhookMaxDepthDetailId != null) {
                propertyIds.add(webhookMaxDepthDetailId);
            }

            // Create type definition
            NemakiTypeDefinition typeDef = createSecondaryTypeDefinition(
                TYPE_ID, "webhookable", "Webhookable",
                "A content with this type can trigger webhook notifications on events.",
                propertyIds.isEmpty() ? null : propertyIds
            );

            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    /**
     * Create nemaki:webhookConfigs property definition
     * This property stores a JSON array of webhook configurations.
     * 
     * @return Property definition detail ID
     */
    private String createWebhookConfigsPropertyDefinition(TypeService typeService, String repositoryId) {
        final String PROPERTY_ID = "nemaki:webhookConfigs";

        // Check if property already exists
        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, PROPERTY_ID);
        if (existingCore != null) {
            log.info("Property '" + PROPERTY_ID + "' core already exists - reusing");
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        log.info("Creating property: " + PROPERTY_ID);

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(PROPERTY_ID);
        propDef.setLocalName("webhookConfigs");
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(PROPERTY_ID);
        propDef.setDisplayName("Webhook Configurations");
        propDef.setDescription("JSON array of webhook configurations for this object");
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.SINGLE);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(false);  // JSON content is not queryable
        propDef.setOrderable(false);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        log.info("Property '" + PROPERTY_ID + "' created with detail ID: " + detail.getId());
        return detail.getId();
    }

    /**
     * Create nemaki:webhookMaxDepth property definition
     * DEPRECATED: This property is kept for backward compatibility.
     * Use webhookConfigs[].maxDepth instead.
     * 
     * @return Property definition detail ID
     */
    private String createWebhookMaxDepthPropertyDefinition(TypeService typeService, String repositoryId) {
        final String PROPERTY_ID = "nemaki:webhookMaxDepth";

        // Check if property already exists
        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, PROPERTY_ID);
        if (existingCore != null) {
            log.info("Property '" + PROPERTY_ID + "' core already exists - reusing");
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        log.info("Creating property: " + PROPERTY_ID);

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(PROPERTY_ID);
        propDef.setLocalName("webhookMaxDepth");
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(PROPERTY_ID);
        propDef.setDisplayName("Webhook Max Depth (Deprecated)");
        propDef.setDescription("DEPRECATED: Maximum depth for child event monitoring. Use webhookConfigs[].maxDepth instead.");
        propDef.setPropertyType(PropertyType.INTEGER);
        propDef.setCardinality(Cardinality.SINGLE);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(true);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        log.info("Property '" + PROPERTY_ID + "' created with detail ID: " + detail.getId());
        return detail.getId();
    }

    /**
     * Helper to create a secondary type definition
     */
    private NemakiTypeDefinition createSecondaryTypeDefinition(
            String typeId, String localName, String displayName,
            String description, List<String> propertyDetailIds) {

        NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
        typeDef.setTypeId(typeId);
        typeDef.setLocalName(localName);
        typeDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        typeDef.setQueryName(typeId);
        typeDef.setDisplayName(displayName);
        typeDef.setDescription(description);
        typeDef.setBaseId(BaseTypeId.CMIS_SECONDARY);
        typeDef.setParentId("cmis:secondary");
        // CMIS 1.1 SPEC: Secondary types MUST NOT be creatable (TCK requirement)
        typeDef.setCreatable(false);
        typeDef.setFilable(false);  // Secondary types are not fileable
        typeDef.setQueryable(true);
        typeDef.setFulltextIndexed(false);
        typeDef.setIncludedInSupertypeQuery(true);
        typeDef.setControllablePolicy(false);
        typeDef.setControllableACL(false);
        typeDef.setTypeMutabilityCreate(true);
        typeDef.setTypeMutabilityUpdate(true);
        typeDef.setTypeMutabilityDelete(true);

        if (propertyDetailIds != null && !propertyDetailIds.isEmpty()) {
            typeDef.setProperties(propertyDetailIds);
        }

        return typeDef;
    }
}
