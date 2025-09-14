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
		
		// CRITICAL FIX: The type inherits properties from its parent automatically via TypeManagerImpl
		// No need to create PropertyDefinitionCore records for inherited CMIS properties

		if (log.isDebugEnabled()) {
			log.debug("Creating type definition: " + typeDefinition.getId() +
				" (Parent: " + typeDefinition.getParentId() + ", Base: " + typeDefinition.getBaseId() + ")");
		}
		NemakiTypeDefinition created = contentDaoService.createTypeDefinition(repositoryId, typeDefinition);

		if (typeManager != null) {
			// CRITICAL FIX: Force immediate cache invalidation before refresh
			// This ensures the next getTypeDefinition() call rebuilds from database
			typeManager.invalidateTypeCache(repositoryId);
			typeManager.refreshTypes();
			if (log.isDebugEnabled()) {
				log.debug("Type cache invalidated and refreshed after creating type: " + created.getId());
			}
		}
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
		boolean isCustomProperty = originalPropertyId != null && 
			originalPropertyId.contains(":") && !originalPropertyId.startsWith("cmis:");
		
		log.debug("CUSTOM PROPERTY DETECTION: " + isCustomProperty);
		log.debug("Property ID contains namespace: " + (originalPropertyId != null && originalPropertyId.contains(":")));
		log.debug("Property ID (full): " + originalPropertyId);
		log.debug("Property ID starts with cmis:: " + (originalPropertyId != null && originalPropertyId.startsWith("cmis:")));
		
		String coreNodeId = "";
		
		if (isCustomProperty) {
			// CUSTOM PROPERTIES: Create dedicated cores with preserved original IDs
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
		//propertyId uniqueness
		List<String> list = getSystemPropertyIds();
		List<NemakiPropertyDefinitionCore>cores = getPropertyDefinitionCores(repositoryId);
		if(CollectionUtils.isNotEmpty(cores)){
			for(NemakiPropertyDefinitionCore core: cores){
				list.add(core.getPropertyId());
			}
		}

		return !list.contains(propertyId);
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
