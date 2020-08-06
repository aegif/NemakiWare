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
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.InitializingBean;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

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

		return typeManager.getTypesChildren(callContext, repositoryId,
				typeId, includePropertyDefinitions, maxItems, skipCount);
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
		TypeDefinition typeDefinition = typeManager.getTypeDefinition(repositoryId, typeId);
		exceptionService.objectNotFound(DomainType.OBJECT_TYPE, typeDefinition,
				typeId);
		return typeDefinition;
	}

	@Override
	public TypeDefinition createType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext, repositoryId);
		exceptionService.invalidArgumentRequired("typeDefinition", type);
		exceptionService.invalidArgumentCreatableType(repositoryId, type);
		exceptionService.constraintDuplicatePropertyDefinition(repositoryId, type);

		// //////////////////
		// Body of the method
		// //////////////////
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
				if (!systemIds.contains(key)) {
					//Check PropertyDefinition
					exceptionService.constraintQueryName(propDef);
					exceptionService.constraintPropertyDefinition(type, propDef);

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

		// Create
		NemakiTypeDefinition created = typeService.createTypeDefinition(repositoryId, ntd);
		typeManager.refreshTypes();

		// Sort the order of properties
		return sortPropertyDefinitions(repositoryId, created, type);
	}

	@Override
	public void deleteType(CallContext callContext, String repositoryId,
			String typeId, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.perimissionAdmin(callContext, repositoryId);
		exceptionService.invalidArgumentRequiredString("typeId", typeId);
		exceptionService.invalidArgumentDoesNotExistType(repositoryId, typeId);
		exceptionService.invalidArgumentDeletableType(repositoryId, typeId);
		exceptionService.constraintOnlyLeafTypeDefinition(repositoryId, typeId);
		exceptionService.constraintObjectsStillExist(repositoryId, typeId);

		// //////////////////
		// Body of the method
		// //////////////////
		typeService.deleteTypeDefinition(repositoryId, typeId);
		typeManager.refreshTypes();
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

				List<String> existingPropertyNodeIds = (CollectionUtils
						.isEmpty(existingType.getProperties())) ? new ArrayList<String>()
						: existingType.getProperties();

				String propNodeId = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, key).getId();
				if (existingPropertyNodeIds.contains(propNodeId)) {
					// update
					PropertyDefinition<?> oldPropDef = typeManager.getTypeDefinition(repositoryId, existingType.getTypeId()).getPropertyDefinitions().get(propNodeId);
					exceptionService.constraintUpdatePropertyDefinition(propDef, oldPropDef);
					exceptionService.constraintQueryName(propDef);
					exceptionService.constraintPropertyDefinition(type, propDef);

					NemakiPropertyDefinition _update = new NemakiPropertyDefinition(
							propDef);
					NemakiPropertyDefinitionCore core = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, _update.getPropertyId());
					NemakiPropertyDefinitionDetail update = new NemakiPropertyDefinitionDetail(_update, core.getId());
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
		AbstractTypeDefinition tdf = typeManager
				.buildTypeDefinitionFromDB(repositoryId, nemakiTypeDefinition);
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
