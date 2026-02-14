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
 * External Integration Secondary Type Initialization Patch
 *
 * Registers the nemaki:externalIntegration secondary type that stores
 * external context data from various sources for RAG search and display.
 *
 * Properties:
 * - nemaki:externalContext (STRING, SINGLE) - JSON with context data (max 5000 chars)
 * - nemaki:externalContextUpdatedAt (DATETIME, SINGLE) - Last update timestamp
 * - nemaki:externalSourceType (STRING, SINGLE) - Source type: cloud_sync, crm, erp, chat, etc.
 * - nemaki:externalSourceId (STRING, SINGLE) - Source system identifier (e.g., google, microsoft, salesforce)
 *
 * Use cases:
 * - Cloud Drive sync: comments, activities, approvals from Google Drive / OneDrive
 * - CRM integration: record context from Salesforce, HubSpot, etc.
 * - ERP integration: transaction context from SAP, Oracle, etc.
 * - Chat integration: conversation context from Slack, Teams, etc.
 *
 * This patch is idempotent - it will not create duplicate types on restart.
 */
public class Patch_ExternalIntegrationSecondaryType extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_ExternalIntegrationSecondaryType.class);

	private static final String PATCH_NAME = "external-integration-secondary-type-20260204";
	private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";
	private static final String TYPE_ID = "nemaki:externalIntegration";

	@Override
	public String getName() {
		return PATCH_NAME;
	}

	@Override
	protected void applySystemPatch() {
		log.info("No system-wide configuration needed for externalIntegration secondary type");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("=== EXTERNAL INTEGRATION SECONDARY TYPE PATCH STARTED for repository: " + repositoryId + " ===");

		if ("canopy".equals(repositoryId)) {
			log.info("Skipping externalIntegration type for canopy - information management area");
			return;
		}

		if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
			log.info("Skipping externalIntegration type for archive repositories");
			return;
		}

		try {
			TypeService typeService = patchUtil.getTypeService();
			if (typeService == null) {
				log.error("TypeService not available, cannot apply externalIntegration secondary type patch");
				return;
			}

			createExternalIntegrationType(typeService, repositoryId);

			if (patchUtil.getTypeManager() != null) {
				patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
				log.info("Type cache invalidated for repository: " + repositoryId);

				try {
					patchUtil.getTypeManager().refreshTypes();
					log.info("Type cache refreshed for repository: " + repositoryId);
				} catch (Exception e) {
					log.warn("Failed to refresh type cache: " + e.getMessage());
				}
			}

			log.info("=== EXTERNAL INTEGRATION SECONDARY TYPE PATCH COMPLETED for repository: " + repositoryId + " ===");

		} catch (Exception e) {
			log.error("=== ERROR DURING EXTERNAL INTEGRATION SECONDARY TYPE PATCH for repository: " + repositoryId + " ===", e);
		}
	}

	private void createExternalIntegrationType(TypeService typeService, String repositoryId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
		boolean typeExists = existing != null;

		if (typeExists) {
			log.info("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - checking for missing properties");
		} else {
			log.info("Creating secondary type: " + TYPE_ID);
		}

		try {
			List<String> propertyIds = new ArrayList<>();

			// nemaki:externalContext - JSON with context data (comments, activities, metadata)
			String id1 = createStringProperty(typeService, repositoryId,
				"nemaki:externalContext", "externalContext", "External Context",
				"JSON with context data from external sources (comments, activities, metadata). Max 5000 chars for RAG compatibility.",
				false, false);
			if (id1 != null) propertyIds.add(id1);

			// nemaki:externalContextUpdatedAt - Last update timestamp
			String id2 = createDateTimeProperty(typeService, repositoryId,
				"nemaki:externalContextUpdatedAt", "externalContextUpdatedAt",
				"External Context Updated At",
				"Timestamp when external context was last updated");
			if (id2 != null) propertyIds.add(id2);

			// nemaki:externalSourceType - Source type identifier
			String id3 = createStringProperty(typeService, repositoryId,
				"nemaki:externalSourceType", "externalSourceType", "External Source Type",
				"Type of external source: cloud_sync, crm, erp, chat, email, etc.",
				true, true);
			if (id3 != null) propertyIds.add(id3);

			// nemaki:externalSourceId - Source system identifier
			String id4 = createStringProperty(typeService, repositoryId,
				"nemaki:externalSourceId", "externalSourceId", "External Source ID",
				"Identifier of the external source system (e.g., google, microsoft, salesforce, slack)",
				true, true);
			if (id4 != null) propertyIds.add(id4);

			if (typeExists) {
				// Type already exists - add any new properties to it
				List<String> existingProps = existing.getProperties();
				if (existingProps == null) {
					existingProps = new ArrayList<>();
				}
				boolean updated = false;
				for (String propId : propertyIds) {
					if (!existingProps.contains(propId)) {
						existingProps.add(propId);
						updated = true;
						log.info("Adding new property ID '" + propId + "' to existing type");
					}
				}
				if (updated) {
					existing.setProperties(existingProps);
					typeService.updateTypeDefinition(repositoryId, existing);
					log.info("Type '" + TYPE_ID + "' updated with new properties");
				} else {
					log.info("Type '" + TYPE_ID + "' already has all required properties");
				}
			} else {
				// Create new type
				NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
				typeDef.setTypeId(TYPE_ID);
				typeDef.setLocalName("externalIntegration");
				typeDef.setLocalNameSpace(NEMAKI_NAMESPACE);
				typeDef.setQueryName(TYPE_ID);
				typeDef.setDisplayName("External Integration");
				typeDef.setDescription("Stores external context data from various sources (cloud sync, CRM, ERP, chat) for RAG search and display.");
				typeDef.setBaseId(BaseTypeId.CMIS_SECONDARY);
				typeDef.setParentId("cmis:secondary");
				typeDef.setCreatable(false);
				typeDef.setFilable(false);
				typeDef.setQueryable(true);
				typeDef.setFulltextIndexed(true);  // Enable for RAG search
				typeDef.setIncludedInSupertypeQuery(true);
				typeDef.setControllablePolicy(false);
				typeDef.setControllableACL(false);
				typeDef.setTypeMutabilityCreate(true);
				typeDef.setTypeMutabilityUpdate(true);
				typeDef.setTypeMutabilityDelete(true);

				if (!propertyIds.isEmpty()) {
					typeDef.setProperties(propertyIds);
				}

				try {
					NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
					log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());
				} catch (Exception createEx) {
					String message = createEx.getMessage() != null ? createEx.getMessage().toLowerCase() : "";
					Throwable cause = createEx.getCause();
					String causeMessage = (cause != null && cause.getMessage() != null) ? cause.getMessage().toLowerCase() : "";

					if (message.contains("conflict") || causeMessage.contains("conflict") ||
						createEx.getClass().getSimpleName().contains("Conflict") ||
						(cause != null && cause.getClass().getSimpleName().contains("Conflict"))) {
						log.info("Type '" + TYPE_ID + "' already exists in CouchDB (Conflict detected) - this is OK");
					} else {
						throw createEx;
					}
				}
			}

		} catch (Exception e) {
			log.error("Failed to create type: " + TYPE_ID, e);
		}
	}

	private String createStringProperty(TypeService typeService, String repositoryId,
			String propertyId, String localName, String displayName, String description,
			boolean queryable, boolean orderable) {

		var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
		if (existingCore != null) {
			log.info("Property '" + propertyId + "' core already exists - reusing");
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
		propDef.setOrderable(orderable);

		NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
		log.info("Property '" + propertyId + "' created with detail ID: " + detail.getId());
		return detail.getId();
	}

	private String createDateTimeProperty(TypeService typeService, String repositoryId,
			String propertyId, String localName, String displayName, String description) {

		var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
		if (existingCore != null) {
			log.info("Property '" + propertyId + "' core already exists - reusing");
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
		propDef.setPropertyType(PropertyType.DATETIME);
		propDef.setCardinality(Cardinality.SINGLE);
		propDef.setUpdatability(Updatability.READWRITE);
		propDef.setRequired(false);
		propDef.setQueryable(true);
		propDef.setOrderable(true);

		NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
		log.info("Property '" + propertyId + "' created with detail ID: " + detail.getId());
		return detail.getId();
	}
}
