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
		
		// CRITICAL FIX: Inherit property definitions from parent type before creation
		// ROOT CAUSE IDENTIFIED: TCK creates new types but they don't inherit CMIS property definitions
		// This causes "Property definition: cmis:name" failures in TCK compliance checks
		
		String parentTypeId = typeDefinition.getParentId();
		if (parentTypeId == null && typeDefinition.getBaseId() != null) {
			parentTypeId = typeDefinition.getBaseId().value();
			log.info("DEBUG: Using BaseId as parent: " + parentTypeId);
		}
		
		if (parentTypeId != null) {
			log.info("DEBUG: Starting property inheritance from parent: " + parentTypeId);
			
			// Get TypeManager to access parent type definitions
			try {
				jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
					(jp.aegif.nemaki.cmis.aspect.type.TypeManager) jp.aegif.nemaki.util.spring.SpringContext.getBean("TypeManager");
				
				log.info("DEBUG: TypeManager retrieved: " + (typeManager != null));
				
				if (typeManager != null) {
					
					// Get parent type definition 
					TypeDefinition parentType = typeManager.getTypeDefinition(repositoryId, parentTypeId);
					
					log.info("DEBUG: Parent type definition retrieved: " + (parentType != null));
					if (parentType != null) {
						log.info("DEBUG: Parent type ID: " + parentType.getId() + ", Properties count: " + 
							(parentType.getPropertyDefinitions() != null ? parentType.getPropertyDefinitions().size() : 0));
					}
					
					if (parentType != null) {
						
						// CRITICAL FIX: Create PropertyDefinitionCore entries for inherited CMIS properties
						Map<String, PropertyDefinition<?>> parentProperties = parentType.getPropertyDefinitions();
						
						if (parentProperties != null && !parentProperties.isEmpty()) {
							log.info("DEBUG: Processing " + parentProperties.size() + " parent properties");
							
							int inheritedCount = 0;
							int skippedExisting = 0;
							int errorCount = 0;
							
							for (Map.Entry<String, PropertyDefinition<?>> entry : parentProperties.entrySet()) {
								PropertyDefinition<?> parentProp = entry.getValue();
								String propertyId = parentProp.getId();
								
								log.debug("DEBUG: Processing property: " + propertyId + " (Type: " + parentProp.getPropertyType() + ")");
								
								// Only inherit CMIS system properties, not custom properties
								if (propertyId != null && propertyId.startsWith("cmis:")) {
									
									try {
										// Check if property definition already exists
										NemakiPropertyDefinitionCore existingCore = getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
										
										if (existingCore == null) {
											log.info("DEBUG: Creating new PropertyDefinitionCore for: " + propertyId);
											
											// Create NemakiPropertyDefinition from CMIS PropertyDefinition
											NemakiPropertyDefinition nemakiProp = new NemakiPropertyDefinition();
											nemakiProp.setPropertyId(propertyId);
											nemakiProp.setPropertyType(parentProp.getPropertyType());
											nemakiProp.setQueryName(parentProp.getQueryName());
											nemakiProp.setCardinality(parentProp.getCardinality());
											nemakiProp.setLocalName(parentProp.getLocalName());
											nemakiProp.setDisplayName(parentProp.getDisplayName());
											nemakiProp.setDescription(parentProp.getDescription());
											
											// CRITICAL FIX: Set inherited flag for properties inherited from parent types
											nemakiProp.setInherited(true);
											
											// Create PropertyDefinitionCore
											NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore(nemakiProp);
											
											// CRITICAL FIX: Set inherited flag on core as well for consistency
											core.setInherited(true);
											NemakiPropertyDefinitionCore createdCore = contentDaoService.createPropertyDefinitionCore(repositoryId, core);
											log.info("DEBUG: PropertyDefinitionCore created successfully: " + propertyId + " -> " + createdCore.getId());
											inheritedCount++;
										} else {
											log.debug("DEBUG: PropertyDefinitionCore already exists for: " + propertyId + " -> " + existingCore.getId());
											skippedExisting++;
										}
									} catch (Exception e) {
										log.error("ERROR: Failed to create PropertyDefinitionCore for " + propertyId, e);
										errorCount++;
										// Continue with other properties - don't fail entire type creation
									}
								} else {
									log.debug("DEBUG: Skipping non-CMIS property: " + propertyId);
								}
							}
							
							log.info("DEBUG: Property inheritance completed. Created: " + inheritedCount + 
									", Skipped existing: " + skippedExisting + ", Errors: " + errorCount);
						} else {
							log.warn("DEBUG: Parent type has no properties to inherit");
						}
					}
				} else {
					log.error("ERROR: TypeManager is null - cannot inherit properties");
				}
			} catch (Exception e) {
				log.error("ERROR: Exception during property inheritance", e);
				e.printStackTrace();
				// Don't fail type creation - inheritance is enhancement
			}
		} else {
			log.info("DEBUG: No parent type for inheritance - creating base type");
		}
		
		// Proceed with original type creation
		log.info("DEBUG: Creating type definition in database");
		NemakiTypeDefinition created = contentDaoService.createTypeDefinition(repositoryId, typeDefinition);
		log.info("DEBUG: Type definition created with ID: " + created.getId());

		// CRITICAL FIX: Use SpringContext to get TypeManager and refresh cache after type creation
		// This avoids circular dependency since TypeManager depends on TypeService
		try {
			jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager = 
				(jp.aegif.nemaki.cmis.aspect.type.TypeManager) jp.aegif.nemaki.util.spring.SpringContext.getBean("TypeManager");
			
			if (typeManager != null) {
				log.info("DEBUG: Refreshing TypeManager cache after type creation");
				typeManager.refreshTypes();
			}
		} catch (Exception e) {
			log.error("ERROR: Exception during TypeManager cache refresh", e);
			e.printStackTrace();
			// Don't throw exception - type creation succeeded, cache refresh is optimization
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
			(originalPropertyId.startsWith("tck:") || 
			 (!originalPropertyId.startsWith("cmis:") && originalPropertyId.contains(":")));
		
		log.debug("CUSTOM PROPERTY DETECTION: " + isCustomProperty);
		log.debug("Property ID starts with tck:: " + (originalPropertyId != null && originalPropertyId.startsWith("tck:")));
		log.debug("Property ID contains :: " + (originalPropertyId != null && originalPropertyId.contains(":")));
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


}
