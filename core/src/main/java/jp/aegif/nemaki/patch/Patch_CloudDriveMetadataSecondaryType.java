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
 * Cloud Drive Metadata Secondary Type Initialization Patch
 *
 * Registers the nemaki:cloudDriveMetadata secondary type that stores
 * cloud drive synchronization metadata on documents.
 *
 * Properties:
 * - nemaki:cloudProvider (STRING, SINGLE) - "google" or "microsoft"
 * - nemaki:cloudFileId (STRING, SINGLE) - File ID in the cloud provider
 * - nemaki:cloudFileUrl (STRING, SINGLE) - URL to open the file in the cloud
 * - nemaki:cloudLastSyncedAt (DATETIME, SINGLE) - Last sync timestamp
 * - nemaki:cloudEncryptedRefreshToken (STRING, SINGLE) - AES-256-GCM encrypted refresh token
 * - nemaki:cloudComments (STRING, SINGLE) - JSON array of comments from cloud provider
 * - nemaki:cloudCommentsImportedAt (DATETIME, SINGLE) - Timestamp when comments were imported
 *
 * This patch is idempotent - it will not create duplicate types on restart.
 */
public class Patch_CloudDriveMetadataSecondaryType extends AbstractNemakiPatch {

	private static final Log log = LogFactory.getLog(Patch_CloudDriveMetadataSecondaryType.class);

	private static final String PATCH_NAME = "cloud-drive-metadata-secondary-type-20260201";
	private static final String NEMAKI_NAMESPACE = "http://www.aegif.jp/NEMAKI";
	private static final String TYPE_ID = "nemaki:cloudDriveMetadata";

	@Override
	public String getName() {
		return PATCH_NAME;
	}

	@Override
	protected void applySystemPatch() {
		log.error("No system-wide configuration needed for cloudDriveMetadata secondary type");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.error("=== CLOUD DRIVE METADATA SECONDARY TYPE PATCH STARTED for repository: " + repositoryId + " ===");

		if ("canopy".equals(repositoryId)) {
			log.error("Skipping cloudDriveMetadata type for canopy - information management area");
			return;
		}

		if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
			log.error("Skipping cloudDriveMetadata type for archive repositories");
			return;
		}

		try {
			TypeService typeService = patchUtil.getTypeService();
			if (typeService == null) {
				log.error("TypeService not available, cannot apply cloudDriveMetadata secondary type patch");
				return;
			}

			createCloudDriveMetadataType(typeService, repositoryId);

			if (patchUtil.getTypeManager() != null) {
				patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
				log.error("Type cache invalidated for repository: " + repositoryId);
			}

			log.error("=== CLOUD DRIVE METADATA SECONDARY TYPE PATCH COMPLETED for repository: " + repositoryId + " ===");

		} catch (Exception e) {
			log.error("=== ERROR DURING CLOUD DRIVE METADATA SECONDARY TYPE PATCH for repository: " + repositoryId + " ===", e);
		}
	}

	private void createCloudDriveMetadataType(TypeService typeService, String repositoryId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
		boolean typeExists = existing != null;

		if (typeExists) {
			log.error("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - checking for missing properties");
		} else {
			log.error("Creating secondary type: " + TYPE_ID);
		}

		try {
			List<String> propertyIds = new ArrayList<>();

			String id1 = createStringProperty(typeService, repositoryId,
				"nemaki:cloudProvider", "cloudProvider", "Cloud Provider",
				"Cloud storage provider (google or microsoft)", true, true);
			if (id1 != null) propertyIds.add(id1);

			String id2 = createStringProperty(typeService, repositoryId,
				"nemaki:cloudFileId", "cloudFileId", "Cloud File ID",
				"File identifier in the cloud provider", true, false);
			if (id2 != null) propertyIds.add(id2);

			String id3 = createStringProperty(typeService, repositoryId,
				"nemaki:cloudFileUrl", "cloudFileUrl", "Cloud File URL",
				"URL to open the file in the cloud provider", false, false);
			if (id3 != null) propertyIds.add(id3);

			String id4 = createDateTimeProperty(typeService, repositoryId,
				"nemaki:cloudLastSyncedAt", "cloudLastSyncedAt", "Last Synced At",
				"Timestamp of the last synchronization with cloud");
			if (id4 != null) propertyIds.add(id4);

			String id5 = createStringProperty(typeService, repositoryId,
				"nemaki:cloudEncryptedRefreshToken", "cloudEncryptedRefreshToken",
				"Encrypted Refresh Token",
				"AES-256-GCM encrypted OAuth refresh token for cloud API access",
				false, false);
			if (id5 != null) propertyIds.add(id5);

			// Cloud comments properties (added 2026-02-03)
			String id6 = createStringProperty(typeService, repositoryId,
				"nemaki:cloudComments", "cloudComments", "Cloud Comments",
				"JSON array of comments imported from cloud provider (Google Docs / Microsoft 365)",
				false, false);
			if (id6 != null) propertyIds.add(id6);

			String id7 = createDateTimeProperty(typeService, repositoryId,
				"nemaki:cloudCommentsImportedAt", "cloudCommentsImportedAt",
				"Comments Imported At",
				"Timestamp when comments were last imported from cloud");
			if (id7 != null) propertyIds.add(id7);

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
				// Create new type
				NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
				typeDef.setTypeId(TYPE_ID);
				typeDef.setLocalName("cloudDriveMetadata");
				typeDef.setLocalNameSpace(NEMAKI_NAMESPACE);
				typeDef.setQueryName(TYPE_ID);
				typeDef.setDisplayName("Cloud Drive Metadata");
				typeDef.setDescription("Stores cloud drive synchronization metadata for documents pushed to Google Drive or OneDrive.");
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

				NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, typeDef);
				log.error("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());
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
		log.error("Property '" + propertyId + "' created with detail ID: " + detail.getId());
		return detail.getId();
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
		log.error("Property '" + propertyId + "' created with detail ID: " + detail.getId());
		return detail.getId();
	}
}
