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
		log.info("No system-wide configuration needed for cloudDriveMetadata secondary type");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("=== CLOUD DRIVE METADATA SECONDARY TYPE PATCH STARTED for repository: " + repositoryId + " ===");

		if ("canopy".equals(repositoryId)) {
			log.info("Skipping cloudDriveMetadata type for canopy - information management area");
			return;
		}

		if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
			log.info("Skipping cloudDriveMetadata type for archive repositories");
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
				log.info("Type cache invalidated for repository: " + repositoryId);
			}

			log.info("=== CLOUD DRIVE METADATA SECONDARY TYPE PATCH COMPLETED for repository: " + repositoryId + " ===");

		} catch (Exception e) {
			log.error("=== ERROR DURING CLOUD DRIVE METADATA SECONDARY TYPE PATCH for repository: " + repositoryId + " ===", e);
		}
	}

	private void createCloudDriveMetadataType(TypeService typeService, String repositoryId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, TYPE_ID);
		if (existing != null) {
			log.info("Type '" + TYPE_ID + "' already exists (ID: " + existing.getId() + ") - skipping");
			return;
		}

		log.info("Creating secondary type: " + TYPE_ID);

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
			log.info("Type '" + TYPE_ID + "' created successfully with ID: " + created.getId());

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
