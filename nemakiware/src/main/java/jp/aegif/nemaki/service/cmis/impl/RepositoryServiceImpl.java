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
package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.MapUtils;
import org.springframework.beans.factory.InitializingBean;
import org.apache.commons.collections.CollectionUtils;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

	private NemakiRepositoryInfoImpl repositoryInfo;
	private ContentService contentService;
	private TypeManager typeManager;
	private ExceptionService exceptionService;

	public TypeManager getTypeManager() {
		return typeManager;
	}

	public boolean hasThisRepositoryId(String repositoryId) {
		return (repositoryId.equals(repositoryInfo.getId()));
	}

	public NemakiRepositoryInfoImpl getRepositoryInfo() {
		repositoryInfo.setLatestChangeLogToken(contentService
				.getLatestChangeToken());
		return repositoryInfo;
	}

	/**
	 * CMIS Service method
	 */
	@Override
	public TypeDefinitionList getTypeChildren(CallContext callContext,
			String typeId, Boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount) {

		return typeManager.getTypesChildren(callContext, typeId,
				includePropertyDefinitions, maxItems, skipCount);
	}

	@Override
	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String typeId, BigInteger depth,
			Boolean includePropertyDefinitions) {
		return typeManager.getTypesDescendants(typeId, depth,
				includePropertyDefinitions);
	}

	@Override
	public TypeDefinition getTypeDefinition(CallContext callContext,
			String typeId) {
		TypeDefinition typeDefinition = typeManager.getTypeDefinition(typeId);
		exceptionService.objectNotFound(DomainType.OBJECT_TYPE, typeDefinition,
				typeId);
		return typeDefinition;
	}

	@Override
	public TypeDefinition createType(CallContext callContext,
			TypeDefinition type, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext);
		exceptionService.invalidArgumentRequired("typeDefinition", type);
		exceptionService.invalidArgumentCreatableType(type);
		exceptionService.constraintDuplicatePropertyDefinition(type);

		// //////////////////
		// Body of the method
		// //////////////////
		// Attributes
		NemakiTypeDefinition ntd = setNemakiTypeDefinitionAttributes(type);

		// Property definitions
		List<String> systemIds = typeManager.getSystemPropertyIds();
		Map<String, PropertyDefinition<?>> propDefs = type
				.getPropertyDefinitions();

		ntd.setProperties(new ArrayList<String>());
		if (MapUtils.isNotEmpty(propDefs)) {
			for (String key : propDefs.keySet()) {
				PropertyDefinition<?> propDef = propDefs.get(key);
				if (!systemIds.contains(key)) {
					exceptionService.constraintQueryName(propDef);
					NemakiPropertyDefinition create = new NemakiPropertyDefinition(
							propDef);
					NemakiPropertyDefinitionDetail created = contentService
							.createPropertyDefinition(create);

					List<String> l = ntd.getProperties();
					l.add(created.getId());
					ntd.setProperties(l);
				}
			}
		}

		// Create
		NemakiTypeDefinition created = contentService.createTypeDefinition(ntd);
		typeManager.refreshTypes();

		// Sort the order of properties
		return sortPropertyDefinitions(created, type);
	}

	@Override
	public void deleteType(CallContext callContext, String typeId,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext);
		exceptionService.invalidArgumentRequiredString("typeId", typeId);
		exceptionService.invalidArgumentDoesNotExistType(typeId);
		exceptionService.invalidArgumentDeletableType(typeId);
		exceptionService.constaintOnlyLeafTypeDefinition(typeId);
		exceptionService.constraintObjectsStillExist(typeId);

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.deleteTypeDefinition(typeId);
		typeManager.refreshTypes();
	}

	@Override
	public TypeDefinition updateType(CallContext callContext,
			TypeDefinition type, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext);
		exceptionService.invalidArgumentRequired("typeDefinition", type);
		exceptionService.invalidArgumentDoesNotExistType(type.getId());
		exceptionService.invalidArgumentUpdatableType(type);
		exceptionService.constaintOnlyLeafTypeDefinition(type.getId());
		exceptionService.constraintDuplicatePropertyDefinition(type);

		// //////////////////
		// Body of the method
		// //////////////////
		NemakiTypeDefinition existingType = contentService
				.getTypeDefinition(type.getId());

		// Attributes
		NemakiTypeDefinition ntd = setNemakiTypeDefinitionAttributes(type);

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

				List<String> existingPropertyNodeIds = (CollectionUtils
						.isEmpty(existingType.getProperties())) ? new ArrayList<String>()
						: existingType.getProperties();
				
				String propNodeId = contentService.getPropertyDefinitionCoreByPropertyId(key).getId(); 		
				if (existingPropertyNodeIds.contains(propNodeId)) {
					// update
					PropertyDefinition<?> oldPropDef = typeManager.getTypeDefinition(existingType.getTypeId()).getPropertyDefinitions().get(propNodeId);
					exceptionService.constraintUpdatePropertyDefinition(propDef, oldPropDef);
					exceptionService.constraintQueryName(propDef);
					
					NemakiPropertyDefinition _update = new NemakiPropertyDefinition(
							propDef);
					NemakiPropertyDefinitionCore core = contentService.getPropertyDefinitionCoreByPropertyId(_update.getPropertyId());
					NemakiPropertyDefinitionDetail update = new NemakiPropertyDefinitionDetail(_update, core.getId());
					contentService.updatePropertyDefinitionDetail(update);
				} else {
					// create
					exceptionService.constraintQueryName(propDef);
					NemakiPropertyDefinition create = new NemakiPropertyDefinition(
							propDef);
					NemakiPropertyDefinitionDetail created = contentService
							.createPropertyDefinition(create);

					List<String> l = ntd.getProperties();
					l.add(created.getId());
					ntd.setProperties(l);
				}

			}
		}

		// Update
		NemakiTypeDefinition updated = contentService.updateTypeDefinition(ntd);
		typeManager.refreshTypes();

		// Sort
		return sortPropertyDefinitions(updated, type);
	}
	
	private NemakiTypeDefinition setNemakiTypeDefinitionAttributes(
			TypeDefinition typeDefinition) {
		NemakiTypeDefinition ntd = new NemakiTypeDefinition();

		ntd.setType("typeDefinition");
		// To avoid the conflict of typeId, add suffix
		if (typeManager.getTypeById(typeDefinition.getId()) == null) {
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
			ntd.setTypeMutabilityCreate(false);
			ntd.setTypeMutabilityDelete(true);
			ntd.setTypeMutabilityUpdate(false);
		}

		return ntd;
	}

	private TypeDefinition sortPropertyDefinitions(
			NemakiTypeDefinition nemakiTypeDefinition, TypeDefinition criterion) {
		AbstractTypeDefinition tdf = typeManager
				.buildTypeDefinitionFromDB(nemakiTypeDefinition);
		Map<String, PropertyDefinition<?>> propDefs = tdf
				.getPropertyDefinitions();

		LinkedHashMap<String, PropertyDefinition<?>> map = new LinkedHashMap<String, PropertyDefinition<?>>();
		LinkedHashMap<String, PropertyDefinition<?>> sorted = new LinkedHashMap<String, PropertyDefinition<?>>();
		if (MapUtils.isNotEmpty(criterion.getPropertyDefinitions())) {
			// Not updated property definitions
			for (Entry<String, PropertyDefinition<?>> propDef : propDefs
					.entrySet()) {
				if (!criterion.getPropertyDefinitions().containsKey(
						propDef.getKey())) {
					map.put(propDef.getKey(), propDef.getValue());
				}
			}

			// Sorted updated property definitions
			for (Entry<String, PropertyDefinition<?>> entry : criterion
					.getPropertyDefinitions().entrySet()) {
				sorted.put(entry.getKey(), entry.getValue());
			}

			// Merge
			for (Entry<String, PropertyDefinition<?>> entry : sorted.entrySet()) {
				map.put(entry.getKey(), entry.getValue());
			}
			tdf.setPropertyDefinitions(map);
		}

		return tdf;
	}

	/**
	 * Sets CMIS optional capabilities for Nemaki repository.
	 */
	public void afterPropertiesSet() throws Exception {
	}

	public void setRepositoryInfo(NemakiRepositoryInfoImpl repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}
}
