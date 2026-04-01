package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds canonical source-tracking fields to the existing nemaki:externalIntegration
 * secondary type. These fields provide precise, queryable metadata for the
 * external ingestion pipeline introduced in Phase 1.
 *
 * <p>New properties:
 * <ul>
 *   <li>{@code nemaki:sourceArchetype} — FILE_SHARE, COMPOUND_NOTE, etc.</li>
 *   <li>{@code nemaki:sourceSystem} — google_drive, notion, salesforce, etc.</li>
 *   <li>{@code nemaki:sourceObjectType} — file, page, record, message, etc.</li>
 *   <li>{@code nemaki:sourceObjectId} — stable external object ID</li>
 *   <li>{@code nemaki:sourceUrl} — external URL to the source object</li>
 *   <li>{@code nemaki:ingestionRunId} — correlates to ExternalIngestRequest.requestId</li>
 * </ul>
 *
 * <p>This patch is idempotent — existing properties are skipped.
 */
public class Patch_ExternalIntegrationSourceFields extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ExternalIntegrationSourceFields.class);

    private static final String PATCH_NAME = "external-integration-source-fields-20260401";
    private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";
    private static final String TYPE_ID = "nemaki:externalIntegration";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== EXTERNAL INTEGRATION SOURCE FIELDS PATCH for repository: " + repositoryId + " ===");

        try {
            TypeService typeService = patchUtil.getTypeService();
            if (typeService == null) {
                log.error("TypeService not available");
                return;
            }

            NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
            if (existing == null) {
                log.warn("Type '" + TYPE_ID + "' not found — Patch_ExternalIntegrationSecondaryType must run first");
                return;
            }

            List<String> existingProps = existing.getProperties();
            if (existingProps == null) {
                existingProps = new ArrayList<>();
            }

            boolean updated = false;
            String[][] newProperties = {
                    {"nemaki:sourceArchetype", "sourceArchetype", "Source Archetype",
                            "Archetype classification: FILE_SHARE, COMPOUND_NOTE, CHAT_CONTEXT, BUSINESS_RECORD"},
                    {"nemaki:sourceSystem", "sourceSystem", "Source System",
                            "Canonical source system name (e.g. google_drive, notion, salesforce)"},
                    {"nemaki:sourceObjectType", "sourceObjectType", "Source Object Type",
                            "Object type in source system (e.g. file, page, record, message)"},
                    {"nemaki:sourceObjectId", "sourceObjectId", "Source Object ID",
                            "Stable object identifier in the source system"},
                    {"nemaki:sourceUrl", "sourceUrl", "Source URL",
                            "URL to the object in the source system"},
                    {"nemaki:ingestionRunId", "ingestionRunId", "Ingestion Run ID",
                            "Correlation ID linking to the ExternalIngestRequest that created this document"},
            };

            for (String[] prop : newProperties) {
                String propId = prop[0];
                String localName = prop[1];
                String displayName = prop[2];
                String description = prop[3];
                boolean queryable = !"nemaki:sourceUrl".equals(propId);

                String detailId = createStringPropertyIfAbsent(typeService, repositoryId,
                        propId, localName, displayName, description, queryable);
                if (detailId != null && !existingProps.contains(detailId)) {
                    existingProps.add(detailId);
                    updated = true;
                    log.info("Added property '" + propId + "' to type '" + TYPE_ID + "'");
                }
            }

            if (updated) {
                existing.setProperties(existingProps);
                typeService.updateTypeDefinition(repositoryId, existing);
                log.info("Type '" + TYPE_ID + "' updated with source fields");
            } else {
                log.info("Type '" + TYPE_ID + "' already has all source fields");
            }

            if (patchUtil.getTypeManager() != null) {
                patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
                try {
                    patchUtil.getTypeManager().refreshTypes();
                } catch (Exception e) {
                    log.warn("Failed to refresh type cache: " + e.getMessage());
                }
            }

            log.info("=== EXTERNAL INTEGRATION SOURCE FIELDS PATCH COMPLETED for repository: " + repositoryId + " ===");

        } catch (Exception e) {
            log.error("=== ERROR DURING SOURCE FIELDS PATCH for repository: " + repositoryId + " ===", e);
        }
    }

    private String createStringPropertyIfAbsent(TypeService typeService, String repositoryId,
            String propertyId, String localName, String displayName, String description,
            boolean queryable) {

        var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
        if (existingCore != null) {
            var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
            if (existingDetails != null && !existingDetails.isEmpty()) {
                return existingDetails.get(0).getId();
            }
        }

        log.info("Creating property: " + propertyId);
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
        propDef.setQueryable(queryable);
        propDef.setOrderable(queryable);

        NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
        log.info("Property '" + propertyId + "' created with detail ID: " + detail.getId());
        return detail.getId();
    }
}
