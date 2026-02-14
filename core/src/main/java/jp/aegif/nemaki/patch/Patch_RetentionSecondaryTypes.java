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
 * CMIS 1.1 Retention Management Secondary Type Initialization Patch
 *
 * Registers the cmis:rm_clientMgtRetention secondary type that enables
 * per-document expiration-based retention management.
 *
 * Properties:
 * - cmis:rm_expirationDate (DATETIME, SINGLE) - Document expiration date
 * - cmis:rm_startOfRetention (DATETIME, SINGLE) - Retention start date
 *
 * This patch is idempotent - it will not create duplicate types on restart.
 */
public class Patch_RetentionSecondaryTypes extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_RetentionSecondaryTypes.class);

	private static final String PATCH_NAME = "retention-secondary-types-20260209";
	private static final String CMIS_NAMESPACE = "http://docs.oasis-open.org/ns/cmis/core/200908/";
	private static final String TYPE_ID = "cmis:rm_clientMgtRetention";

	@Override
	public String getName() {
		return PATCH_NAME;
	}

	@Override
	protected void applySystemPatch() {
		log.error("No system-wide configuration needed for retention secondary type");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.error("=== RETENTION SECONDARY TYPE PATCH STARTED for repository: " + repositoryId + " ===");

		if ("canopy".equals(repositoryId)) {
			log.error("Skipping retention secondary type for canopy");
			return;
		}

		if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
			log.error("Skipping retention secondary type for archive repositories");
			return;
		}

		try {
			TypeService typeService = patchUtil.getTypeService();
			if (typeService == null) {
				log.error("TypeService not available, cannot apply retention secondary type patch");
				return;
			}

			createRetentionType(typeService, repositoryId);

			if (patchUtil.getTypeManager() != null) {
				patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
				log.error("Type cache invalidated for repository: " + repositoryId);

				try {
					patchUtil.getTypeManager().refreshTypes();
					log.error("Type cache refreshed for repository: " + repositoryId);
				} catch (Exception e) {
					log.error("Failed to refresh type cache: " + e.getMessage());
				}
			}

			log.error("=== RETENTION SECONDARY TYPE PATCH COMPLETED for repository: " + repositoryId + " ===");

		} catch (Exception e) {
			log.error("=== ERROR DURING RETENTION SECONDARY TYPE PATCH for repository: " + repositoryId + " ===", e);
		}
	}

	private void createRetentionType(TypeService typeService, String repositoryId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
		boolean typeExists = existing != null;

		if (typeExists) {
			log.error("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - checking for missing properties");
		} else {
			log.error("Creating secondary type: " + TYPE_ID);
		}

		try {
			List<String> propertyIds = new ArrayList<>();

			String id1 = createDateTimeProperty(typeService, repositoryId,
				"cmis:rm_expirationDate", "rm_expirationDate", "Expiration Date",
				"The date/time at which the retention period expires");
			if (id1 != null) propertyIds.add(id1);

			String id2 = createDateTimeProperty(typeService, repositoryId,
				"cmis:rm_startOfRetention", "rm_startOfRetention", "Start of Retention",
				"The date/time at which the retention period starts");
			if (id2 != null) propertyIds.add(id2);

			if (typeExists) {
				List<String> existingProps = existing.getProperties();
				if (existingProps == null) {
					existingProps = new ArrayList<>();
				}
				boolean updated = false;
				for (String propId : propertyIds) {
					if (!existingProps.contains(propId)) {
						existingProps.add(propId);
						updated = true;
						log.error("Adding new property ID '" + propId + "' to existing type");
					}
				}
				if (updated) {
					existing.setProperties(existingProps);
					typeService.updateTypeDefinition(repositoryId, existing);
					log.error("Type '" + TYPE_ID + "' updated with new properties");
				} else {
					log.error("Type '" + TYPE_ID + "' already has all required properties");
				}
			} else {
				NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
				typeDef.setTypeId(TYPE_ID);
				typeDef.setLocalName("rm_clientMgtRetention");
				typeDef.setLocalNameSpace(CMIS_NAMESPACE);
				typeDef.setQueryName(TYPE_ID);
				typeDef.setDisplayName("Client Managed Retention");
				typeDef.setDescription("CMIS 1.1 standard secondary type for client-managed retention with expiration date.");
				typeDef.setBaseId(BaseTypeId.CMIS_SECONDARY);
				typeDef.setParentId("cmis:secondary");
				typeDef.setCreatable(false);
				typeDef.setFilable(false);
				typeDef.setQueryable(true);
				typeDef.setFulltextIndexed(false);
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
					log.error("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());
				} catch (Exception createEx) {
					String message = createEx.getMessage() != null ? createEx.getMessage().toLowerCase() : "";
					Throwable cause = createEx.getCause();
					String causeMessage = (cause != null && cause.getMessage() != null) ? cause.getMessage().toLowerCase() : "";

					if (message.contains("conflict") || causeMessage.contains("conflict") ||
						createEx.getClass().getSimpleName().contains("Conflict") ||
						(cause != null && cause.getClass().getSimpleName().contains("Conflict"))) {
						log.error("Type '" + TYPE_ID + "' already exists in CouchDB (Conflict detected) - this is OK");
					} else {
						throw createEx;
					}
				}
			}

		} catch (Exception e) {
			log.error("Failed to create type: " + TYPE_ID, e);
		}
	}

	private String createDateTimeProperty(TypeService typeService, String repositoryId,
			String propertyId, String localName, String displayName, String description) {

		var existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
		if (existingCore != null) {
			log.error("Property '" + propertyId + "' core already exists - reusing");
			var existingDetails = typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
			if (existingDetails != null && !existingDetails.isEmpty()) {
				return existingDetails.get(0).getId();
			}
		}

		log.error("Creating property: " + propertyId);

		NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
		propDef.setPropertyId(propertyId);
		propDef.setLocalName(localName);
		propDef.setLocalNameSpace(CMIS_NAMESPACE);
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
		log.error("Property '" + propertyId + "' created with detail ID: " + detail.getId());
		return detail.getId();
	}
}
