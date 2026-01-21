package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * NemakiWare Standard Types Initialization Patch
 *
 * Registers NemakiWare standard type definitions that were previously included
 * in the database dump but were removed in 2015 (commit f19550894).
 *
 * Types registered:
 * 1. nemaki:document - Document type extending cmis:document with nemaki:tag property
 * 2. nemaki:commentable - Secondary type for adding comments
 * 3. nemaki:classificationInfo - Secondary type for document classification
 * 4. nemaki:clientInfo - Secondary type for client information
 * 5. nemaki:reviewInfo - Secondary type for review information
 *
 * This patch is idempotent - it will not create duplicate types on restart.
 *
 * CRITICAL: This patch must execute AFTER Patch_StandardCmisViews to ensure
 * basic CMIS types (cmis:document, cmis:secondary) are available.
 */
public class Patch_NemakiwareStandardTypes extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_NemakiwareStandardTypes.class);

    private static final String PATCH_NAME = "nemakiware-standard-types-20251211";
    private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        log.info("No system-wide configuration needed for NemakiWare standard types");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.error("=== NEMAKIWARE STANDARD TYPES PATCH STARTED for repository: " + repositoryId + " ===");

        if ("canopy".equals(repositoryId)) {
            log.info("Skipping NemakiWare Standard Types for canopy - information management area");
            return;
        }

        if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
            log.info("Skipping NemakiWare Standard Types for archive repositories");
            return;
        }

        try {
            TypeService typeService = patchUtil.getTypeService();
            if (typeService == null) {
                log.error("TypeService not available, cannot apply NemakiWare Standard Types patch");
                return;
            }

            // 1. Create nemaki:document type with nemaki:tag property
            createNemakiDocumentType(typeService, repositoryId);

            // 2. Create secondary types
            createCommentableType(typeService, repositoryId);
            createClassificationInfoType(typeService, repositoryId);
            createClientInfoType(typeService, repositoryId);
            createReviewInfoType(typeService, repositoryId);

            // Invalidate type cache to reflect new types
            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                log.info("Type cache invalidated for repository: " + repositoryId);
            }

            log.error("=== NEMAKIWARE STANDARD TYPES PATCH COMPLETED for repository: " + repositoryId + " ===");

        } catch (Exception e) {
            log.error("=== ERROR DURING NEMAKIWARE STANDARD TYPES PATCH for repository: " + repositoryId + " ===", e);
        }
    }

    /**
     * Create nemaki:document type extending cmis:document
     * Properties: nemaki:tag (MULTI STRING)
     */
    private void createNemakiDocumentType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:document";

        // Check if type already exists
        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - skipping");
            return;
        }

        log.info("Creating type: " + TYPE_ID);

        try {
            // First, create the nemaki:tag property definition
            String tagDetailId = createTagPropertyDefinition(typeService, repositoryId);
            if (tagDetailId == null) {
                log.error("Failed to create nemaki:tag property definition");
                return;
            }

            // Create type definition
            NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
            typeDef.setTypeId(TYPE_ID);
            typeDef.setLocalName("document");
            typeDef.setLocalNameSpace(NEMAKI_NAMESPACE);
            typeDef.setQueryName(TYPE_ID);
            typeDef.setDisplayName("NemakiWare Document");
            typeDef.setDescription("NemakiWare standard document type with tag support");
            typeDef.setBaseId(BaseTypeId.CMIS_DOCUMENT);
            typeDef.setParentId("cmis:document");
            typeDef.setCreatable(true);
            typeDef.setFilable(true);
            typeDef.setQueryable(true);
            typeDef.setFulltextIndexed(true);
            typeDef.setIncludedInSupertypeQuery(true);
            typeDef.setControllablePolicy(false);
            typeDef.setControllableACL(true);
            typeDef.setTypeMutabilityCreate(true);
            typeDef.setTypeMutabilityUpdate(true);
            typeDef.setTypeMutabilityDelete(true);
            typeDef.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
            typeDef.setVersionable(true);

            // Set properties (link to tag property detail)
            List<String> propertyIds = new ArrayList<>();
            propertyIds.add(tagDetailId);
            typeDef.setProperties(propertyIds);

            // Create the type
            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    /**
     * Create nemaki:tag property definition
     * @return Property definition detail ID
     */
    private String createTagPropertyDefinition(TypeService typeService, String repositoryId) {
        final String PROPERTY_ID = "nemaki:tag";

        // Check if property already exists
        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, PROPERTY_ID);
        if (existingCore != null) {
            log.info("Property '" + PROPERTY_ID + "' core already exists - reusing");
            // Find existing detail for this core
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        log.info("Creating property: " + PROPERTY_ID);

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(PROPERTY_ID);
        propDef.setLocalName("tag");
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(PROPERTY_ID);
        propDef.setDisplayName("Tag");
        propDef.setDescription("Tags for categorizing documents");
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.MULTI);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(true);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        log.info("Property '" + PROPERTY_ID + "' created with detail ID: " + detail.getId());
        return detail.getId();
    }

    /**
     * Create nemaki:commentable secondary type
     * Properties: nemaki:comment (MULTI STRING)
     */
    private void createCommentableType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:commentable";

        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists - skipping");
            return;
        }

        log.info("Creating secondary type: " + TYPE_ID);

        try {
            // Create nemaki:comment property
            String commentDetailId = createCommentPropertyDefinition(typeService, repositoryId);

            NemakiTypeDefinition typeDef = createSecondaryTypeDefinition(
                TYPE_ID, "commentable", "Commentable",
                "A content with this type is commentable.",
                commentDetailId != null ? List.of(commentDetailId) : null
            );

            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    private String createCommentPropertyDefinition(TypeService typeService, String repositoryId) {
        final String PROPERTY_ID = "nemaki:comment";

        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, PROPERTY_ID);
        if (existingCore != null) {
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(PROPERTY_ID);
        propDef.setLocalName("comment");
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(PROPERTY_ID);
        propDef.setDisplayName("Comment");
        propDef.setDescription("Comments on the content");
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.MULTI);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(true);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        return detail.getId();
    }

    /**
     * Create nemaki:classificationInfo secondary type
     * Properties: nemaki:classification (MULTI STRING)
     */
    private void createClassificationInfoType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:classificationInfo";

        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists - skipping");
            return;
        }

        log.info("Creating secondary type: " + TYPE_ID);

        try {
            String classificationDetailId = createClassificationPropertyDefinition(typeService, repositoryId);

            NemakiTypeDefinition typeDef = createSecondaryTypeDefinition(
                TYPE_ID, "classificationInfo", "Document Classification Information",
                "Document classification information for categorization",
                classificationDetailId != null ? List.of(classificationDetailId) : null
            );

            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    private String createClassificationPropertyDefinition(TypeService typeService, String repositoryId) {
        final String PROPERTY_ID = "nemaki:classification";

        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, PROPERTY_ID);
        if (existingCore != null) {
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(PROPERTY_ID);
        propDef.setLocalName("classification");
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(PROPERTY_ID);
        propDef.setDisplayName("Document Class");
        propDef.setDescription("Document classification categories");
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.MULTI);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(true);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        return detail.getId();
    }

    /**
     * Create nemaki:clientInfo secondary type
     * Properties: nemaki:customerName (SINGLE STRING), nemaki:personInCharge (SINGLE STRING)
     */
    private void createClientInfoType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:clientInfo";

        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists - skipping");
            return;
        }

        log.info("Creating secondary type: " + TYPE_ID);

        try {
            String customerNameDetailId = createSingleStringPropertyDefinition(
                typeService, repositoryId, "nemaki:customerName", "customerName", "Customer Name", "Customer name"
            );
            String personInChargeDetailId = createSingleStringPropertyDefinition(
                typeService, repositoryId, "nemaki:personInCharge", "personInCharge", "Person in Charge", "Person in charge of the client"
            );

            List<String> propertyIds = new ArrayList<>();
            if (customerNameDetailId != null) propertyIds.add(customerNameDetailId);
            if (personInChargeDetailId != null) propertyIds.add(personInChargeDetailId);

            NemakiTypeDefinition typeDef = createSecondaryTypeDefinition(
                TYPE_ID, "clientInfo", "Client Information",
                "Client information for business documents",
                propertyIds.isEmpty() ? null : propertyIds
            );

            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    /**
     * Create nemaki:reviewInfo secondary type
     * Properties: nemaki:reviewStatus, nemaki:nextReviewer, nemaki:comment (shared)
     */
    private void createReviewInfoType(TypeService typeService, String repositoryId) {
        final String TYPE_ID = "nemaki:reviewInfo";

        NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
        if (existing != null) {
            log.info("Type '" + TYPE_ID + "' already exists - skipping");
            return;
        }

        log.info("Creating secondary type: " + TYPE_ID);

        try {
            String reviewStatusDetailId = createSingleStringPropertyDefinition(
                typeService, repositoryId, "nemaki:reviewStatus", "reviewStatus", "Review Status", "Current review status"
            );
            String nextReviewerDetailId = createSingleStringPropertyDefinition(
                typeService, repositoryId, "nemaki:nextReviewer", "nextReviewer", "Next Reviewer", "Next person to review"
            );

            List<String> propertyIds = new ArrayList<>();
            if (reviewStatusDetailId != null) propertyIds.add(reviewStatusDetailId);
            if (nextReviewerDetailId != null) propertyIds.add(nextReviewerDetailId);

            NemakiTypeDefinition typeDef = createSecondaryTypeDefinition(
                TYPE_ID, "reviewInfo", "Review Information",
                "Review workflow information for documents",
                propertyIds.isEmpty() ? null : propertyIds
            );

            NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create type: " + TYPE_ID, e);
        }
    }

    /**
     * Helper to create a single-value string property definition
     */
    private String createSingleStringPropertyDefinition(
            TypeService typeService, String repositoryId,
            String propertyId, String localName, String displayName, String description) {

        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
        if (existingCore != null) {
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
        propDef.setPropertyId(propertyId);
        propDef.setLocalName(localName);
        propDef.setLocalNameSpace(NEMAKI_NAMESPACE);
        propDef.setQueryName(propertyId);
        propDef.setDisplayName(displayName);
        propDef.setDescription(description);
        propDef.setPropertyType(PropertyType.STRING);
        propDef.setCardinality(Cardinality.SINGLE);
        propDef.setUpdatability(Updatability.READWRITE);
        propDef.setRequired(false);
        propDef.setQueryable(true);
        propDef.setOrderable(true);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
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
