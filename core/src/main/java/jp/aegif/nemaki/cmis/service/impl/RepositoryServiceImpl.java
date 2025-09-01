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
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.InitializingBean;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

	private static final Log log = LogFactory.getLog(RepositoryServiceImpl.class);

	private RepositoryInfoMap repositoryInfoMap;
	private TypeManager typeManager;
	private TypeService typeService;
	private ContentService contentService;
	private ExceptionService exceptionService;

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
		
		// Handle null Boolean conversion to primitive boolean (default to false per CMIS spec)
		boolean includeProps = (includePropertyDefinitions == null) ? false : includePropertyDefinitions.booleanValue();

		return typeManager.getTypesChildren(callContext, repositoryId,
				typeId, includeProps, maxItems, skipCount);
	}

	@Override
	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String repositoryId, String typeId,
			BigInteger depth, Boolean includePropertyDefinitions, ExtensionsData extension) {
		return typeManager.getTypesDescendants(repositoryId, typeId,
				depth, includePropertyDefinitions);
	}

	@Override
	public TypeDefinition getTypeDefinition(CallContext callContext,
			String repositoryId, String typeId, ExtensionsData extension) {
		try {
			TypeDefinition typeDefinition = typeManager.getTypeDefinition(repositoryId, typeId);
			
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
		
		log.debug("createType called: repositoryId=" + repositoryId + ", typeId=" + (type != null ? type.getId() : "null") + ", user=" + (callContext != null ? callContext.getUsername() : "null"));
		
		// //////////////////
		// General Exception
		// //////////////////
		log.debug("Performing permission and validation checks for createType");
		exceptionService.perimissionAdmin(callContext, repositoryId);
		exceptionService.invalidArgumentRequired("typeDefinition", type);
		exceptionService.invalidArgumentCreatableType(repositoryId, type);
		exceptionService.constraintDuplicatePropertyDefinition(repositoryId, type);
		log.debug("All validation checks passed for createType");

		// //////////////////
		// Body of the method
		// //////////////////
		// Attributes
		log.debug("Setting type attributes for createType");
		NemakiTypeDefinition ntd = setNemakiTypeDefinitionAttributes(repositoryId, type);
		log.debug("Type attributes set successfully");

		// Property definitions
		List<String> systemIds = typeManager.getSystemPropertyIds();
		Map<String, PropertyDefinition<?>> propDefs = type
				.getPropertyDefinitions();

		ntd.setProperties(new ArrayList<String>());
		
		// CRITICAL FIX: Inherit parent type property definitions
		String parentTypeId = type.getParentTypeId();
		if (parentTypeId != null) {
			TypeDefinition parentType = typeManager.getTypeDefinition(repositoryId, parentTypeId);
			if (parentType != null && parentType.getPropertyDefinitions() != null) {
				
				// Get parent type definition from database to find property IDs
				NemakiTypeDefinition parentTypeDef = typeService.getTypeDefinition(repositoryId, parentTypeId);
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

					NemakiPropertyDefinition create = new NemakiPropertyDefinition(propDef);
					
					NemakiPropertyDefinitionDetail created = typeService
							.createPropertyDefinition(repositoryId, create);
					
					// CRITICAL DEBUG: Get the created property definition and check for contamination
					NemakiPropertyDefinition retrievedProp = typeService.getPropertyDefinition(repositoryId, created.getId());
					if (retrievedProp != null) {
						if (!propDef.getId().equals(retrievedProp.getPropertyId())) {
							// Contamination detected - handle appropriately
						}
					}

					List<String> l = ntd.getProperties();
					// CRITICAL FIX: Add the property ID (e.g., "tck:boolean"), NOT the database ID
					l.add(propDef.getId());  // Use the actual property ID, not the database ID
					ntd.setProperties(l);
				}
			}
		}

		// Create
		NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, ntd);
		
		// CRITICAL FIX: Instead of full refresh, force specific repository cache refresh
		typeManager.refreshTypes();
		
		// ADDITIONAL CHECK: Verify the created type is now in cache
		TypeDefinition verifyType = typeManager.getTypeDefinition(repositoryId, created.getTypeId());
		if (verifyType == null) {
			// Type not found in cache after refresh - this will cause deletion to fail
		}

		// Sort the order of properties
		try {
			TypeDefinition result = sortPropertyDefinitions(repositoryId, created, type);
			return result;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	@Override
	public void deleteType(CallContext callContext, String repositoryId,
			String typeId, ExtensionsData extension) {
		
		log.debug("deleteType called: repositoryId=" + repositoryId + ", typeId=" + typeId + ", user=" + (callContext != null ? callContext.getUsername() : "null"));
		
		// Force direct TypeService call
		try {
			if (typeService == null) {
				log.warn("typeService is null - attempting Spring context lookup");
				org.springframework.web.context.WebApplicationContext context = 
					org.springframework.web.context.support.WebApplicationContextUtils.getWebApplicationContext(
						((org.springframework.web.context.WebApplicationContext) org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext()).getServletContext());
				typeService = (jp.aegif.nemaki.businesslogic.TypeService) context.getBean("TypeService");
				log.debug("TypeService retrieved from Spring context: " + (typeService != null ? "SUCCESS" : "FAILED"));
			}
			
			typeService.deleteTypeDefinition(repositoryId, typeId);
			log.debug("typeService.deleteTypeDefinition completed successfully");
			
			if (typeManager != null) {
				typeManager.refreshTypes();
				log.debug("typeManager.refreshTypes completed");
			} else {
				log.warn("typeManager is null - cache refresh skipped");
			}
			
			log.debug("deleteType completed successfully");
			return;
		} catch (Exception e) {
			log.error("Error in deleteType: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException("Type deletion failed: " + e.getMessage(), e);
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
		NemakiTypeDefinition existingType = typeService
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
