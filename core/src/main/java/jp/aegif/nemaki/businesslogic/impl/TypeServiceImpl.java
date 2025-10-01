package jp.aegif.nemaki.businesslogic.impl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TypeServiceImpl implements TypeService{

	private static final Log log = LogFactory.getLog(TypeServiceImpl.class);
	private ContentDaoService contentDaoService;
	private jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager;

	public TypeServiceImpl() {


	}

	public TypeServiceImpl(ContentDaoService contentDaoService) {
		setContentDaoService(contentDaoService);
	}

	@Override
	public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		return contentDaoService.getTypeDefinition(repositoryId, typeId);
	}

	@Override
	public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
		List<NemakiTypeDefinition> result = contentDaoService.getTypeDefinitions(repositoryId);
		return result;
	}

	@Override
	public NemakiPropertyDefinition getPropertyDefinition(String repositoryId, String detailNodeId) {
		NemakiPropertyDefinitionDetail detail = getPropertyDefinitionDetail(repositoryId, detailNodeId);
		
		if (detail == null) {
			return null;
		}
		
		String coreNodeId = detail.getCoreNodeId();
		
		NemakiPropertyDefinitionCore core = getPropertyDefinitionCore(repositoryId, coreNodeId);
		
		if (core == null) {
			return null;
		}

		NemakiPropertyDefinition npd = new NemakiPropertyDefinition(core,
				detail);
		return npd;
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String coreId) {
		return contentDaoService.getPropertyDefinitionCore(repositoryId, coreId);
	}

	@Override
	public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(
			String repositoryId, String propertyId) {
		return contentDaoService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
	}

	@Override
	public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
		return contentDaoService.getPropertyDefinitionCores(repositoryId);
	}

	@Override
	public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(
			String repositoryId, String detailId) {
		return contentDaoService.getPropertyDefinitionDetail(repositoryId, detailId);
	}

	@Override
	public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(
			String repositoryId, String coreNodeId){
		return contentDaoService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, coreNodeId);
	}

	@Override
	public NemakiTypeDefinition createTypeDefinition(
			String repositoryId, NemakiTypeDefinition typeDefinition) {

		// CRITICAL DEBUG: Add comprehensive debugging for property inheritance investigation
		log.info("=== TYPE CREATION DEBUG: Starting type creation for: " + typeDefinition.getId() + " ===");
		log.info("DEBUG: Repository: " + repositoryId + ", TypeId: " + typeDefinition.getId());
		log.info("DEBUG: BaseId: " + typeDefinition.getBaseId() + ", ParentId: " + typeDefinition.getParentId());

		// First, create the type definition in database
		log.info("DEBUG: Creating type definition in database");
		NemakiTypeDefinition created = contentDaoService.createTypeDefinition(repositoryId, typeDefinition);
		log.info("DEBUG: Type definition created with ID: " + created.getId());

		// CRITICAL FIX: CMIS properties are inherited automatically through TypeManager
		// We should NOT explicitly add CMIS properties to custom types
		// They will be inherited from parent when TypeManager builds the type

		String parentTypeId = created.getParentId();
		if (parentTypeId == null && created.getBaseId() != null) {
			parentTypeId = created.getBaseId().value();
			log.info("DEBUG: Using BaseId as parent: " + parentTypeId);
		}

		if (parentTypeId != null) {
			log.info("DEBUG: Type has parent: " + parentTypeId + " - CMIS properties will be inherited automatically");
			// CMIS properties are inherited automatically by TypeManager when it builds the type
			// We don't need to explicitly add them to the type's properties list
		} else {
			log.info("DEBUG: No parent type - this is a base type");
		}

		if (typeManager != null) {
			System.out.println("DEBUG: TypeManager is not null, adding new type to cache");
			log.info("DEBUG: Adding new type to TypeManager cache");

			// Debug: Check what properties the created type has
			System.out.println("DEBUG: Created NemakiTypeDefinition has " +
				(created.getProperties() != null ? created.getProperties().size() : 0) +
				" property detail IDs");
			if (created.getProperties() != null) {
				for (String propId : created.getProperties()) {
					System.out.println("  Created type property detail ID: " + propId);
				}
			}

			// CRITICAL FIX: Use addTypeDefinition to avoid clearing entire cache
			// This preserves parent type's custom properties during child type creation
			// Build TypeDefinition from NemakiTypeDefinition
			org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDef =
				typeManager.buildTypeDefinitionFromDB(repositoryId, created);
			if (typeDef != null) {
				System.out.println("DEBUG: Built TypeDefinition has " +
					typeDef.getPropertyDefinitions().size() + " properties");
				int customCount = 0;
				for (String propId : typeDef.getPropertyDefinitions().keySet()) {
					if (!propId.startsWith("cmis:")) {
						System.out.println("  Built type custom property: " + propId);
						customCount++;
					}
				}
				System.out.println("DEBUG: Built type has " + customCount + " custom properties");
				System.out.println("DEBUG: Calling typeManager.addTypeDefinition for " + created.getId());
				typeManager.addTypeDefinition(repositoryId, typeDef, false);
			} else {
				System.out.println("DEBUG: typeDef is null, not calling addTypeDefinition");
			}
		} else {
			System.out.println("WARNING: TypeManager is NULL, cannot update cache!");
		}

		log.info("=== TYPE CREATION DEBUG: Completed type creation for: " + typeDefinition.getId() + " ===");
		return created;
	}

	@Override
	public NemakiTypeDefinition updateTypeDefinition(
			String repositoryId, NemakiTypeDefinition typeDefinition) {
		return contentDaoService.updateTypeDefinition(repositoryId, typeDefinition);
	}

	@Override
	public void deleteTypeDefinition(String repositoryId, String typeId) {
		
		NemakiTypeDefinition ntd = getTypeDefinition(repositoryId, typeId);
		
		if (ntd == null) {
			log.warn("Type definition not found for deletion: " + typeId);
			return;
		}

		//Delete unnecessary property definitions with proper error handling
		List<String> detailIds = ntd.getProperties();
		if (detailIds != null && !detailIds.isEmpty()) {
			for(String detailId : detailIds){
				try {
					NemakiPropertyDefinitionDetail detail = getPropertyDefinitionDetail(repositoryId, detailId);
					if (detail == null) {
						log.warn("Property definition detail not found: " + detailId + ", skipping deletion");
						continue;
					}
					
					NemakiPropertyDefinitionCore core = getPropertyDefinitionCore(repositoryId, detail.getCoreNodeId());
					if (core == null) {
						log.warn("Property definition core not found: " + detail.getCoreNodeId() + ", skipping core deletion but deleting detail");
						contentDaoService.delete(repositoryId, detail.getId());
						continue;
					}
					
					//Delete a detail
					contentDaoService.delete(repositoryId, detail.getId());

					//Delete a core only if no other details exist
					List<NemakiPropertyDefinitionDetail> remainingDetails =
							contentDaoService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, core.getId());
					if(CollectionUtils.isEmpty(remainingDetails)){
						contentDaoService.delete(repositoryId, core.getId());
						log.debug("Deleted property definition core: " + core.getId());
					} else {
						log.debug("Property definition core " + core.getId() + " retained, " + remainingDetails.size() + " details still reference it");
					}
				} catch (Exception e) {
					log.error("Error deleting property definition detail " + detailId + " for type " + typeId, e);
					// Continue with other deletions even if one fails
				}
			}
		}

		//Delete the type definition
		try {
			contentDaoService.deleteTypeDefinition(repositoryId, ntd.getId());
			log.info("Successfully deleted type definition: " + typeId);
		} catch (Exception e) {
			log.error("Error deleting type definition: " + typeId, e);
			e.printStackTrace();
			throw e; // Re-throw since this is the main operation
		}
		
		if (typeManager != null) {
			log.info("DEBUG: Invalidating TypeManager cache after type deletion");
			typeManager.invalidateTypeCache(repositoryId);
		}
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinition(
			String repositoryId, NemakiPropertyDefinition propertyDefinition) {
		
		// CRITICAL FIX: Preserve original property ID exactly as provided
		String originalPropertyId = propertyDefinition.getPropertyId();
		
		// ENHANCED DEBUGGING: Track all property creation attempts
		log.debug("=== TCK PROPERTY CREATION DEBUG START ===");
		log.debug("Repository ID: " + repositoryId);
		log.debug("Original Property ID: " + originalPropertyId);
		log.debug("Property Type: " + propertyDefinition.getPropertyType());
		log.debug("Property LocalName: " + propertyDefinition.getLocalName());
		
		if (log.isDebugEnabled()) {
			log.debug("Creating property definition - ID: " + originalPropertyId + 
				", Type: " + propertyDefinition.getPropertyType());
		}
		
		NemakiPropertyDefinitionCore _core = new NemakiPropertyDefinitionCore(
				propertyDefinition);

		// Determine if this is a custom property (non-CMIS namespace)
		// CRITICAL FIX: Handle timestamped property IDs correctly
		// TCK creates IDs like "tck:boolean_1758180525722" which should be treated as custom
		boolean isCustomProperty = false;
		if (originalPropertyId != null && originalPropertyId.contains(":")) {
			// Extract namespace part (before the colon)
			String namespace = originalPropertyId.substring(0, originalPropertyId.indexOf(":"));
			// Anything not starting with "cmis" is custom
			isCustomProperty = !namespace.equals("cmis");
		}

		// CRITICAL DEBUG: Log to stderr for immediate visibility
		System.err.println("TCK CREATE PROPERTY DEBUG: originalPropertyId=" + originalPropertyId);
		System.err.println("TCK CREATE PROPERTY DEBUG: isCustomProperty=" + isCustomProperty);
		System.err.println("TCK CREATE PROPERTY DEBUG: propertyType=" + propertyDefinition.getPropertyType());

		log.debug("CUSTOM PROPERTY DETECTION: " + isCustomProperty);
		log.debug("Property ID contains namespace: " + (originalPropertyId != null && originalPropertyId.contains(":")));
		log.debug("Property ID (full): " + originalPropertyId);
		log.debug("Property ID starts with cmis:: " + (originalPropertyId != null && originalPropertyId.startsWith("cmis:")));
		
		String coreNodeId = "";
		
		if (isCustomProperty) {
			// CUSTOM PROPERTIES: Create dedicated cores with preserved original IDs
			System.err.println("TCK CUSTOM PROPERTY PATH: ENTERING for " + originalPropertyId);
			log.debug("=== CUSTOM PROPERTY PATH ACTIVATED ===");
			if (log.isInfoEnabled()) {
				log.info("Creating custom property: " + originalPropertyId + " (preserving exact ID)");
			}
			
			// CRITICAL: Preserve the exact property ID - no modification unless conflict
			String preservedPropertyId = originalPropertyId;
			log.debug("PRESERVED PROPERTY ID: " + preservedPropertyId);
			
			if (!isUniquePropertyIdInRepository(repositoryId, preservedPropertyId)) {
				log.debug("UNIQUENESS CHECK FAILED - checking for exact conflicts");
				// Only add timestamp for genuine same-property conflicts
				List<NemakiPropertyDefinitionCore> cores = getPropertyDefinitionCores(repositoryId);
				boolean exactConflict = false;
				if (CollectionUtils.isNotEmpty(cores)) {
					for (NemakiPropertyDefinitionCore core : cores) {
						if (preservedPropertyId.equals(core.getPropertyId())) {
							exactConflict = true;
							log.debug("EXACT CONFLICT DETECTED with existing core: " + core.getId());
							break;
						}
					}
				}
				if (exactConflict) {
					preservedPropertyId = originalPropertyId + "_" + System.currentTimeMillis();
					if (log.isWarnEnabled()) {
						log.warn("Property ID conflict resolved with timestamp: " + preservedPropertyId);
					}
					log.debug("CONFLICT RESOLVED WITH TIMESTAMP: " + preservedPropertyId);
				}
			} else {
				log.debug("UNIQUENESS CHECK PASSED - no conflicts");
			}
			
			log.debug("FINAL PRESERVED PROPERTY ID: " + preservedPropertyId);
			_core.setPropertyId(preservedPropertyId);
			log.debug("CORE PROPERTY ID SET TO: " + _core.getPropertyId());
			
			// Create new dedicated property core
			log.debug("CREATING NEW DEDICATED PROPERTY CORE");
			NemakiPropertyDefinitionCore core = contentDaoService
					.createPropertyDefinitionCore(repositoryId, _core);
			coreNodeId = core.getId();
			
			log.debug("CUSTOM PROPERTY CORE CREATED:");
			log.debug("  Core Node ID: " + coreNodeId);
			log.debug("  Core Property ID: " + core.getPropertyId());
			log.debug("  Original Property ID: " + originalPropertyId);
			
			if (log.isInfoEnabled()) {
				log.info("Custom property core created: " + coreNodeId + " (ID: " + preservedPropertyId + ")");
			}
		} else {
			// CMIS SYSTEM PROPERTIES: Standard reuse logic for CMIS properties
			log.debug("=== CMIS SYSTEM PROPERTY PATH ACTIVATED ===");
			if (log.isDebugEnabled()) {
				log.debug("Processing CMIS system property: " + originalPropertyId);
			}
			
			List<NemakiPropertyDefinitionCore> cores = getPropertyDefinitionCores(repositoryId);
			Map<String, NemakiPropertyDefinitionCore> corePropertyIds = new HashMap<String, NemakiPropertyDefinitionCore>();
			if (CollectionUtils.isNotEmpty(cores)) {
				for (NemakiPropertyDefinitionCore core : cores) {
					corePropertyIds.put(core.getPropertyId(), core);
				}
			}

			if (corePropertyIds.containsKey(originalPropertyId)) {
				// Reuse existing CMIS core
				NemakiPropertyDefinitionCore core = corePropertyIds.get(originalPropertyId);
				coreNodeId = core.getId();
				log.debug("REUSING EXISTING CMIS CORE: " + coreNodeId + " for " + originalPropertyId);
				if (log.isDebugEnabled()) {
					log.debug("Reusing existing CMIS core: " + coreNodeId + " for " + originalPropertyId);
				}
			} else {
				// Create new CMIS core
				log.debug("CREATING NEW CMIS CORE for " + originalPropertyId);
				NemakiPropertyDefinitionCore core = contentDaoService
						.createPropertyDefinitionCore(repositoryId, _core);
				coreNodeId = core.getId();
				log.debug("NEW CMIS CORE CREATED: " + coreNodeId + " with property ID: " + core.getPropertyId());
				if (log.isDebugEnabled()) {
					log.debug("Created new CMIS core: " + coreNodeId + " for " + originalPropertyId);
				}
			}
		}

		log.debug("PROCEEDING TO CREATE PROPERTY DETAIL with core: " + coreNodeId);
		
		// Create a detail
		NemakiPropertyDefinitionDetail _detail = new NemakiPropertyDefinitionDetail(
				propertyDefinition, coreNodeId);
		log.debug("DETAIL CREATED - LocalName: " + _detail.getLocalName());
		
		if (_detail.getLocalName() == null || _detail.getLocalName().trim().isEmpty()) {
			_detail.setLocalName(originalPropertyId);
			log.debug("DETAIL LocalName set to propertyId: " + originalPropertyId);
		}
		
		if (_detail.getDisplayName() == null || _detail.getDisplayName().trim().isEmpty()) {
			_detail.setDisplayName(originalPropertyId);
			log.debug("DETAIL DisplayName set to propertyId: " + originalPropertyId);
		}
		
		NemakiPropertyDefinitionDetail detail = contentDaoService
				.createPropertyDefinitionDetail(repositoryId, _detail);

		log.debug("FINAL PROPERTY DEFINITION CREATED:");
		log.debug("  Original Property ID: " + originalPropertyId);
		log.debug("  Detail ID: " + detail.getId()); 
		log.debug("  Detail LocalName: " + detail.getLocalName());
		log.debug("  Core Node ID: " + coreNodeId);
		log.debug("=== TCK PROPERTY CREATION DEBUG END ===");
		
		if (log.isInfoEnabled()) {
			log.info("Property definition created: " + originalPropertyId + 
				" (Detail ID: " + detail.getId() + ")");
		}
		
		return detail;
	}

	@Override
	public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			String repositoryId, NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
		return contentDaoService.updatePropertyDefinitionDetail(repositoryId, propertyDefinitionDetail);
	}





	private String buildUniquePropertyId(String repositoryId, String propertyId){
		if(isUniquePropertyIdInRepository(repositoryId, propertyId)){
			return propertyId;
		}else{
			return propertyId + "_" + String.valueOf(System.currentTimeMillis());
		}
	}

	private boolean isUniquePropertyIdInRepository(String repositoryId, String propertyId){
		// CRITICAL TCK FIX: Allow TCK custom properties to be reused across different type definitions
		// TCK tests like CreateAndDeleteTypeTest create types, delete them, and recreate them
		// The old logic was too restrictive and prevented property ID reuse

		// Only check against CMIS system properties for true conflicts
		List<String> systemIds = getSystemPropertyIds();

		// CRITICAL FIX: For TCK custom properties (non-CMIS namespace), allow reuse
		if (propertyId != null && propertyId.contains(":") && !propertyId.startsWith("cmis:")) {
			// This is a custom property (e.g., tck:boolean, tck:id, etc.)
			// Only check against CMIS system properties, not against existing custom properties
			System.err.println("TCK CUSTOM PROPERTY UNIQUENESS: " + propertyId + " - allowing reuse for TCK compliance");
			return !systemIds.contains(propertyId);
		}

		// For CMIS properties, use the original strict logic
		List<NemakiPropertyDefinitionCore>cores = getPropertyDefinitionCores(repositoryId);
		if(CollectionUtils.isNotEmpty(cores)){
			for(NemakiPropertyDefinitionCore core: cores){
				systemIds.add(core.getPropertyId());
			}
		}

		return !systemIds.contains(propertyId);
	}

	/**
	 * List up specification-default property ids
	 *
	 * @return
	 */
	private List<String> getSystemPropertyIds() {
		List<String> ids = new ArrayList<String>();

		Field[] fields = PropertyIds.class.getDeclaredFields();
		for (Field field : fields) {
			try {
				String cmisId = (String) (field.get(null));
				ids.add(cmisId);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
		}
		return ids;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setTypeManager(jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager) {
		this.typeManager = typeManager;
	}


}
