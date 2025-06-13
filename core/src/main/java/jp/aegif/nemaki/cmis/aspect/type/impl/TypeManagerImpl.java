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
package jp.aegif.nemaki.cmis.aspect.type.impl;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

/**
 * Type Manager class
 */
public class TypeManagerImpl implements TypeManager {

	private RepositoryInfoMap repositoryInfoMap;
	private TypeService typeService;
	private PropertyManager propertyManager;

	/**
	 * Constant
	 */
	private final static boolean REQUIRED = true;
	private final static boolean QUERYABLE = true;
	private final static boolean ORDERABLE = true;

	/**
	 * Global variables containing type information
	 */
	// Map of all types
	//private Map<String, TypeDefinitionContainer> types;
	private Map<String, Map<String, TypeDefinitionContainer>> TYPES;

	// Map of all base types
	private Map<String, TypeDefinitionContainer> basetypes;

	// Map of subtype-specific property
	private Map<String, List<PropertyDefinition<?>>> subTypeProperties;

	// Map of propertyDefinition cores(id, name, queryName, propertyType)
	private Map<String, PropertyDefinition<?>> propertyDefinitionCoresForQueryName;
	
	// Flag to track initialization
	private volatile boolean initialized = false;
	private final Object initLock = new Object();

	// /////////////////////////////////////////////////
	// Constructor
	// /////////////////////////////////////////////////
	public void init() {
		// Check if already initialized to avoid duplicate initialization
		if (initialized) {
			return;
		}
		
		synchronized (initLock) {
			if (initialized) {
				return;
			}
			
			initGlobalTypes();
			
			basetypes = new HashMap<String, TypeDefinitionContainer>();
			subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
			propertyDefinitionCoresForQueryName = new HashMap<String, PropertyDefinition<?>>();

			generate();
			initialized = true;
		}
	}
	
	private void ensureInitialized() {
		if (!initialized) {
			synchronized (initLock) {
				if (!initialized) {
					init();
				}
			}
		}
	}

	private void initGlobalTypes(){
		TYPES = new HashMap<String, Map<String,TypeDefinitionContainer>>();
		for(String key : repositoryInfoMap.keys()){
			TYPES.put(key, new HashMap<String, TypeDefinitionContainer>());
		}
	}
	
	private void generate(){
		for(String key : repositoryInfoMap.keys()){
			generate(key);
		}
	}
	
	private void generate(String repositoryId) {
		// Generate basetypes
		addDocumentType(repositoryId);
		addFolderType(repositoryId);
		addRelationshipType(repositoryId);
		addPolicyType(repositoryId);
		addItemType(repositoryId);
		addSecondayType(repositoryId);

		// Generate subtypes
		addSubTypes(repositoryId);

		// Generate property definition cores
		buildPropertyDefinitionCores(repositoryId);
	}

	// /////////////////////////////////////////////////
	// Refresh global variables from DB
	// /////////////////////////////////////////////////
	@Override
	public void refreshTypes() {
		synchronized (initLock) {
			// Reset initialization flag to force re-initialization
			initialized = false;
			
			initGlobalTypes();
			
			basetypes.clear();
			basetypes = new HashMap<String, TypeDefinitionContainer>();
			
			subTypeProperties.clear();
			subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
			
			propertyDefinitionCoresForQueryName.clear();
			propertyDefinitionCoresForQueryName = new HashMap<String, PropertyDefinition<?>>();

			generate();
			initialized = true;
		}
	}

	// /////////////////////////////////////////////////
	// BaseType Generating Methods
	// /////////////////////////////////////////////////
	private void addDocumentType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_DESCRIPTION);
		boolean creatable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_CREATABLE);
		boolean fileable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_FILEABLE);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_QUERYABLE);
		boolean controllablePolicy = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_CONTROLLABLE_POLICY);
		boolean controllableAcl = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_CONTROLLABLE_ACL);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_DELETE);
		boolean versionable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_DOCUMENT_VERSIONABLE);
		String _contentStreamAllowed = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED);
		ContentStreamAllowed contentStreamAllowed = ContentStreamAllowed
				.fromValue(_contentStreamAllowed);

		// Set attributes
		DocumentTypeDefinitionImpl documentType = new DocumentTypeDefinitionImpl();
		documentType.setId(BaseTypeId.CMIS_DOCUMENT.value());
		documentType.setLocalName(localName);
		documentType.setLocalNamespace(getNameSpace(repositoryId));
		documentType.setQueryName(BaseTypeId.CMIS_DOCUMENT.value());
		documentType.setDisplayName(displayName);
		documentType.setDescription(description);
		documentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
		documentType.setIsCreatable(creatable);
		documentType.setIsFileable(fileable);
		documentType.setIsQueryable(queryable);
		documentType.setIsControllablePolicy(controllablePolicy);
		documentType.setIsControllableAcl(controllableAcl);
		documentType.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		documentType.setIsFulltextIndexed(fulltextIndexed);
		documentType.setIsVersionable(versionable);
		documentType.setContentStreamAllowed(contentStreamAllowed);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		documentType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(repositoryId, documentType);
		addDocumentPropertyDefinitions(repositoryId, documentType);

		addTypeInternal(TYPES.get(repositoryId), documentType);
		addTypeInternal(basetypes, documentType);
	}

	private void addFolderType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_DESCRIPTION);
		boolean creatable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_CREATABLE);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_QUERYABLE);
		boolean controllablePolicy = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_CONTROLLABLE_POLICY);
		boolean controllableAcl = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_CONTROLLABLE_ACL);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_DELETE);

		// Set attributes
		FolderTypeDefinitionImpl folderType = new FolderTypeDefinitionImpl();
		folderType.setId(BaseTypeId.CMIS_FOLDER.value());
		folderType.setLocalName(localName);
		folderType.setLocalNamespace(getNameSpace(repositoryId));
		folderType.setQueryName(BaseTypeId.CMIS_FOLDER.value());
		folderType.setDisplayName(displayName);
		folderType.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
		folderType.setDescription(description);
		folderType.setIsCreatable(creatable);
		folderType.setIsFileable(true);
		folderType.setIsQueryable(queryable);
		folderType.setIsControllablePolicy(controllablePolicy);
		folderType.setIsControllableAcl(controllableAcl);
		folderType.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		folderType.setIsFulltextIndexed(fulltextIndexed);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		folderType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(repositoryId, folderType);
		addFolderPropertyDefinitions(repositoryId, folderType);

		addTypeInternal(TYPES.get(repositoryId), folderType);
		addTypeInternal(basetypes, folderType);
	}

	private void addRelationshipType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_DESCRIPTION);
		boolean creatable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_CREATABLE);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_QUERYABLE);
		boolean controllablePolicy = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_CONTROLLABLE_POLICY);
		boolean controllableAcl = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_CONTROLLABLE_ACL);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_DELETE);
		List<String> allowedSourceTypes = propertyManager
				.readValues(PropertyKey.BASETYPE_RELATIONSHIP_ALLOWED_SOURCE_TYPES);
		List<String> allowedTargetTypes = propertyManager
				.readValues(PropertyKey.BASETYPE_RELATIONSHIP_ALLOWED_TARGET_TYPES);

		// Set attributes
		RelationshipTypeDefinitionImpl relationshipType = new RelationshipTypeDefinitionImpl();
		relationshipType.setId(BaseTypeId.CMIS_RELATIONSHIP.value());
		relationshipType.setLocalName(localName);
		relationshipType.setLocalNamespace(getNameSpace(repositoryId));
		relationshipType.setQueryName(BaseTypeId.CMIS_RELATIONSHIP.value());
		relationshipType.setDisplayName(displayName);
		relationshipType.setBaseTypeId(BaseTypeId.CMIS_RELATIONSHIP);
		relationshipType.setDescription(description);
		relationshipType.setIsCreatable(creatable);
		relationshipType.setIsFileable(false);
		relationshipType.setIsQueryable(queryable);
		relationshipType.setIsControllablePolicy(controllablePolicy);
		relationshipType.setIsControllableAcl(controllableAcl);
		relationshipType
				.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		relationshipType.setIsFulltextIndexed(fulltextIndexed);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		relationshipType.setTypeMutability(typeMutability);

		relationshipType.setAllowedSourceTypes(allowedSourceTypes);
		relationshipType.setAllowedTargetTypes(allowedTargetTypes);

		addBasePropertyDefinitions(repositoryId, relationshipType);
		addRelationshipPropertyDefinitions(repositoryId, relationshipType);

		addTypeInternal(TYPES.get(repositoryId), relationshipType);
		addTypeInternal(basetypes, relationshipType);
	}

	private void addPolicyType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_DESCRIPTION);
		boolean creatable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_CREATABLE);
		boolean fileable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_FILEABLE);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_QUERYABLE);
		boolean controllablePolicy = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_CONTROLLABLE_POLICY);
		boolean controllableAcl = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_CONTROLLABLE_ACL);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_DELETE);

		// Set attributes
		PolicyTypeDefinitionImpl policyType = new PolicyTypeDefinitionImpl();
		policyType.setId(BaseTypeId.CMIS_POLICY.value());
		policyType.setLocalName(localName);
		policyType.setLocalNamespace(getNameSpace(repositoryId));
		policyType.setQueryName(BaseTypeId.CMIS_POLICY.value());
		policyType.setDisplayName(displayName);
		policyType.setBaseTypeId(BaseTypeId.CMIS_POLICY);
		policyType.setDescription(description);
		policyType.setIsCreatable(creatable);
		policyType.setIsFileable(fileable);
		policyType.setIsQueryable(queryable);
		policyType.setIsControllablePolicy(controllablePolicy);
		policyType.setIsControllableAcl(controllableAcl);
		policyType.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		policyType.setIsFulltextIndexed(fulltextIndexed);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		policyType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(repositoryId, policyType);
		addPolicyPropertyDefinitions(repositoryId, policyType);

		addTypeInternal(TYPES.get(repositoryId), policyType);
		addTypeInternal(basetypes, policyType);
	}

	private void addItemType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_DESCRIPTION);
		boolean creatable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_CREATABLE);
		boolean fileable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_FILEABLE);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_QUERYABLE);
		boolean controllablePolicy = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_CONTROLLABLE_POLICY);
		boolean controllableAcl = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_CONTROLLABLE_ACL);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_DELETE);

		// Set attributes
		ItemTypeDefinitionImpl itemType = new ItemTypeDefinitionImpl();
		itemType.setId(BaseTypeId.CMIS_ITEM.value());
		itemType.setLocalName(localName);
		itemType.setLocalNamespace(getNameSpace(repositoryId));
		itemType.setQueryName(BaseTypeId.CMIS_ITEM.value());
		itemType.setDisplayName(displayName);
		itemType.setBaseTypeId(BaseTypeId.CMIS_ITEM);
		itemType.setDescription(description);
		itemType.setIsCreatable(creatable);
		itemType.setIsFileable(fileable);
		itemType.setIsQueryable(queryable);
		itemType.setIsControllablePolicy(controllablePolicy);
		itemType.setIsControllableAcl(controllableAcl);
		itemType.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		itemType.setIsFulltextIndexed(fulltextIndexed);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		itemType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(repositoryId, itemType);

		addTypeInternal(TYPES.get(repositoryId), itemType);
		addTypeInternal(basetypes, itemType);
	}

	private void addSecondayType(String repositoryId) {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_DESCRIPTION);
		boolean queryable = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_QUERYABLE);
		boolean includedInSupertypeQuery = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean fulltextIndexed = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_FULLTEXT_INDEXED);
		boolean typeMutabilityCanCreate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanUpdate = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanDelete = propertyManager
				.readBoolean(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_DELETE);

		// Set attributes
		SecondaryTypeDefinitionImpl secondaryType = new SecondaryTypeDefinitionImpl();
		secondaryType.setId(BaseTypeId.CMIS_SECONDARY.value());
		secondaryType.setLocalName(localName);
		secondaryType.setLocalNamespace(getNameSpace(repositoryId));
		secondaryType.setQueryName(BaseTypeId.CMIS_SECONDARY.value());
		secondaryType.setDisplayName(displayName);
		secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		secondaryType.setDescription(description);
		secondaryType.setIsCreatable(false);
		secondaryType.setIsFileable(false);
		secondaryType.setIsQueryable(queryable);
		secondaryType.setIsControllablePolicy(false);
		secondaryType.setIsControllableAcl(false);
		secondaryType.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);
		secondaryType.setIsFulltextIndexed(fulltextIndexed);
		secondaryType
				.setPropertyDefinitions(new HashMap<String, PropertyDefinition<?>>());

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		secondaryType.setTypeMutability(typeMutability);

		secondaryType
				.setPropertyDefinitions(new HashMap<String, PropertyDefinition<?>>());

		addTypeInternal(TYPES.get(repositoryId), secondaryType);
		addTypeInternal(basetypes, secondaryType);
	}


	private void addBasePropertyDefinitions(String repositoryId, AbstractTypeDefinition type) {
		//cmis:name
		String _updatability_name = propertyManager.readValue(PropertyKey.PROPERTY_NAME_UPDATABILITY);
		Updatability updatability_name = Updatability.fromValue(_updatability_name);
		boolean queryable_name = propertyManager.readBoolean(PropertyKey.PROPERTY_NAME_QUERYABLE);
		boolean orderable_name = propertyManager.readBoolean(PropertyKey.PROPERTY_NAME_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.NAME, PropertyType.STRING,
				Cardinality.SINGLE, updatability_name, REQUIRED, queryable_name, orderable_name, null));

		//cmis:description
		String _updatability_description = propertyManager.readValue(PropertyKey.PROPERTY_DESCRIPTION_UPDATABILITY);
		Updatability updatability_description = Updatability.fromValue(_updatability_description);
		boolean queryable_description = propertyManager.readBoolean(PropertyKey.PROPERTY_DESCRIPTION_QUERYABLE);
		boolean orderable_description = propertyManager.readBoolean(PropertyKey.PROPERTY_DESCRIPTION_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.DESCRIPTION,
				PropertyType.STRING, Cardinality.SINGLE, updatability_description,
				!REQUIRED, queryable_description, orderable_description, null));

		//cmis:objectId
		boolean orderable_objectId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.OBJECT_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, orderable_objectId, null));

		//cmis:baseTypeId
		boolean queryable_baseTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_BASE_TYPE_ID_QUERYABLE);
		boolean orderable_baseTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_BASE_TYPE_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.BASE_TYPE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED, queryable_baseTypeId, orderable_baseTypeId, null));

		//cmis:objectTypeId
		boolean queryable_objectTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_TYPE_ID_QUERYABLE);
		boolean orderable_objectTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_TYPE_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.OBJECT_TYPE_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.ONCREATE, REQUIRED,
				queryable_objectTypeId, orderable_objectTypeId, null));

		//cmis:secondaryObjectTypeIds
		String _updatability_secondaryObjectTypeIds = propertyManager.readValue(PropertyKey.PROPERTY_SECONDARY_OBJECT_TYPE_IDS_UPDATABILITY);
		Updatability updatability_secondaryObjectTypeIds = Updatability.fromValue(_updatability_secondaryObjectTypeIds);
		boolean queryable_secondaryObjectTypeIds = propertyManager.readBoolean(PropertyKey.PROPERTY_SECONDARY_OBJECT_TYPE_IDS_QUERYABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
				PropertyType.ID, Cardinality.MULTI, updatability_secondaryObjectTypeIds,
				!REQUIRED, queryable_secondaryObjectTypeIds, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.CREATED_BY, PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CREATION_DATE,
				PropertyType.DATETIME, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.LAST_MODIFIED_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.LAST_MODIFICATION_DATE,
				PropertyType.DATETIME, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CHANGE_TOKEN,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, !QUERYABLE, !ORDERABLE, null));
	}

	private void addFolderPropertyDefinitions(String repositoryId, FolderTypeDefinitionImpl type) {
		//cmis:parentId
		boolean queryable_parentId = propertyManager.readBoolean(PropertyKey.PROPERTY_PARENT_ID_QUERYABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.PARENT_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, queryable_parentId, !ORDERABLE, null));

		//cmis:path
		boolean queryable_path = propertyManager.readBoolean(PropertyKey.PROPERTY_PATH_QUERYABLE);
		boolean orderable_path = propertyManager.readBoolean(PropertyKey.PROPERTY_PATH_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.PATH, PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, queryable_path, orderable_path, null));

		List<String> defaults = new ArrayList<String>();
		defaults.add(BaseTypeId.CMIS_FOLDER.value());
		defaults.add(BaseTypeId.CMIS_DOCUMENT.value());
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS,
				PropertyType.ID, Cardinality.MULTI, Updatability.READONLY,
				!REQUIRED, !QUERYABLE, !ORDERABLE, defaults));
	}

	private void addDocumentPropertyDefinitions(String repositoryId, DocumentTypeDefinitionImpl type) {
		//cmis:isImmutable
		boolean queryable_isImmutable = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_IMMUTABLE_QUERYABLE);
		boolean orderable_isImmutable = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_IMMUTABLE_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.IS_IMMUTABLE,
				PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_isImmutable, orderable_isImmutable, Arrays.asList(false)));

		//cmis:isLatestVersion
		boolean queryable_isLatestVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_LATEST_VERSION_QUERYABLE);
		boolean orderable_isLatestVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_LATEST_VERSION_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.IS_LATEST_VERSION,
				PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_isLatestVersion, orderable_isLatestVersion, null));

		//cmis:isMajorVersion
		boolean queryable_isMajorVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_MAJOR_VERSION_QUERYABLE);
		boolean orderable_isMajorVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_MAJOR_VERSION_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.IS_MAJOR_VERSION,
				PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_isMajorVersion, orderable_isMajorVersion, null));

		//cmis:isLatestMajorVersion
		boolean queryable_isLatestMajorVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_LATEST_MAJOR_VERSION_QUERYABLE);
		boolean orderable_isLatestMajorVersion = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_LATEST_MAJOR_VERSION_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.IS_LATEST_MAJOR_VERSION,
				PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_isLatestMajorVersion, orderable_isLatestMajorVersion, null));

		//cmis:isPrivateWorkingCopy
		boolean queryable_isPrivateWorkingCopy = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_PRIVATE_WORKING_COPY_QUERYABLE);
		boolean orderable_isPrivateWorkingCopy = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_PRIVATE_WORKING_COPY_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.IS_PRIVATE_WORKING_COPY,
				PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_isPrivateWorkingCopy, orderable_isPrivateWorkingCopy, null));

		//cmis:versionLabel
		boolean queryable_versionLabel = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_LABEL_QUERYABLE);
		boolean orderable_versionLabel = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_LABEL_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.VERSION_LABEL,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_versionLabel, orderable_versionLabel, null));

		//cmis:versionSeriesId
		boolean queryable_versionSeriesId = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_ID_QUERYABLE);
		boolean orderable_versionSeriesId = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.VERSION_SERIES_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_versionSeriesId, orderable_versionSeriesId, null));

		//cmis:isVersionSeriesCheckedOut
		boolean queryable_isVersionSeriesCheckedOut = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_VERSION_SERIES_CHECKED_OUT_QUERYABLE);
		boolean orderable_isVersionSeriesCheckedOut = propertyManager.readBoolean(PropertyKey.PROPERTY_IS_VERSION_SERIES_CHECKED_OUT_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId,
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED, queryable_isVersionSeriesCheckedOut, orderable_isVersionSeriesCheckedOut, null));

		//cmis:versionSeriesCheckedOutBy
		boolean queryable_versionSeriesCheckedOutBy = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_CHECKED_OUT_BY_QUERYABLE);
		boolean orderable_versionSeriesCheckedOutBy = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_CHECKED_OUT_BY_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_versionSeriesCheckedOutBy, orderable_versionSeriesCheckedOutBy, null));

		//cmis:versionSeriesCheckedOutId
		boolean queryable_versionSeriesCheckedOutId = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_CHECKED_OUT_ID_QUERYABLE);
		boolean orderable_versionSeriesCheckedOutId = propertyManager.readBoolean(PropertyKey.PROPERTY_VERSION_SERIES_CHECKED_OUT_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_versionSeriesCheckedOutId, orderable_versionSeriesCheckedOutId, null));

		//cmis:checkInComment
		boolean queryable_checkInComment = propertyManager.readBoolean(PropertyKey.PROPERTY_CHECK_IN_COMMENT_QUERYABLE);
		boolean orderable_checkInComment = propertyManager.readBoolean(PropertyKey.PROPERTY_CHECK_IN_COMMENT_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CHECKIN_COMMENT,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_checkInComment, orderable_checkInComment, null));

		//cmis:contentStreamLength
		boolean queryable_contentStreamLength = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_LENGTH_QUERYABLE);
		boolean orderable_contentStreamLength = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_LENGTH_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CONTENT_STREAM_LENGTH,
				PropertyType.INTEGER, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_contentStreamLength, orderable_contentStreamLength, null));

		//cmis:contentStreamMimeType
		boolean queryable_contentStreamMimeType = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_MIME_TYPE_QUERYABLE);
		boolean orderable_contentStreamMimeType = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_MIME_TYPE_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CONTENT_STREAM_MIME_TYPE,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_contentStreamMimeType, orderable_contentStreamMimeType, null));

		//cmis:contentStreamMimeType
		boolean queryable_contentStreamFileName = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_FILE_NAME_QUERYABLE);
		boolean orderable_contentStreamFileName = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_FILE_NAME_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CONTENT_STREAM_FILE_NAME,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_contentStreamFileName, orderable_contentStreamFileName, null));

		//cmis:contentStreamId
		boolean queryable_contentStreamId = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_ID_QUERYABLE);
		boolean orderable_contentStreamId = propertyManager.readBoolean(PropertyKey.PROPERTY_CONTENT_STREAM_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CONTENT_STREAM_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_contentStreamId, orderable_contentStreamId, null));
	}

	private void addRelationshipPropertyDefinitions(
			String repositoryId, RelationshipTypeDefinitionImpl type) {
		//cmis:sourceId
		boolean queryable_sourceId = propertyManager.readBoolean(PropertyKey.PROPERTY_SOURCE_ID_QUERYABLE);
		boolean orderable_sourceId = propertyManager.readBoolean(PropertyKey.PROPERTY_SOURCE_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.SOURCE_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READWRITE, REQUIRED, queryable_sourceId, orderable_sourceId, null));

		//cmis:targetId
		boolean queryable_targetId = propertyManager.readBoolean(PropertyKey.PROPERTY_TARGET_ID_QUERYABLE);
		boolean orderable_targetId = propertyManager.readBoolean(PropertyKey.PROPERTY_TARGET_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.TARGET_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READWRITE, REQUIRED, queryable_targetId, orderable_targetId, null));
	}

	private void addPolicyPropertyDefinitions(String repositoryId, PolicyTypeDefinitionImpl type) {
		//cmis:policyText
		boolean queryable_policyText = propertyManager.readBoolean(PropertyKey.PROPERTY_POLICY_TEXT_QUERYABLE);
		boolean orderable_policyText = propertyManager.readBoolean(PropertyKey.PROPERTY_POLICY_TEXT_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.POLICY_TEXT,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, queryable_policyText, orderable_policyText, null));
	}

	private PropertyDefinition<?> createDefaultPropDef(String repositoryId,
			String id, PropertyType datatype,
			Cardinality cardinality, Updatability updatability, boolean required,
			boolean queryable, boolean orderable, List<?> defaultValue) {
		PropertyDefinition<?> result = null;

		// Default values
		String localName = id;
		String localNameSpace = getNameSpace(repositoryId);
		String queryName = id;
		String displayName = id;
		String description = id;
		boolean inherited = false;
		boolean openChoice = false;

		result = DataUtil.createPropDef(id, localName, localNameSpace,
				queryName, displayName, description, datatype, cardinality,
				updatability, required, queryable, inherited, null, openChoice,
				orderable, defaultValue, null, null, null, null, null, null,
				null);

		return result;
	}
	
	private String getNameSpace(String repositoryId){
		return repositoryInfoMap.get(repositoryId).getNameSpace();
	}

	// /////////////////////////////////////////////////
	// Subtype
	// /////////////////////////////////////////////////
	private List<NemakiTypeDefinition> getNemakiTypeDefinitions(String repositoryId) {
		return typeService.getTypeDefinitions(repositoryId);
	}

	
	private void addSubTypes(){
		for(String key : repositoryInfoMap.keys()){
			RepositoryInfo info = repositoryInfoMap.get(key);
			String repositoryId = info.getId();
			addSubTypes(repositoryId);
		}
	}
	
	private void addSubTypes(String repositoryId) {
		List<NemakiTypeDefinition> subtypes = getNemakiTypeDefinitions(repositoryId);
		List<NemakiTypeDefinition> firstGeneration = new ArrayList<NemakiTypeDefinition>();
		if(CollectionUtils.isNotEmpty(subtypes)){
			for (NemakiTypeDefinition subtype : subtypes) {
				if (subtype.getBaseId().value().equals(subtype.getParentId())) {
					firstGeneration.add(subtype);
				}
			}

			for (NemakiTypeDefinition type : firstGeneration) {
				addSubTypesInternal(repositoryId, subtypes, type);
			}
		}
		
		return;
	}

	private void addSubTypesInternal(String repositoryId,
			List<NemakiTypeDefinition> subtypes, NemakiTypeDefinition type) {
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		
		TypeDefinitionContainerImpl container = new TypeDefinitionContainerImpl();
		container.setTypeDefinition(buildTypeDefinitionFromDB(repositoryId, type));
		container.setChildren(new ArrayList<TypeDefinitionContainer>());

		if (types.get(type.getTypeId()) == null) {
			types.put(type.getTypeId(), container);
		} else {
			// TODO logging: can't overwrite the type
		}

		List<NemakiTypeDefinition> children = new ArrayList<NemakiTypeDefinition>();
		for (NemakiTypeDefinition subtype : subtypes) {
			if (subtype.getParentId().equals(type.getTypeId())) {
				children.add(subtype);
			}
		}

		if (!CollectionUtils.isEmpty(children)) {
			for (NemakiTypeDefinition child : children) {
				addSubTypesInternal(repositoryId, subtypes, child);
			}
		}

		TypeDefinitionContainer parentContainer = types.get(type.getParentId());
		parentContainer.getChildren().add(container);
		return;
	}

	@Override
	public AbstractTypeDefinition buildTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		// Do NOT call ensureInitialized() here as this method is called during initialization
		// and would cause infinite recursion
		switch (nemakiType.getBaseId()) {
		case CMIS_DOCUMENT:
			return buildDocumentTypeDefinitionFromDB(repositoryId, nemakiType);
		case CMIS_FOLDER:
			return buildFolderTypeDefinitionFromDB(repositoryId, nemakiType);
		case CMIS_RELATIONSHIP:
			return buildRelationshipTypeDefinitionFromDB(repositoryId, nemakiType);
		case CMIS_POLICY:
			return buildPolicyTypeDefinitionFromDB(repositoryId, nemakiType);
		case CMIS_ITEM:
			return buildItemTypeDefinitionFromDB(repositoryId, nemakiType);
		case CMIS_SECONDARY:
			return buildSecondaryTypeDefinitionFromDB(repositoryId, nemakiType);
		default:
			break;
		}

		return null;
	}

	private void buildTypeDefinitionBaseFromDB(String repositoryId,
			AbstractTypeDefinition type, AbstractTypeDefinition parentType, NemakiTypeDefinition nemakiType) {
		type.setId(nemakiType.getTypeId());
		type.setLocalName(nemakiType.getLocalName());
		type.setLocalNamespace(nemakiType.getLocalNameSpace());
		type.setQueryName(nemakiType.getQueryName());
		type.setDisplayName(nemakiType.getDisplayName());
		type.setBaseTypeId(nemakiType.getBaseId());
		type.setParentTypeId(nemakiType.getParentId());
		type.setDescription(nemakiType.getDescription());

		boolean creatable = (nemakiType.isCreatable() == null) ? parentType
				.isCreatable() : nemakiType.isCreatable();
		type.setIsCreatable(creatable);
		boolean filable = (nemakiType.isFilable() == null) ? parentType
				.isFileable() : nemakiType.isFilable();
		type.setIsFileable(filable);
		boolean queryable = (nemakiType.isQueryable() == null) ? parentType
				.isQueryable() : nemakiType.isQueryable();
		type.setIsQueryable(queryable);
		boolean controllablePolicy = (nemakiType.isControllablePolicy() == null) ? parentType
				.isControllablePolicy() : nemakiType.isControllablePolicy();
		type.setIsControllablePolicy(controllablePolicy);
		boolean controllableACL = (nemakiType.isControllableACL() == null) ? parentType
				.isControllableAcl() : nemakiType.isControllableACL();
		type.setIsControllableAcl(controllableACL);
		boolean fulltextIndexed = (nemakiType.isFulltextIndexed() == null) ? parentType
				.isFulltextIndexed() : nemakiType.isFulltextIndexed();
		type.setIsFulltextIndexed(fulltextIndexed);
		boolean includedInSupertypeQuery = (nemakiType
				.isIncludedInSupertypeQuery() == null) ? parentType
				.isIncludedInSupertypeQuery() : nemakiType
				.isIncludedInSupertypeQuery();
		type.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);

		// Type Mutability
		boolean create = (nemakiType.isTypeMutabilityCreate() == null) ? parentType
				.getTypeMutability().canCreate() : nemakiType
				.isTypeMutabilityCreate();
		boolean update = (nemakiType.isTypeMutabilityUpdate() == null) ? parentType
				.getTypeMutability().canUpdate() : nemakiType
				.isTypeMutabilityUpdate();
		boolean delete = (nemakiType.isTypeMutabilityDelete() == null) ? parentType
				.getTypeMutability().canDelete() : nemakiType
				.isTypeMutabilityDelete();
		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(create);
		typeMutability.setCanUpdate(update);
		typeMutability.setCanDelete(delete);
		type.setTypeMutability(typeMutability);

		// Inherit parent's properties
		TypeDefinition copied = DataUtil.copyTypeDefinition(parentType);
		Map<String, PropertyDefinition<?>> parentProperties = copied
				.getPropertyDefinitions();
		if (MapUtils.isEmpty(parentProperties)) {
			parentProperties = new HashMap<String, PropertyDefinition<?>>();
		}
		for (String key : parentProperties.keySet()) {
			PropertyDefinition<?> parentProperty = parentProperties.get(key);
			setInheritedToTrue((AbstractPropertyDefinition<?>) parentProperty);
		}
		type.setPropertyDefinitions(parentProperties);

		// Add specific properties
		// TODO if there is the same id with that of the inherited, check the
		// difference of attributes
		Map<String, PropertyDefinition<?>> properties = type
				.getPropertyDefinitions();
		List<PropertyDefinition<?>> specificProperties = new ArrayList<PropertyDefinition<?>>();
		if (!CollectionUtils.isEmpty(nemakiType.getProperties())) {
			for (String propertyId : nemakiType.getProperties()) {
				NemakiPropertyDefinitionDetail detail = typeService
						.getPropertyDefinitionDetail(repositoryId, propertyId);
				NemakiPropertyDefinitionCore core = typeService
						.getPropertyDefinitionCore(repositoryId, detail.getCoreNodeId());

				NemakiPropertyDefinition p = new NemakiPropertyDefinition(core,
						detail);

				PropertyDefinition<?> property = DataUtil.createPropDef(
						p.getPropertyId(), p.getLocalName(),
						p.getLocalNameSpace(), p.getQueryName(),
						p.getDisplayName(), p.getDescription(),
						p.getPropertyType(), p.getCardinality(),
						p.getUpdatability(), p.isRequired(), p.isQueryable(),
						false, p.getChoices(), p.isOpenChoice(),
						p.isOrderable(), p.getDefaultValue(), p.getMinValue(),
						p.getMaxValue(), p.getResolution(),
						p.getDecimalPrecision(), p.getDecimalMinValue(),
						p.getDecimalMaxValue(), p.getMaxLength());
				properties.put(p.getPropertyId(), property);

				// for subTypeProperties
				// ignore null property (List index will be lost)
				if (property != null) {
					specificProperties.add(property);
				}
			}
		}

		// for subTypeProperties
		if (subTypeProperties.containsKey(type.getParentTypeId())) {
			List<PropertyDefinition<?>> parentSpecificProperties = subTypeProperties
					.get(type.getParentTypeId());
			// subTypeProperties.put(type.getId(), specificProperties);
			specificProperties.addAll(parentSpecificProperties);
			subTypeProperties.put(type.getId(), specificProperties);
		} else {
			subTypeProperties.put(type.getId(), specificProperties);
		}
	}

	private AbstractPropertyDefinition<?> setInheritedToTrue(
			AbstractPropertyDefinition<?> property) {
		property.setIsInherited(true);
		return property;
	}

	private DocumentTypeDefinitionImpl buildDocumentTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		DocumentTypeDefinitionImpl type = new DocumentTypeDefinitionImpl();
		DocumentTypeDefinitionImpl parentType = (DocumentTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		// Add specific attributes
		ContentStreamAllowed contentStreamAllowed = (nemakiType
				.getContentStreamAllowed() == null) ? parentType
				.getContentStreamAllowed() : nemakiType
				.getContentStreamAllowed();
		type.setContentStreamAllowed(contentStreamAllowed);
		boolean versionable = (nemakiType.isVersionable() == null) ? parentType
				.isVersionable() : nemakiType.isVersionable();
		type.setIsVersionable(versionable);

		return type;
	}

	private FolderTypeDefinitionImpl buildFolderTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		FolderTypeDefinitionImpl type = new FolderTypeDefinitionImpl();
		FolderTypeDefinitionImpl parentType = (FolderTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		return type;
	}

	private RelationshipTypeDefinitionImpl buildRelationshipTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		RelationshipTypeDefinitionImpl type = new RelationshipTypeDefinitionImpl();
		RelationshipTypeDefinitionImpl parentType = (RelationshipTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		// Set specific attributes
		type.setAllowedSourceTypes(nemakiType.getAllowedSourceTypes());
		type.setAllowedTargetTypes(nemakiType.getAllowedTargetTypes());

		return type;
	}

	private PolicyTypeDefinitionImpl buildPolicyTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		PolicyTypeDefinitionImpl type = new PolicyTypeDefinitionImpl();
		PolicyTypeDefinitionImpl parentType = (PolicyTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		return type;
	}

	private ItemTypeDefinitionImpl buildItemTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		ItemTypeDefinitionImpl type = new ItemTypeDefinitionImpl();
		ItemTypeDefinitionImpl parentType = (ItemTypeDefinitionImpl) types.get(
				nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		return type;
	}

	private SecondaryTypeDefinitionImpl buildSecondaryTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
		SecondaryTypeDefinitionImpl parentType = (SecondaryTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		return type;
	}

	private void buildPropertyDefinitionCores(String repositoryId) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		// CMIS default property cores
		Map<String, PropertyDefinition<?>> d = types.get(BaseTypeId.CMIS_DOCUMENT.value())
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> f = types.get(BaseTypeId.CMIS_FOLDER.value())
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> r = types.get(BaseTypeId.CMIS_RELATIONSHIP.value())
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> p = types.get(BaseTypeId.CMIS_POLICY.value())
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> i = types.get(BaseTypeId.CMIS_ITEM.value())
				.getTypeDefinition().getPropertyDefinitions();

		copyToPropertyDefinitionCore(d);
		copyToPropertyDefinitionCore(f);
		copyToPropertyDefinitionCore(r);
		copyToPropertyDefinitionCore(p);
		copyToPropertyDefinitionCore(i);

		// Subtype property cores(consequently includes secondary property
		// cores)
		List<NemakiPropertyDefinitionCore> subTypeCores = typeService
				.getPropertyDefinitionCores(repositoryId);
		if (CollectionUtils.isNotEmpty(subTypeCores)) {
			for (NemakiPropertyDefinitionCore sc : subTypeCores) {
				addPropertyDefinitionCore(sc.getPropertyId(),
						sc.getQueryName(), sc.getPropertyType(),
						sc.getCardinality());
			}
		}
	}

	private void copyToPropertyDefinitionCore(
			Map<String, PropertyDefinition<?>> map) {
		for (Entry<String, PropertyDefinition<?>> e : map.entrySet()) {
			if (!propertyDefinitionCoresForQueryName.containsKey(e.getKey())) {
				PropertyDefinition<?> pdf = e.getValue();
				addPropertyDefinitionCore(pdf.getId(), pdf.getQueryName(),
						pdf.getPropertyType(), pdf.getCardinality());
			}
		}
	}

	private void addPropertyDefinitionCore(String propertyId, String queryName,
			PropertyType propertyType, Cardinality cardinality) {
		if (!propertyDefinitionCoresForQueryName.containsKey(propertyId)) {
			PropertyDefinition<?> core = DataUtil.createPropDefCore(propertyId,
					queryName, propertyType, cardinality);
			propertyDefinitionCoresForQueryName.put(queryName, core);
		}
	}

	// /////////////////////////////////////////////////
	// Type Service Methods
	// /////////////////////////////////////////////////
	@Override
	public TypeDefinitionContainer getTypeById(String repositoryId, String typeId) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		return types.get(typeId);
	}

	@Override
	public TypeDefinition getTypeByQueryName(String repositoryId, String typeQueryName) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		
		for (Entry<String, TypeDefinitionContainer> entry : types.entrySet()) {
			if (entry.getValue().getTypeDefinition().getQueryName()
					.equals(typeQueryName))
				return entry.getValue().getTypeDefinition();
		}
		return null;
	}

	@Override
	public Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		
		List<TypeDefinitionContainer> typeRoots = new ArrayList<TypeDefinitionContainer>();
		// iterate types map and return a list collecting the root types:
		for (TypeDefinitionContainer typeDef : types.values()) {
			if (typeDef.getTypeDefinition().getParentTypeId() == null) {
				typeRoots.add(typeDef);
			}
		}
		return typeRoots;
	}

	@Override
	public List<TypeDefinitionContainer> getRootTypes(String repositoryId) {
		ensureInitialized();
		List<TypeDefinitionContainer> rootTypes = new ArrayList<TypeDefinitionContainer>();
		for (String key : basetypes.keySet()) {
			rootTypes.add(basetypes.get(key));
		}
		return rootTypes;
	}

	@Override
	public String getPropertyIdForQueryName(String repositoryId,
			TypeDefinition typeDefinition, String propQueryName) {
		PropertyDefinition<?> def = getPropertyDefinitionForQueryName(
				repositoryId, typeDefinition, propQueryName);
		if (def == null) {
			return null;
		} else {
			return def.getQueryName();
		}
	}

	@Override
	public PropertyDefinition<?> getPropertyDefinitionForQueryName(
			String repositoryId, TypeDefinition typeDefinition, String propQueryName) {
		Map<String, PropertyDefinition<?>> defs = typeDefinition
				.getPropertyDefinitions();
		for (Entry<String, PropertyDefinition<?>> def : defs.entrySet()) {
			if (def.getValue().getQueryName().equals(propQueryName)) {
				return def.getValue();
			}
		}

		return null;
	}

	@Override
	public PropertyDefinition<?> getPropertyDefinitionCoreForQueryName(
			String queryName) {
		ensureInitialized();
		return propertyDefinitionCoresForQueryName.get(queryName);
	}

	@Override
	public TypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			return null;
		}

		return tc.getTypeDefinition();
	}

	@Override
	public List<PropertyDefinition<?>> getSpecificPropertyDefinitions(
			String typeId) {
		ensureInitialized();
		return subTypeProperties.get(typeId);
	}

	/**
	 * CMIS getTypesChildren. If parent type id is not specified, return only
	 * base types.
	 */
	@Override
	public TypeDefinitionList getTypesChildren(CallContext context,
			String repositoryId, String typeId,
			boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		
		TypeDefinitionListImpl result = new TypeDefinitionListImpl(
				new ArrayList<TypeDefinition>());

		int skip = (skipCount == null ? 0 : skipCount.intValue());
		if (skip < 0) {
			skip = 0;
		}

		int max = (maxItems == null ? Integer.MAX_VALUE : maxItems.intValue());
		if (max < 1) {
			return result;
		}

		if (typeId == null) {
			int count = skip;
			for (String key : basetypes.keySet()) {
				count--;
				if (count >= 0)
					continue;
				TypeDefinitionContainer type = basetypes.get(key);
				result.getList().add(
						DataUtil.copyTypeDefinition(type.getTypeDefinition()));
			}

			result.setHasMoreItems((result.getList().size() + skip) < max);
			result.setNumItems(BigInteger.valueOf(basetypes.size()));
		} else {
			TypeDefinitionContainer tc = types.get(typeId);
			if ((tc == null) || (tc.getChildren() == null)) {
				return result;
			}

			for (TypeDefinitionContainer child : tc.getChildren()) {
				if (skip > 0) {
					skip--;
					continue;
				}

				result.getList().add(
						DataUtil.copyTypeDefinition(child.getTypeDefinition()));

				max--;
				if (max == 0) {
					break;
				}
			}

			result.setHasMoreItems((result.getList().size() + skip) < tc
					.getChildren().size());
			result.setNumItems(BigInteger.valueOf(tc.getChildren().size()));
		}

		if (!includePropertyDefinitions) {
			for (TypeDefinition type : result.getList()) {
				try {
					if (type.getPropertyDefinitions() != null) {
						type.getPropertyDefinitions().clear();
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		return result;
	}

	/**
	 * CMIS getTypesDescendants.
	 */
	@Override
	public List<TypeDefinitionContainer> getTypesDescendants(String repositoryId,
			String typeId, BigInteger depth, Boolean includePropertyDefinitions) {
		ensureInitialized();
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		
		List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

		// check depth
		int d = (depth == null ? -1 : depth.intValue());
		if (d == 0) {
			throw new CmisInvalidArgumentException("Depth must not be 0!");
		} else if (d < -1) {
			throw new CmisInvalidArgumentException(
					"Depth must be positive(except for -1, that means infinity!");
		}

		// set property definition flag to default value if not set
		boolean ipd = (includePropertyDefinitions == null ? false
				: includePropertyDefinitions.booleanValue());

		if (typeId == null) {
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_FOLDER.value()), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_DOCUMENT.value()), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_RELATIONSHIP.value()),
					result, d, ipd);
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_POLICY.value()), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_ITEM.value()), result, d,
					ipd);
			flattenTypeDefinitionContainer(types.get(BaseTypeId.CMIS_SECONDARY.value()),
					result, d, ipd);
		} else {
			TypeDefinitionContainer tdc = types.get(typeId);
			flattenTypeDefinitionContainer(tdc, result, d, ipd);
		}

		return result;
	}

	private void flattenTypeDefinitionContainer(TypeDefinitionContainer tdc,
			List<TypeDefinitionContainer> result, int depth,
			boolean includePropertyDefinitions) {
		if (depth == 0)
			return;
		if (includePropertyDefinitions) {
			result.add(tdc);
		} else {
			result.add(removeProeprtyDefinition(tdc));
		}

		List<TypeDefinitionContainer> children = tdc.getChildren();
		if (CollectionUtils.isNotEmpty(children)) {
			for (TypeDefinitionContainer child : children) {
				flattenTypeDefinitionContainer(child, result, depth - 1,
						includePropertyDefinitions);
			}
		}
	}

	private TypeDefinitionContainer removeProeprtyDefinition(
			TypeDefinitionContainer tdc) {
		// Remove from its own typeDefinition
		TypeDefinition tdf = tdc.getTypeDefinition();
		TypeDefinition copy = DataUtil.copyTypeDefinition(tdf);
		Map<String, PropertyDefinition<?>> propDefs = copy
				.getPropertyDefinitions();
		if (MapUtils.isNotEmpty(propDefs)) {
			propDefs.clear();
		}
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl(
				copy);

		// Remove from children recursively
		List<TypeDefinitionContainer> children = tdc.getChildren();
		if (CollectionUtils.isNotEmpty(children)) {
			List<TypeDefinitionContainer> l = new ArrayList<TypeDefinitionContainer>();
			for (TypeDefinitionContainer child : children) {
				l.add(removeProeprtyDefinition(child));
			}
			result.setChildren(l);
		}

		return result;
	}

	/**
	 * Get a type definition Internal Use
	 * @param content
	 *
	 * @return
	 */
	@Override
	public TypeDefinition getTypeDefinition(String repositoryId, Content content) {
		String typeId = (content.getObjectType() == null) ? content.getType()
				: content.getObjectType();
		return getTypeDefinition(repositoryId, typeId);
	}

	/**
	 * List up specification-default property ids
	 *
	 * @return
	 */
	@Override
	public List<String> getSystemPropertyIds() {
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

	public void addTypeDefinition(String repositoryId,
			TypeDefinition typeDefinition, boolean addInheritedProperties) {
		// TODO Auto-generated method stub

	}

	public void updateTypeDefinition(String repositoryId, TypeDefinition typeDefinition) {
		// TODO Auto-generated method stub

	}

	public void deleteTypeDefinition(String repositoryId, String typeId) {
		// TODO Auto-generated method stub

	}

	// //////////////////////////////////////////////////////////////////////////////
	// Utility
	// //////////////////////////////////////////////////////////////////////////////
	private void addTypeInternal(Map<String, TypeDefinitionContainer> types,
			AbstractTypeDefinition type) {
		if (type == null) {
			return;
		}

		if (types.containsKey(type.getId())) {
			// TODO Logging
			// log.warn("Can't overwrite a type");
			return;
		}

		TypeDefinitionContainerImpl tc = new TypeDefinitionContainerImpl();
		tc.setTypeDefinition(type);

		// add to parent
		if (type.getParentTypeId() != null) {
			TypeDefinitionContainerImpl tdc = (TypeDefinitionContainerImpl) types
					.get(type.getParentTypeId());
			if (tdc != null) {
				if (tdc.getChildren() == null) {
					tdc.setChildren(new ArrayList<TypeDefinitionContainer>());
				}

				if (!isDuplicateChild(tdc, type)) {
					tdc.getChildren().add(tc);
				}
			}
		}

		types.put(type.getId(), tc);
	}

	private boolean isDuplicateChild(TypeDefinitionContainer parent,
			TypeDefinition type) {
		for (TypeDefinitionContainer child : parent.getChildren()) {
			if (child.getTypeDefinition().getId().equals(type.getId())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Object getSingleDefaultValue(String propertyId, String typeId, String repositoryId) {
		TypeDefinition tdf = getTypeDefinition(repositoryId, typeId);
		PropertyDefinition<?> pdf = tdf.getPropertyDefinitions()
				.get(propertyId);
		return pdf.getDefaultValue().get(0);
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
