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
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TypeServiceImpl implements TypeService{

	private static final Log log = LogFactory.getLog(TypeServiceImpl.class);
	private ContentDaoService contentDaoService;

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
		System.err.println("=== TYPESERVICE: getTypeDefinitions called for repository: " + repositoryId + " ===");
		System.err.println("=== TYPESERVICE GET TYPE DEFINITIONS DEBUG ===");
		System.err.println("Repository ID: " + repositoryId);
		System.err.println("ContentDaoService: " + (contentDaoService != null ? "NOT NULL" : "NULL"));
		
		List<NemakiTypeDefinition> result = contentDaoService.getTypeDefinitions(repositoryId);
		
		System.err.println("Result from contentDaoService: " + (result != null ? result.size() + " types" : "null"));
		if (result != null && !result.isEmpty()) {
			System.err.println("First few types from TypeService:");
			for (int i = 0; i < Math.min(3, result.size()); i++) {
				NemakiTypeDefinition type = result.get(i);
				if (type != null) {
					System.err.println("  " + (i+1) + ". " + type.getTypeId() + " (BaseId: " + type.getBaseId() + ")");
				}
			}
		}
		
		return result;
	}

	@Override
	public NemakiPropertyDefinition getPropertyDefinition(String repositoryId, String detailNodeId) {
		// CRITICAL DEBUG: Add logging for NPE investigation
		System.out.println("TYPESERVICE DEBUG: getPropertyDefinition called with repositoryId=" + repositoryId + ", detailNodeId=" + detailNodeId);
		
		NemakiPropertyDefinitionDetail detail = getPropertyDefinitionDetail(repositoryId, detailNodeId);
		System.out.println("TYPESERVICE DEBUG: detail=" + (detail != null ? "NOT NULL (ID=" + detail.getId() + ")" : "NULL"));
		
		if (detail == null) {
			System.out.println("CRITICAL ERROR: PropertyDefinitionDetail is NULL for detailNodeId: " + detailNodeId);
			return null;
		}
		
		String coreNodeId = detail.getCoreNodeId();
		System.out.println("TYPESERVICE DEBUG: coreNodeId=" + coreNodeId);
		
		NemakiPropertyDefinitionCore core = getPropertyDefinitionCore(repositoryId, coreNodeId);
		System.out.println("TYPESERVICE DEBUG: core=" + (core != null ? "NOT NULL (PropertyId=" + core.getPropertyId() + ")" : "NULL"));
		
		if (core == null) {
			System.out.println("CRITICAL ERROR: PropertyDefinitionCore is NULL for coreNodeId: " + coreNodeId);
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
		System.err.println("=== TYPESERVICE: createTypeDefinition called for repositoryId: " + repositoryId + " ===");
		
		NemakiTypeDefinition created = contentDaoService.createTypeDefinition(repositoryId, typeDefinition);
		
		// CRITICAL FIX: Use SpringContext to get TypeManager and refresh cache after type creation
		// This avoids circular dependency since TypeManager depends on TypeService
		try {
			jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
				(jp.aegif.nemaki.cmis.aspect.type.TypeManager) jp.aegif.nemaki.util.spring.SpringContext.getBean("TypeManager");
			
			if (typeManager != null) {
				System.err.println("=== TYPESERVICE: Successfully retrieved TypeManager via SpringContext ===");
				System.err.println("=== TYPESERVICE: Refreshing TypeManager cache after type creation ===");
				typeManager.refreshTypes();
				System.err.println("=== TYPESERVICE: TypeManager cache refresh completed ===");
			} else {
				System.err.println("=== TYPESERVICE WARNING: TypeManager is null from SpringContext ===");
			}
		} catch (Exception e) {
			System.err.println("=== TYPESERVICE ERROR: Failed to refresh TypeManager cache: " + e.getMessage() + " ===");
			e.printStackTrace();
			// Don't throw exception - type creation succeeded, cache refresh is optimization
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
		System.err.println("=== TYPESERVICE: deleteTypeDefinition called ===");
		System.err.println("Repository ID: " + repositoryId);
		System.err.println("Type ID: " + typeId);
		
		NemakiTypeDefinition ntd = getTypeDefinition(repositoryId, typeId);
		System.err.println("Type definition lookup result: " + (ntd != null ? "FOUND (ID=" + ntd.getId() + ")" : "NOT FOUND"));
		
		if (ntd == null) {
			System.err.println("WARNING: Type definition not found for deletion: " + typeId);
			log.warn("Type definition not found for deletion: " + typeId);
			return;
		}

		System.err.println("=== DELETING PROPERTY DEFINITIONS ===");
		//Delete unnecessary property definitions with proper error handling
		List<String> detailIds = ntd.getProperties();
		System.err.println("Property detail IDs to delete: " + (detailIds != null ? detailIds.size() + " properties" : "null"));
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

		System.err.println("=== DELETING TYPE DEFINITION DOCUMENT ===");
		System.err.println("About to call contentDaoService.deleteTypeDefinition with:");
		System.err.println("  Repository ID: " + repositoryId);
		System.err.println("  Document ID: " + ntd.getId());
		
		//Delete the type definition
		try {
			contentDaoService.deleteTypeDefinition(repositoryId, ntd.getId());
			System.err.println("SUCCESS: contentDaoService.deleteTypeDefinition completed");
			log.info("Successfully deleted type definition: " + typeId);
		} catch (Exception e) {
			System.err.println("CRITICAL ERROR: contentDaoService.deleteTypeDefinition failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			log.error("Error deleting type definition: " + typeId, e);
			e.printStackTrace();
			throw e; // Re-throw since this is the main operation
		}
		
		System.err.println("=== TYPESERVICE deleteTypeDefinition COMPLETED ===");
	}

	@Override
	public NemakiPropertyDefinitionDetail createPropertyDefinition(
			String repositoryId, NemakiPropertyDefinition propertyDefinition) {
		
		// CRITICAL FIX: Preserve original property ID exactly as provided
		String originalPropertyId = propertyDefinition.getPropertyId();
		
		// CRITICAL DEBUG: Add visible logging for TCK property creation
		System.err.println("=== TYPESERVICE.createPropertyDefinition CALLED ===");
		System.err.println("Repository: " + repositoryId);
		System.err.println("Original Property ID: " + originalPropertyId);
		System.err.println("Property Type: " + propertyDefinition.getPropertyType());
		System.err.println("ThreadID: " + Thread.currentThread().getId());
		System.err.println("Timestamp: " + System.currentTimeMillis());
		
		if (log.isDebugEnabled()) {
			log.debug("Creating property definition - ID: " + originalPropertyId + 
				", Type: " + propertyDefinition.getPropertyType());
		}
		
		NemakiPropertyDefinitionCore _core = new NemakiPropertyDefinitionCore(
				propertyDefinition);

		// Determine if this is a custom property (non-CMIS namespace)
		boolean isCustomProperty = originalPropertyId != null && 
			(originalPropertyId.startsWith("tck:") || 
			 (!originalPropertyId.startsWith("cmis:") && originalPropertyId.contains(":")));
		
		String coreNodeId = "";
		
		if (isCustomProperty) {
			// CUSTOM PROPERTIES: Create dedicated cores with preserved original IDs
			if (log.isInfoEnabled()) {
				log.info("Creating custom property: " + originalPropertyId + " (preserving exact ID)");
			}
			
			// CRITICAL: Preserve the exact property ID - no modification unless conflict
			String preservedPropertyId = originalPropertyId;
			if (!isUniquePropertyIdInRepository(repositoryId, preservedPropertyId)) {
				// Only add timestamp for genuine same-property conflicts
				List<NemakiPropertyDefinitionCore> cores = getPropertyDefinitionCores(repositoryId);
				boolean exactConflict = false;
				if (CollectionUtils.isNotEmpty(cores)) {
					for (NemakiPropertyDefinitionCore core : cores) {
						if (preservedPropertyId.equals(core.getPropertyId())) {
							exactConflict = true;
							break;
						}
					}
				}
				if (exactConflict) {
					preservedPropertyId = originalPropertyId + "_" + System.currentTimeMillis();
					if (log.isWarnEnabled()) {
						log.warn("Property ID conflict resolved with timestamp: " + preservedPropertyId);
					}
				}
			}
			
			_core.setPropertyId(preservedPropertyId);
			
			// Create new dedicated property core
			NemakiPropertyDefinitionCore core = contentDaoService
					.createPropertyDefinitionCore(repositoryId, _core);
			coreNodeId = core.getId();
			
			if (log.isInfoEnabled()) {
				log.info("Custom property core created: " + coreNodeId + " (ID: " + preservedPropertyId + ")");
			}
		} else {
			// CMIS SYSTEM PROPERTIES: Standard reuse logic for CMIS properties
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
				if (log.isDebugEnabled()) {
					log.debug("Reusing existing CMIS core: " + coreNodeId + " for " + originalPropertyId);
				}
			} else {
				// Create new CMIS core
				NemakiPropertyDefinitionCore core = contentDaoService
						.createPropertyDefinitionCore(repositoryId, _core);
				coreNodeId = core.getId();
				if (log.isDebugEnabled()) {
					log.debug("Created new CMIS core: " + coreNodeId + " for " + originalPropertyId);
				}
			}
		}

		// Create a detail
		NemakiPropertyDefinitionDetail _detail = new NemakiPropertyDefinitionDetail(
				propertyDefinition, coreNodeId);
		NemakiPropertyDefinitionDetail detail = contentDaoService
				.createPropertyDefinitionDetail(repositoryId, _detail);

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


}
