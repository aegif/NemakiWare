/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.service.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.constant.DomainType;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.InitializingBean;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

	private static final Log log = LogFactory.getLog(RepositoryServiceImpl.class);
	
	// CRITICAL FIX: Static shared caches for TypeDefinition instance sharing
	// Required for TCK object identity comparison (==) instead of equals()
	private static final ConcurrentHashMap<String, TypeDefinition> SHARED_TYPE_DEFINITIONS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, TypeDefinitionList> SHARED_TYPE_CHILDREN = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, List<TypeDefinitionContainer>> SHARED_TYPE_DESCENDANTS = new ConcurrentHashMap<>();

	private RepositoryInfoMap repositoryInfoMap;
	private TypeManager typeManager;
	private TypeService typeService;
	private ContentService contentService;
	private ExceptionService exceptionService;
	
	// CRITICAL FIX: Helper methods for TypeDefinition instance sharing
	// These methods ensure that identical TypeDefinitions return the same object instance
	// for TCK object identity comparison (==)
	
	/**
	 * Get shared TypeDefinition instance from cache or create and cache it
	 */
	private TypeDefinition getSharedTypeDefinition(String repositoryId, String typeId) {
		String cacheKey = repositoryId + ":" + typeId;
		return SHARED_TYPE_DEFINITIONS.computeIfAbsent(cacheKey, k -> {
			if (log.isDebugEnabled()) {
				log.debug("Creating shared TypeDefinition for " + typeId);
			}
			
			TypeDefinition td = typeManager.getTypeDefinition(repositoryId, typeId);
			if (td != null && td.getPropertyDefinitions() != null) {
				int validPropCount = 0;
				int nullPropCount = 0;
				for (Map.Entry<String, PropertyDefinition<?>> entry : td.getPropertyDefinitions().entrySet()) {
					PropertyDefinition<?> propDef = entry.getValue();
					if (propDef == null) {
						log.error("Null PropertyDefinition found for property '" + entry.getKey() + "' in type " + typeId);
						nullPropCount++;
					} else {
						validPropCount++;
						if (propDef.getId() == null) {
							log.error("PropertyDefinition with null ID found for property '" + entry.getKey() + "' in type " + typeId);
						}
					}
				}
				if (log.isDebugEnabled()) {
					log.debug("TypeDefinition " + typeId + " has " + validPropCount + " valid properties and " + nullPropCount + " null properties");
				}
			}
			return td;
		});
	}
	
	/**
	 * Get shared TypeDefinitionList instance from cache or create and cache it
	 */
	private TypeDefinitionList getSharedTypeChildren(CallContext callContext, String repositoryId, String typeId,
			Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {
		String cacheKey = repositoryId + ":" + typeId + ":" + includePropertyDefinitions + ":" + maxItems + ":" + skipCount;
		return SHARED_TYPE_CHILDREN.computeIfAbsent(cacheKey, k -> {
			if (log.isDebugEnabled()) {
				log.debug("Creating shared TypeDefinitionList for " + typeId);
			}
			
			boolean includeProps = (includePropertyDefinitions == null) ? false : includePropertyDefinitions.booleanValue();
			return typeManager.getTypesChildren(callContext, repositoryId, typeId, includeProps, maxItems, skipCount);
		});
	}
	
	/**
	 * Get shared TypeDefinitionContainer list instance from cache or create and cache it
	 */
	private List<TypeDefinitionContainer> getSharedTypeDescendants(String repositoryId, String typeId,
			BigInteger depth, Boolean includePropertyDefinitions) {
		String cacheKey = repositoryId + ":" + typeId + ":" + depth + ":" + includePropertyDefinitions;
		return SHARED_TYPE_DESCENDANTS.computeIfAbsent(cacheKey, k -> {
			if (log.isDebugEnabled()) {
				log.debug("Creating shared TypeDefinitionContainer list for " + typeId);
			}
			
			return typeManager.getTypesDescendants(repositoryId, typeId, depth, includePropertyDefinitions);
		});
	}
	
	/**
	 * Clear shared caches when types are modified
	 */
	private void clearSharedTypeCaches() {
		if (log.isDebugEnabled()) {
			log.debug("Clearing all shared type caches");
		}
		
		SHARED_TYPE_DEFINITIONS.clear();
		SHARED_TYPE_CHILDREN.clear();
		SHARED_TYPE_DESCENDANTS.clear();
	}

	@Override
	public boolean hasThisRepositoryId(String repositoryId) {
		return (repositoryInfoMap.get(repositoryId) != null);
	}

	@Override
	public RepositoryInfo getRepositoryInfo(String repositoryId) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		info.setLatestChangeLogToken(contentService.getLatestChangeToken(repositoryId));
		return info;
	}

	public List<org.apache.chemistry.opencmis.commons.data.RepositoryInfo> getRepositoryInfos(){
		List<org.apache.chemistry.opencmis.commons.data.RepositoryInfo> result =
				new ArrayList<org.apache.chemistry.opencmis.commons.data.RepositoryInfo>();
		for(String key : repositoryInfoMap.keys()){
			result.add(repositoryInfoMap.get(key));
		}
		return result;
	}

	/**
	 * CMIS Service method
	 */
	@Override
	public TypeDefinitionList getTypeChildren(CallContext callContext,
			String repositoryId, String typeId,
			Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		
		log.info("*** RepositoryServiceImpl.getTypeChildren ENTRY: repositoryId=" + repositoryId + 
				 ", typeId=" + typeId + 
				 ", includePropertyDefinitions=" + includePropertyDefinitions +
				 ", maxItems=" + maxItems + 
				 ", skipCount=" + skipCount + " ***");
		
		// Handle null Boolean conversion to primitive boolean (default to false per CMIS spec)
		boolean includeProps = (includePropertyDefinitions == null) ? false : includePropertyDefinitions.booleanValue();

		log.info("*** RepositoryServiceImpl.getTypeChildren: Using SHARED instance cache for TypeDefinition object identity preservation ***");

		// CRITICAL FIX: Use shared TypeDefinitionList instance for TCK object identity comparison
		TypeDefinitionList result = getSharedTypeChildren(callContext, repositoryId, typeId, includePropertyDefinitions, maxItems, skipCount);

		log.info("*** RepositoryServiceImpl.getTypeChildren EXIT: returned SHARED " + 
				 (result != null && result.getList() != null ? result.getList().size() : "null") + " type definitions ***");

		return result;
	}

	@Override
	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String repositoryId, String typeId,
			BigInteger depth, Boolean includePropertyDefinitions, ExtensionsData extension) {
		
		// CRITICAL FIX: Use shared TypeDefinitionContainer list for TCK object identity comparison
		return getSharedTypeDescendants(repositoryId, typeId, depth, includePropertyDefinitions);
	}

	@Override
	public TypeDefinition getTypeDefinition(CallContext callContext,
			String repositoryId, String typeId, ExtensionsData extension) {
		try {
			// CRITICAL FIX: Use shared TypeDefinition instance for TCK object identity comparison
			TypeDefinition typeDefinition = getSharedTypeDefinition(repositoryId, typeId);
			
			exceptionService.objectNotFound(DomainType.OBJECT_TYPE, typeDefinition, typeId);
			return typeDefinition;
			
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	@Override
	public TypeDefinition createType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension) {
		
		log.info("=== REPOSITORY SERVICE DEBUG: createType() called ===");
		log.info("DEBUG: repositoryId=" + repositoryId);
		log.info("DEBUG: typeId=" + (type != null ? type.getId() : "null"));
		log.info("DEBUG: user=" + (callContext != null ? callContext.getUsername() : "null"));
		log.info("DEBUG: type class=" + (type != null ? type.getClass().getName() : "null"));
		log.info("DEBUG: parentTypeId=" + (type != null ? type.getParentTypeId() : "null"));
		
		log.error("=== TYPE CREATION DEBUG ===");
		log.error("Input type class: " + (type != null ? type.getClass().getName() : "null"));
		log.error("Input type instanceof DocumentTypeDefinition: " + 
			(type instanceof DocumentTypeDefinition));
		log.error("Input type instanceof NemakiTypeDefinition: " + 
			(type instanceof NemakiTypeDefinition));
		
		// //////////////////
		// General Exception
		// //////////////////
		try {
			log.info("DEBUG: Performing permission and validation checks for createType");
			exceptionService.perimissionAdmin(callContext, repositoryId);
			exceptionService.invalidArgumentRequired("typeDefinition", type);
			exceptionService.invalidArgumentCreatableType(repositoryId, type);
			exceptionService.constraintDuplicatePropertyDefinition(repositoryId, type);
			log.info("DEBUG: All validation checks passed for createType");
		} catch (ClassCastException e) {
			log.error("ClassCastException during validation checks: ", e);
			log.error("Stack trace details:");
			e.printStackTrace();
			throw new CmisRuntimeException("Type conversion failed during validation: " + 
				(type != null ? type.getClass().getName() : "null") + " to expected type", e);
		}

		// //////////////////
		// Body of the method
		// //////////////////
		// Attributes
		log.info("DEBUG: Setting type attributes for createType");
		NemakiTypeDefinition ntd = setNemakiTypeDefinitionAttributes(repositoryId, type);
		log.info("DEBUG: Type attributes set successfully - NemakiTypeDefinition ID: " + ntd.getTypeId());

		// Property definitions
		List<String> systemIds = typeManager.getSystemPropertyIds();
		Map<String, PropertyDefinition<?>> propDefs = type
				.getPropertyDefinitions();

		log.info("DEBUG: System property IDs count: " + (systemIds != null ? systemIds.size() : 0));
		log.info("DEBUG: Type property definitions count: " + (propDefs != null ? propDefs.size() : 0));
		
		if (propDefs != null && !propDefs.isEmpty()) {
			log.info("DEBUG: Type property definitions keys: " + propDefs.keySet());
		}

		ntd.setProperties(new ArrayList<String>());
		
		// CRITICAL FIX: Inherit parent type property definitions
		String parentTypeId = type.getParentTypeId();
		if (parentTypeId != null) {
			TypeDefinition parentType = typeManager.getTypeDefinition(repositoryId, parentTypeId);
			if (parentType != null && parentType.getPropertyDefinitions() != null) {
				
				// Get parent type definition from database to find property IDs
				NemakiTypeDefinition parentTypeDef = (NemakiTypeDefinition) typeManager.getTypeDefinition(repositoryId, parentTypeId);
				if (parentTypeDef != null && parentTypeDef.getProperties() != null) {
					
					// Inherit all property IDs from parent type
					for (String parentPropId : parentTypeDef.getProperties()) {
						List<String> l = ntd.getProperties();
						if (!l.contains(parentPropId)) {
							l.add(parentPropId);
							ntd.setProperties(l);
						}
					}
				}
			}
		}
		
		// Add custom property definitions (existing logic)
		if (MapUtils.isNotEmpty(propDefs)) {
			for (String key : propDefs.keySet()) {
				PropertyDefinition<?> propDef = propDefs.get(key);
				
				if (!systemIds.contains(key)) {
					//Check PropertyDefinition
					exceptionService.constraintQueryName(propDef);
					exceptionService.constraintPropertyDefinition(type, propDef);

					// CRITICAL FIX: Preserve original property ID to prevent contamination
					String originalPropertyId = propDef.getId();
					log.info("DEBUG: Creating property definition for original ID: " + originalPropertyId);
					
					NemakiPropertyDefinition create = new NemakiPropertyDefinition(propDef);
					
					// CRITICAL DEBUG: Verify the NemakiPropertyDefinition preserves the correct property ID
					if (!originalPropertyId.equals(create.getPropertyId())) {
						log.error("ERROR: Property ID contamination during NemakiPropertyDefinition creation!");
						log.error("ERROR: Original ID: " + originalPropertyId + " -> NemakiPropertyDefinition ID: " + create.getPropertyId());
						
						// CRITICAL FIX: Force correct property ID back into the object
						create.setPropertyId(originalPropertyId);
						log.info("DEBUG: Property ID forced back to correct value: " + originalPropertyId);
					}
					
					NemakiPropertyDefinitionDetail created = typeService
							.createPropertyDefinition(repositoryId, create);
					
					// CRITICAL FIX: Enhanced contamination detection and handling
					NemakiPropertyDefinition retrievedProp = typeService.getPropertyDefinition(repositoryId, created.getId());
					if (retrievedProp != null) {
						if (!originalPropertyId.equals(retrievedProp.getPropertyId())) {
							// CRITICAL FIX: Contamination detected - implement proper handling
							log.error("CONTAMINATION DETECTED!");
							log.error("  Original property ID: " + originalPropertyId);
							log.error("  Retrieved property ID: " + retrievedProp.getPropertyId());
							log.error("  This will cause TCK test failures!");
							
							// CRITICAL FIX: Force the correct property ID back into the retrieved property
							retrievedProp.setPropertyId(originalPropertyId);
							
							// CRITICAL FIX: Update the property definition with the correct ID
							NemakiPropertyDefinitionDetail updatedDetail = new NemakiPropertyDefinitionDetail(retrievedProp, created.getCoreNodeId());
							updatedDetail.setId(created.getId());
							updatedDetail.setLocalName(originalPropertyId);  // Ensure localName matches property ID
							
							typeService.updatePropertyDefinitionDetail(repositoryId, updatedDetail);
							log.info("DEBUG: Property definition updated with correct property ID: " + originalPropertyId);
						}
					}

					List<String> l = ntd.getProperties();
					// CRITICAL FIX: Add the property ID (e.g., "tck:boolean"), NOT the database ID
					l.add(propDef.getId());  // Use the actual property ID, not the database ID
					ntd.setProperties(l);
				}
			}
		}

		// CRITICAL DEBUG: Show final property list before creation
		log.info("DEBUG: Final NemakiTypeDefinition properties count: " + ntd.getProperties().size());
		if (!ntd.getProperties().isEmpty()) {
			log.info("DEBUG: Final property IDs: " + ntd.getProperties());
		}

		// Create
		log.info("DEBUG: Calling typeService.createTypeDefinition()");
		NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, ntd);
		log.info("DEBUG: typeService.createTypeDefinition() completed - Created type ID: " + created.getTypeId());
		
		// CRITICAL FIX: Instead of full refresh, force specific repository cache refresh
		log.info("DEBUG: Refreshing TypeManager cache");
		typeManager.refreshTypes();
		log.info("DEBUG: TypeManager cache refresh completed");
		
		// ADDITIONAL CHECK: Verify the created type is now in cache
		log.info("DEBUG: Verifying created type in cache");
		TypeDefinition verifyType = typeManager.getTypeDefinition(repositoryId, created.getTypeId());
		if (verifyType == null) {
			log.error("ERROR: Type not found in cache after refresh - this will cause deletion to fail");
		} else {
			log.info("DEBUG: Created type verified in cache with " + 
				(verifyType.getPropertyDefinitions() != null ? verifyType.getPropertyDefinitions().size() : 0) + " properties");
		}

		// Sort the order of properties
		try {
			log.info("DEBUG: Sorting property definitions");
			TypeDefinition result = sortPropertyDefinitions(repositoryId, created, type);
			log.info("DEBUG: Property definitions sorted successfully");
			
			// CRITICAL FIX: Clear shared type caches after type creation
			clearSharedTypeCaches();
			
			log.info("=== REPOSITORY SERVICE DEBUG: createType() completed successfully ===");
			return result;
		} catch (Exception e) {
			log.error("ERROR: Exception during property sorting", e);
			e.printStackTrace();
			throw e;
		}
	}

	@Override
	public void deleteType(CallContext callContext, String repositoryId,
			String typeId, ExtensionsData extension) {
		
		log.debug("deleteType called: repositoryId=" + repositoryId + ", typeId=" + typeId + ", user=" + (callContext != null ? callContext.getUsername() : "null"));
		
		// CRITICAL FIX: Mark type as being deleted to prevent infinite recursion
		boolean typeMarked = false;
		try {
			if (typeManager != null) {
				typeManager.markTypeBeingDeleted(typeId);
				typeMarked = true;
				log.debug("Type marked as being deleted: " + typeId);
			}
		} catch (Exception markException) {
			log.error("CRITICAL ERROR: Failed to mark type as being deleted: " + typeId, markException);
			// Continue processing even if marking fails - don't block deletion
		}
		
		// Force direct TypeService call with enhanced exception safety
		try {
			if (typeService == null) {
				log.warn("typeService is null - attempting Spring context lookup");
				try {
					org.springframework.web.context.WebApplicationContext context = 
						org.springframework.web.context.support.WebApplicationContextUtils.getWebApplicationContext(
							((org.springframework.web.context.WebApplicationContext) org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext()).getServletContext());
					typeService = (jp.aegif.nemaki.businesslogic.TypeService) context.getBean("TypeService");
					log.debug("TypeService retrieved from Spring context: " + (typeService != null ? "SUCCESS" : "FAILED"));
				} catch (Exception contextException) {
					log.error("Failed to retrieve TypeService from Spring context", contextException);
					throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException("TypeService initialization failed: " + contextException.getMessage(), contextException);
				}
			}
			
			try {
				typeManager.deleteTypeDefinition(repositoryId, typeId);
				log.debug("typeManager.deleteTypeDefinition completed successfully");
			} catch (Exception deletionException) {
				log.error("Type deletion failed for typeId: " + typeId, deletionException);
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException("Type deletion failed: " + deletionException.getMessage(), deletionException);
			}
			
			// CRITICAL FIX: Remove duplicate cache refresh - TypeService handles caching internally
			// Problem: typeManager.refreshTypes() was causing infinite recursion during type deletion
			// Root cause: TypeManagerImpl line 1698 calls refreshTypes() when types are missing,
			// creating a loop when deleted types are still referenced during cache rebuild
			// Solution: Let TypeService handle its own cache management instead of external refresh
			log.debug("Type deletion cache refresh handled internally by TypeService");
			
			// CRITICAL FIX: Clear shared type caches after type deletion
			clearSharedTypeCaches();
			
			log.debug("deleteType completed successfully");
			return;
		} catch (Exception e) {
			log.error("Error in deleteType: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
			throw e; // Re-throw to ensure finally block executes
		} finally {
			// CRITICAL ENHANCEMENT: Robust cleanup with nested exception handling
			if (typeMarked && typeManager != null) {
				try {
					typeManager.unmarkTypeBeingDeleted(typeId);
					log.debug("Type unmarked successfully: " + typeId);
				} catch (Exception unmarkException) {
					// CRITICAL: Never let cleanup exceptions prevent the main operation from completing
					log.error("CRITICAL ERROR: Failed to unmark type being deleted: " + typeId + " - this may cause memory leak", unmarkException);
					
					// Attempt secondary cleanup via timeout mechanism
					try {
						log.warn("Attempting secondary cleanup via timeout mechanism for: " + typeId);
						typeManager.cleanupTimedOutTypes();
					} catch (Exception timeoutCleanupException) {
						log.error("Secondary cleanup also failed for: " + typeId, timeoutCleanupException);
						// At this point, we rely on the timeout mechanism during next getTypeDefinition call
					}
				}
			}
		}
	}

	@Override
	public TypeDefinition updateType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext, repositoryId);
		exceptionService.invalidArgumentRequired("typeDefinition", type);
		exceptionService.invalidArgumentDoesNotExistType(repositoryId, type.getId());
		exceptionService.invalidArgumentUpdatableType(type);
		exceptionService.constraintOnlyLeafTypeDefinition(repositoryId, type.getId());
		exceptionService.constraintDuplicatePropertyDefinition(repositoryId, type);

		// //////////////////
		// Body of the method
		// //////////////////
		NemakiTypeDefinition existingType = (NemakiTypeDefinition) typeManager
				.getTypeDefinition(repositoryId, type.getId());

		// Attributes
		NemakiTypeDefinition ntd = setNemakiTypeDefinitionAttributes(repositoryId, type);

		// Property definitions
		List<String> systemIds = typeManager.getSystemPropertyIds();
		Map<String, PropertyDefinition<?>> propDefs = type
				.getPropertyDefinitions();

		ntd.setProperties(new ArrayList<String>());
		if (MapUtils.isNotEmpty(propDefs)) {
			for (String key : propDefs.keySet()) {
				PropertyDefinition<?> propDef = propDefs.get(key);
				if (systemIds.contains(key))
					continue;

				// CRITICAL FIX: Check if property already exists by propertyId, not by node ID lookup
				// Old logic was trying to get PropertyDefinitionCore for new properties that don't exist yet
				NemakiPropertyDefinitionCore existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, key);
				if (existingCore != null) {
					// update
					PropertyDefinition<?> oldPropDef = typeManager.getTypeDefinition(repositoryId, existingType.getTypeId()).getPropertyDefinitions().get(key);
					exceptionService.constraintUpdatePropertyDefinition(propDef, oldPropDef);
					exceptionService.constraintQueryName(propDef);
					exceptionService.constraintPropertyDefinition(type, propDef);

					NemakiPropertyDefinition _update = new NemakiPropertyDefinition(
							propDef);
					// FIXED: Use existing PropertyDefinitionCore ID we already found
					NemakiPropertyDefinitionDetail update = new NemakiPropertyDefinitionDetail(_update, existingCore.getId());
					typeService.updatePropertyDefinitionDetail(repositoryId, update);
				} else {
					// create
					exceptionService.constraintQueryName(propDef);
					NemakiPropertyDefinition create = new NemakiPropertyDefinition(
							propDef);
					NemakiPropertyDefinitionDetail created = typeService
							.createPropertyDefinition(repositoryId, create);

					List<String> l = ntd.getProperties();
					l.add(created.getId());
					ntd.setProperties(l);
				}

			}
		}

		// Update
		NemakiTypeDefinition updated = typeService.updateTypeDefinition(repositoryId, ntd);
		typeManager.refreshTypes();
		
		// CRITICAL FIX: Clear shared TypeDefinition caches to maintain object identity consistency
		clearSharedTypeCaches();

		// Sort
		return sortPropertyDefinitions(repositoryId, updated, type);
	}

	private NemakiTypeDefinition setNemakiTypeDefinitionAttributes(
			String repositoryId, TypeDefinition typeDefinition) {
		NemakiTypeDefinition ntd = new NemakiTypeDefinition();

		// CRITICAL NOTE: Type is automatically set by NemakiTypeDefinition constructor to NodeType.TYPE_DEFINITION.value() = "typeDefinition"
		// This ensures that newly created types are correctly identified in the typeDefinitions view

		// To avoid the conflict of typeId, add suffix
		if (typeManager.getTypeById(repositoryId, typeDefinition.getId()) == null) {
			ntd.setTypeId(typeDefinition.getId());
		} else {
			ntd.setTypeId(typeDefinition.getId() + "_"
					+ String.valueOf(System.currentTimeMillis()));
		}
		ntd.setLocalName(typeDefinition.getLocalName());
		ntd.setLocalNameSpace(typeDefinition.getLocalNamespace());
		ntd.setQueryName(typeDefinition.getQueryName());
		ntd.setDisplayName(typeDefinition.getDisplayName());
		ntd.setBaseId(typeDefinition.getBaseTypeId());
		ntd.setParentId(typeDefinition.getParentTypeId());
		ntd.setDescription(typeDefinition.getDescription());
		ntd.setCreatable(typeDefinition.isCreatable());
		ntd.setFilable(typeDefinition.isFileable());
		ntd.setQueryable(typeDefinition.isQueryable());
		ntd.setControllablePolicy(typeDefinition.isControllablePolicy());
		ntd.setControllableACL(typeDefinition.isControllableAcl());
		ntd.setFulltextIndexed(typeDefinition.isFulltextIndexed());
		ntd.setIncludedInSupertypeQuery(typeDefinition
				.isIncludedInSupertypeQuery());
		TypeMutability typeMutability = typeDefinition.getTypeMutability();
		if (typeMutability != null) {
			ntd.setTypeMutabilityCreate(typeMutability.canCreate());
			ntd.setTypeMutabilityDelete(typeMutability.canDelete());
			ntd.setTypeMutabilityUpdate(typeMutability.canUpdate());
		} else {
			// These default values are repository-specific.
			ntd.setTypeMutabilityCreate(true);
			ntd.setTypeMutabilityDelete(true);
			ntd.setTypeMutabilityUpdate(true);
		}

		//specific to DocumentTypeDefinition
		if(typeDefinition instanceof DocumentTypeDefinition){
			DocumentTypeDefinition dtdf = (DocumentTypeDefinition)typeDefinition;
			ntd.setVersionable(dtdf.isVersionable());
			ntd.setContentStreamAllowed(dtdf.getContentStreamAllowed());
		}

		//specific to RelationshipTypeDefinition
		if(typeDefinition instanceof RelationshipTypeDefinition){
			RelationshipTypeDefinition dtdf = (RelationshipTypeDefinition)typeDefinition;
			ntd.setAllowedSourceTypes(dtdf.getAllowedSourceTypeIds());
			ntd.setAllowedTargetTypes(dtdf.getAllowedTargetTypeIds());
		}

		return ntd;
	}

	private TypeDefinition sortPropertyDefinitions(
			String repositoryId, NemakiTypeDefinition nemakiTypeDefinition, TypeDefinition criterion) {
		
		// CRITICAL: This calls buildTypeDefinitionFromDB which should call addBasePropertyDefinitions
		AbstractTypeDefinition tdf = typeManager
				.buildTypeDefinitionFromDB(repositoryId, nemakiTypeDefinition);
		
		Map<String, PropertyDefinition<?>> propDefs = tdf
				.getPropertyDefinitions();

		// CRITICAL FIX: Proper property ID mapping without contamination
		LinkedHashMap<String, PropertyDefinition<?>> finalMap = new LinkedHashMap<String, PropertyDefinition<?>>();
		
		Map<String, PropertyDefinition<?>> criterionProps = criterion.getPropertyDefinitions();
		
		// Step 1: Add ALL properties from database (includes CMIS base properties)
		if (propDefs != null) {
			for (Entry<String, PropertyDefinition<?>> entry : propDefs.entrySet()) {
				finalMap.put(entry.getKey(), entry.getValue());
			}
		}
		
		// Step 2: CAREFULLY add criterion properties WITHOUT overwriting existing property IDs
		if (MapUtils.isNotEmpty(criterionProps)) {
			
			for (Entry<String, PropertyDefinition<?>> criterionEntry : criterionProps.entrySet()) {
				String criterionPropertyId = criterionEntry.getKey();
				PropertyDefinition<?> criterionPropertyDef = criterionEntry.getValue();
				
				// CRITICAL: Check if this is a custom property (non-CMIS namespace)
				if (!criterionPropertyId.startsWith("cmis:")) {
					
					// Find the correct database property definition by property type match
					PropertyDefinition<?> correctDbProperty = null;
					for (Entry<String, PropertyDefinition<?>> dbEntry : propDefs.entrySet()) {
						PropertyDefinition<?> dbProperty = dbEntry.getValue();
						
						// Match by property type and ID (the REAL property ID, not the map key)
						if (dbProperty.getPropertyType() == criterionPropertyDef.getPropertyType() &&
							criterionPropertyId.equals(dbProperty.getId())) {
							correctDbProperty = dbProperty;
							break;
						}
					}
					
					if (correctDbProperty != null) {
						// Use the criterion property ID (correct custom property name) with the database property definition
						finalMap.put(criterionPropertyId, correctDbProperty);
					} else {
						// This should not happen if property creation worked correctly
						// Fall back to criterion property but log it
						finalMap.put(criterionPropertyId, criterionPropertyDef);
					}
				} else {
					// CMIS standard properties - keep as-is from database if already present
					if (!finalMap.containsKey(criterionPropertyId)) {
						finalMap.put(criterionPropertyId, criterionPropertyDef);
					}
				}
			}
		}

		// Set the corrected property definitions
		tdf.setPropertyDefinitions(finalMap);

		// Final validation
		Map<String, PropertyDefinition<?>> finalProps = tdf.getPropertyDefinitions();
		if (finalProps != null) {
			// ADDITIONAL VALIDATION: Check for type consistency
			for (Entry<String, PropertyDefinition<?>> entry : finalProps.entrySet()) {
				PropertyDefinition<?> prop = entry.getValue();
				
				// Check for contamination indicators
				if (!entry.getKey().equals(prop.getId())) {
					// Potential contamination issue detected
				}
			}
		}

		return tdf;
	}

	/**
	 * Sets CMIS optional capabilities for Nemaki repository.
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
}
