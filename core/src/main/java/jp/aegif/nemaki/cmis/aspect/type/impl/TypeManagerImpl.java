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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

/**
 * Type Manager class
 */
public class TypeManagerImpl implements TypeManager {

	private static final Log log = LogFactory.getLog(TypeManagerImpl.class);
	
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
			// CRITICAL DEBUG: Add extensive logging for cache refresh
			log.info("NEMAKI TYPE DEBUG: refreshTypes() called - clearing and rebuilding cache");
			System.out.println("NEMAKI TYPE DEBUG: refreshTypes() called - clearing and rebuilding cache");
			
			// Reset initialization flag to force re-initialization
			initialized = false;
			
			initGlobalTypes();
			
			basetypes.clear();
			basetypes = new HashMap<String, TypeDefinitionContainer>();
			
			subTypeProperties.clear();
			subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
			
			propertyDefinitionCoresForQueryName.clear();
			propertyDefinitionCoresForQueryName = new HashMap<String, PropertyDefinition<?>>();

			log.info("NEMAKI TYPE DEBUG: Starting cache regeneration...");
			System.out.println("NEMAKI TYPE DEBUG: Starting cache regeneration...");
			
			generate();
			
			log.info("NEMAKI TYPE DEBUG: Cache regeneration complete");
			System.out.println("NEMAKI TYPE DEBUG: Cache regeneration complete");
			
			// Log final cache state
			for (String repositoryId : TYPES.keySet()) {
				Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
				log.info("NEMAKI TYPE DEBUG: Repository " + repositoryId + " now has " + types.size() + " types in cache");
				System.out.println("NEMAKI TYPE DEBUG: Repository " + repositoryId + " now has " + types.size() + " types in cache");
				log.info("NEMAKI TYPE DEBUG: Type IDs after refresh: " + types.keySet());
				System.out.println("NEMAKI TYPE DEBUG: Type IDs after refresh: " + types.keySet());
			}
			
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
		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(typeMutabilityCanCreate);
		typeMutability.setCanUpdate(typeMutabilityCanUpdate);
		typeMutability.setCanDelete(typeMutabilityCanDelete);
		secondaryType.setTypeMutability(typeMutability);

		// CRITICAL FIX: Add all base CMIS properties (same as Document/Folder types)
		// This was missing and caused Secondary types to have NO property definitions
		addBasePropertyDefinitions(repositoryId, secondaryType);

		addTypeInternal(TYPES.get(repositoryId), secondaryType);
		addTypeInternal(basetypes, secondaryType);
	}


	private void addBasePropertyDefinitions(String repositoryId, AbstractTypeDefinition type) {
		if (log.isDebugEnabled()) {
			log.debug("Adding base property definitions for type: " + type.getId());
		}
		
		// Get initial property count
		Map<String, PropertyDefinition<?>> initialProps = type.getPropertyDefinitions();
		int initialCount = (initialProps != null) ? initialProps.size() : 0;
		if (log.isDebugEnabled()) {
			log.debug("Initial property definitions count: " + initialCount);
		}
		
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

		//cmis:secondaryObjectTypeIds - CRITICAL CMIS 1.1 REQUIREMENT
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
		
		// Get final property count
		Map<String, PropertyDefinition<?>> finalProps = type.getPropertyDefinitions();
		int finalCount = (finalProps != null) ? finalProps.size() : 0;
		if (log.isDebugEnabled()) {
			log.debug("Final property definitions count: " + finalCount + " (added " + (finalCount - initialCount) + " properties)");
			if (finalProps != null && finalProps.containsKey(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
				log.debug("CONFIRMATION: cmis:secondaryObjectTypeIds IS PRESENT in final property definitions");
			} else {
				log.warn("WARNING: cmis:secondaryObjectTypeIds IS MISSING from final property definitions");
			}
		}
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
				!REQUIRED, QUERYABLE, !ORDERABLE, defaults));
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
		if (log.isDebugEnabled()) {
			log.debug("Getting NemakiTypeDefinitions for repository: " + repositoryId + 
				", typeService: " + (typeService != null ? typeService.getClass().getSimpleName() : "NULL"));
		}
		
		List<NemakiTypeDefinition> result = typeService.getTypeDefinitions(repositoryId);
		
		if (log.isDebugEnabled()) {
			log.debug("Retrieved " + (result != null ? result.size() : 0) + " type definitions");
		}
		
		return result;
	}

	
	private void addSubTypes(){
		for(String key : repositoryInfoMap.keys()){
			RepositoryInfo info = repositoryInfoMap.get(key);
			String repositoryId = info.getId();
			addSubTypes(repositoryId);
		}
	}
	
	private void addSubTypes(String repositoryId) {
		List<NemakiTypeDefinition> subtypes = null;
		try {
			if (log.isDebugEnabled()) {
				log.debug("Adding subtypes for repository: " + repositoryId);
			}
			
			subtypes = getNemakiTypeDefinitions(repositoryId);
			
			if (log.isDebugEnabled()) {
				log.debug("Retrieved " + (subtypes != null ? subtypes.size() : 0) + " type definitions from database");
			}
		} catch (Exception e) {
			log.error("Failed to get type definitions for repository: " + repositoryId, e);
			return;
		}
		
		List<NemakiTypeDefinition> firstGeneration = new ArrayList<NemakiTypeDefinition>();
		if(CollectionUtils.isNotEmpty(subtypes)){
			for (NemakiTypeDefinition subtype : subtypes) {
				if (subtype == null) {
					log.warn("Null subtype found in type definitions");
					continue;
				}
				
				// Skip subtypes with null BaseId (prevents NullPointerException)
				if (subtype.getBaseId() != null && subtype.getParentId() != null) {
					try {
						if (subtype.getBaseId().value().equals(subtype.getParentId())) {
							firstGeneration.add(subtype);
						}
					} catch (Exception e) {
						log.warn("Error processing type definition " + subtype.getTypeId() + ": " + e.getMessage());
					}
				} else {
					log.warn("Skipping type definition with null BaseId or ParentId: " + 
						(subtype.getTypeId() != null ? subtype.getTypeId() : "unknown"));
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
			System.err.println("=== CRITICAL DEBUG: buildTypeDefinitionBaseFromDB ===");
			System.err.println("Processing " + nemakiType.getProperties().size() + " properties for type " + nemakiType.getTypeId());
			for (String propertyDetailId : nemakiType.getProperties()) {
				System.err.println("=== PROCESSING PROPERTY DETAIL ID: " + propertyDetailId + " ===");
				NemakiPropertyDefinitionDetail detail = typeService
						.getPropertyDefinitionDetail(repositoryId, propertyDetailId);
				if (detail == null) {
					log.error("CRITICAL: PropertyDefinitionDetail is null for propertyDetailId: " + propertyDetailId);
					continue;
				}
				
				String coreNodeId = detail.getCoreNodeId();
				if (coreNodeId == null) {
					log.error("CRITICAL: CoreNodeId is null for PropertyDefinitionDetail ID: " + detail.getId() + ", propertyDetailId: " + propertyDetailId);
					continue;
				}
				
				NemakiPropertyDefinitionCore core = typeService
						.getPropertyDefinitionCore(repositoryId, coreNodeId);
				if (core == null) {
					log.error("CRITICAL: PropertyDefinitionCore is null for coreNodeId: " + coreNodeId + ", propertyDetailId: " + propertyDetailId);
					continue;
				}

				// CRITICAL DEBUG: Check for NPE before creating NemakiPropertyDefinition
				System.err.println("=== BEFORE NEMAKI PROPERTY DEFINITION CONSTRUCTOR ===");
				System.err.println("propertyDetailId: " + propertyDetailId + ", coreNodeId: " + coreNodeId);
				System.err.println("core: " + (core != null ? "NOT NULL (ID=" + core.getId() + ", PropertyType=" + core.getPropertyType() + ")" : "NULL"));
				System.err.println("detail: " + (detail != null ? "NOT NULL (ID=" + detail.getId() + ")" : "NULL"));
				
				NemakiPropertyDefinition p = new NemakiPropertyDefinition(core, detail);

				// Property ID contamination is now fixed in NemakiPropertyDefinition constructor

				System.err.println("=== CRITICAL PROPERTY TYPE DEBUG ===");
				System.err.println("Property ID: " + p.getPropertyId());
				System.err.println("Property Type from NemakiPropertyDefinition: " + p.getPropertyType());
				System.err.println("Property Type from Core: " + core.getPropertyType());
				System.err.println("Expected TCK mapping:");
				System.err.println("  - tck:boolean should have PropertyType.BOOLEAN");
				System.err.println("  - tck:id should have PropertyType.ID"); 
				System.err.println("  - tck:integer should have PropertyType.INTEGER");
				System.err.println("  - tck:datetime should have PropertyType.DATETIME");
				System.err.println("  - tck:decimal should have PropertyType.DECIMAL");
				System.err.println("  - tck:html should have PropertyType.HTML");
				System.err.println("  - tck:uri should have PropertyType.URI");

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
				// CRITICAL: Use the corrected property ID as the key, not the potentially contaminated one
				properties.put(p.getPropertyId(), property);
				System.err.println("✅ FINAL MAPPING: " + p.getPropertyId() + " → " + p.getPropertyType() + " (PropertyDefinition created with correct ID)");

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

		// CRITICAL FIX: Add all base CMIS properties (same as in-memory creation)
		addBasePropertyDefinitions(repositoryId, type);
		addDocumentPropertyDefinitions(repositoryId, type);

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

		// CRITICAL FIX: Add all base CMIS properties (same as in-memory creation)
		addBasePropertyDefinitions(repositoryId, type);
		addFolderPropertyDefinitions(repositoryId, type);

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

		// CRITICAL FIX: Add all base CMIS properties (same as in-memory creation)
		addBasePropertyDefinitions(repositoryId, type);
		addRelationshipPropertyDefinitions(repositoryId, type);

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

		// CRITICAL FIX: Add all base CMIS properties (same as in-memory creation)
		addBasePropertyDefinitions(repositoryId, type);
		addPolicyPropertyDefinitions(repositoryId, type);

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

		// CRITICAL FIX: Add all base CMIS properties (same as in-memory creation)
		addBasePropertyDefinitions(repositoryId, type);

		return type;
	}

	private SecondaryTypeDefinitionImpl buildSecondaryTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
		SecondaryTypeDefinitionImpl parentType = nemakiType.getParentId() != null ? 
				(SecondaryTypeDefinitionImpl) types.get(nemakiType.getParentId()).getTypeDefinition() : null;

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(repositoryId, type, parentType, nemakiType);

		// CRITICAL FIX: Add all base CMIS properties (Secondary types require base properties)
		addBasePropertyDefinitions(repositoryId, type);

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
				
				// CRITICAL FIX: Validate property definition before processing
				if (pdf == null) {
					log.warn("Skipping null PropertyDefinition for key: " + e.getKey());
					continue;
				}
				
				String propertyId = pdf.getId();
				String queryName = pdf.getQueryName();
				PropertyType propertyType = pdf.getPropertyType();
				Cardinality cardinality = pdf.getCardinality();
				
				// CRITICAL FIX: Skip properties with null essential fields during cache refresh
				if (propertyId == null || queryName == null || propertyType == null || cardinality == null) {
					if (log.isWarnEnabled()) {
						log.warn("Skipping PropertyDefinition with null fields during cache refresh - Key: " + e.getKey() + 
							", PropertyId: " + propertyId + ", QueryName: " + queryName + 
							", PropertyType: " + propertyType + ", Cardinality: " + cardinality);
					}
					continue;
				}
				
				addPropertyDefinitionCore(propertyId, queryName, propertyType, cardinality);
			}
		}
	}

	private void addPropertyDefinitionCore(String propertyId, String queryName,
			PropertyType propertyType, Cardinality cardinality) {
		// Null parameter validation
		if (propertyId == null || queryName == null || propertyType == null || cardinality == null) {
			if (log.isWarnEnabled()) {
				log.warn("Skipping property definition core with null parameters - propertyId: " + propertyId + 
					", queryName: " + queryName + ", propertyType: " + propertyType + ", cardinality: " + cardinality);
			}
			return;
		}
		
		if (log.isTraceEnabled()) {
			log.trace("Adding property definition core: " + propertyId + " (queryName: " + queryName + ")");
		}

		// Check for existing property definition core by propertyId
		if (!propertyDefinitionCoresForQueryName.containsKey(propertyId)) {
			// Create new property definition core
			PropertyDefinition<?> core = DataUtil.createPropDefCore(propertyId, queryName, propertyType, cardinality);
			propertyDefinitionCoresForQueryName.put(propertyId, core);
			
			if (log.isDebugEnabled()) {
				log.debug("Created new property core: " + propertyId + " -> " + queryName);
			}
		} else {
			if (log.isTraceEnabled()) {
				log.trace("Property core already exists: " + propertyId);
			}
		}

		// Also add to queryName map for lookup compatibility if not already present
		if (!propertyDefinitionCoresForQueryName.containsKey(queryName)) {
			PropertyDefinition<?> existingCore = propertyDefinitionCoresForQueryName.get(propertyId);
			if (existingCore != null) {
				propertyDefinitionCoresForQueryName.put(queryName, existingCore);
				if (log.isTraceEnabled()) {
					log.trace("Mapped queryName to existing core: " + queryName + " -> " + propertyId);
				}
			}
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
		
		// CRITICAL FIX: Handle custom properties stored by propertyId
		// First try standard lookup (for CMIS base properties stored by queryName)
		PropertyDefinition<?> result = propertyDefinitionCoresForQueryName.get(queryName);
		
		if (result == null && !queryName.startsWith("cmis:")) {
			// CRITICAL FIX: Custom properties are stored by propertyId, not queryName
			// Since custom properties typically have queryName == propertyId, the initial lookup should have worked
			// But let's also search through all stored properties to find a match by queryName
			for (Map.Entry<String, PropertyDefinition<?>> entry : propertyDefinitionCoresForQueryName.entrySet()) {
				String storedKey = entry.getKey();
				PropertyDefinition<?> storedProperty = entry.getValue();
				
				// For custom properties, check if the stored property's queryName matches what we're looking for
				if (!storedKey.startsWith("cmis:") && storedProperty.getQueryName() != null) {
					if (storedProperty.getQueryName().equals(queryName)) {
						result = storedProperty;
						System.err.println("=== CUSTOM PROPERTY FOUND BY QUERY NAME MATCH ===");
						System.err.println("queryName requested: " + queryName);
						System.err.println("found stored under key: " + storedKey);
						System.err.println("property ID: " + storedProperty.getId());
						break;
					}
				}
			}
			
			// DEBUG: Log custom property lookups
			System.err.println("=== CUSTOM PROPERTY CORE LOOKUP ===");
			System.err.println("queryName requested: " + queryName);
			System.err.println("found: " + (result != null ? "YES (ID=" + result.getId() + ")" : "NO"));
		}
		
		return result;
	}

	@Override
	public TypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		ensureInitialized();
		
		// CRITICAL DEBUG: Add extensive logging for type cache lookup during deletion
		log.info("NEMAKI TYPE DEBUG: TypeManager.getTypeDefinition called - repositoryId=" + repositoryId + ", typeId=" + typeId);
		System.out.println("NEMAKI TYPE DEBUG: TypeManager.getTypeDefinition called - repositoryId=" + repositoryId + ", typeId=" + typeId);
		
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		if (types == null) {
			log.error("NEMAKI TYPE ERROR: No type cache found for repository: " + repositoryId);
			System.err.println("NEMAKI TYPE ERROR: No type cache found for repository: " + repositoryId);
			return null;
		}
		
		log.info("NEMAKI TYPE DEBUG: Total types in cache for repository " + repositoryId + ": " + types.size());
		System.out.println("NEMAKI TYPE DEBUG: Total types in cache for repository " + repositoryId + ": " + types.size());
		
		// List all type IDs in cache for debugging
		log.info("NEMAKI TYPE DEBUG: Available type IDs in cache: " + types.keySet());
		System.out.println("NEMAKI TYPE DEBUG: Available type IDs in cache: " + types.keySet());
		
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			log.error("NEMAKI TYPE ERROR: Type '" + typeId + "' not found in TypeManager cache");
			System.err.println("NEMAKI TYPE ERROR: Type '" + typeId + "' not found in TypeManager cache");
			
			// Additional debug: Check if this is a timing issue - force refresh and try again
			log.warn("NEMAKI TYPE DEBUG: Attempting forced cache refresh to find missing type");
			System.err.println("NEMAKI TYPE DEBUG: Attempting forced cache refresh to find missing type");
			
			try {
				refreshTypes();
				Map<String, TypeDefinitionContainer> refreshedTypes = TYPES.get(repositoryId);
				if (refreshedTypes != null) {
					log.info("NEMAKI TYPE DEBUG: After refresh, total types: " + refreshedTypes.size());
					System.out.println("NEMAKI TYPE DEBUG: After refresh, total types: " + refreshedTypes.size());
					log.info("NEMAKI TYPE DEBUG: After refresh, available type IDs: " + refreshedTypes.keySet());
					System.out.println("NEMAKI TYPE DEBUG: After refresh, available type IDs: " + refreshedTypes.keySet());
					
					TypeDefinitionContainer refreshedTc = refreshedTypes.get(typeId);
					if (refreshedTc != null) {
						log.info("NEMAKI TYPE FIX: Found type '" + typeId + "' after forced refresh!");
						System.out.println("NEMAKI TYPE FIX: Found type '" + typeId + "' after forced refresh!");
						return refreshedTc.getTypeDefinition();
					} else {
						log.error("NEMAKI TYPE ERROR: Type '" + typeId + "' still not found even after forced refresh");
						System.err.println("NEMAKI TYPE ERROR: Type '" + typeId + "' still not found even after forced refresh");
					}
				}
			} catch (Exception e) {
				log.error("NEMAKI TYPE ERROR: Exception during forced refresh", e);
				System.err.println("NEMAKI TYPE ERROR: Exception during forced refresh: " + e.getMessage());
				e.printStackTrace();
			}
			
			return null;
		}
		
		log.info("NEMAKI TYPE DEBUG: Found type '" + typeId + "' in cache successfully");
		System.out.println("NEMAKI TYPE DEBUG: Found type '" + typeId + "' in cache successfully");

		TypeDefinition typeDefinition = tc.getTypeDefinition();
		
		// CRITICAL CONTAMINATION DEBUG: Check if this type has contaminated property IDs
		if (typeDefinition != null && typeDefinition.getPropertyDefinitions() != null) {
			System.err.println("=== CONTAMINATION CHECK FOR TYPE: " + typeId + " ===");
			System.err.println("Total properties: " + typeDefinition.getPropertyDefinitions().size());
			
			boolean foundContamination = false;
			for (PropertyDefinition<?> prop : typeDefinition.getPropertyDefinitions().values()) {
				if (prop != null) {
					String propertyId = prop.getId();
					PropertyType propertyType = prop.getPropertyType();
					
					// Check for specific contamination patterns from TCK test results
					boolean isContaminated = false;
					String expectedType = null;
					
					// Check for known contamination patterns
					if (propertyId != null) {
						if (propertyId.equals("cmis:name") && propertyType == PropertyType.BOOLEAN) {
							isContaminated = true;
							expectedType = "STRING (should be tck:boolean)";
						} else if (propertyId.equals("cmis:description") && propertyType == PropertyType.ID) {
							isContaminated = true;
							expectedType = "STRING (should be tck:id)";
						} else if (propertyId.equals("cmis:objectId") && propertyType == PropertyType.INTEGER) {
							isContaminated = true;
							expectedType = "ID (should be tck:integer)";
						} else if (propertyId.equals("cmis:baseTypeId") && propertyType == PropertyType.DATETIME) {
							isContaminated = true;
							expectedType = "ID (should be tck:datetime)";
						} else if (propertyId.equals("cmis:objectTypeId") && propertyType == PropertyType.DECIMAL) {
							isContaminated = true;
							expectedType = "ID (should be tck:decimal)";
						} else if (propertyId.equals("cmis:secondaryObjectTypeIds") && propertyType == PropertyType.HTML) {
							isContaminated = true;
							expectedType = "ID (should be tck:html)";
						} else if (propertyId.equals("cmis:creationDate") && propertyType == PropertyType.URI) {
							isContaminated = true;
							expectedType = "DATETIME (should be tck:uri)";
						}
					}
					
					if (isContaminated) {
						foundContamination = true;
						System.err.println("*** CONTAMINATION DETECTED ***");
						System.err.println("Property ID: " + propertyId);
						System.err.println("Current Type: " + propertyType);
						System.err.println("Expected: " + expectedType);
						System.err.println("*** This is the contamination causing TCK failures ***");
					}
				}
			}
			
			if (!foundContamination) {
				System.err.println("No contamination patterns found in type: " + typeId);
			} else {
				System.err.println("*** CONTAMINATION FOUND IN TYPE CACHE - THIS TYPE WILL CAUSE TCK FAILURES ***");
			}
		}

		return typeDefinition;
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
		
		// CRITICAL CMIS COMPLIANCE DEBUG: Log the includePropertyDefinitions parameter
		System.err.println("=== TYPE MANAGER getTypesChildren CALLED ===");
		System.err.println("repositoryId: " + repositoryId);
		System.err.println("typeId: " + typeId);
		System.err.println("includePropertyDefinitions (boolean): " + includePropertyDefinitions);
		
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
			// CRITICAL FIX: Simplified and corrected base types paging logic
			int currentIndex = 0;
			int returnedCount = 0;
			for (String key : basetypes.keySet()) {
				// Skip items before the skip count
				if (currentIndex < skip) {
					currentIndex++;
					continue;
				}
				
				// Stop if we've returned enough items
				if (returnedCount >= max) {
					break;
				}
				
				TypeDefinitionContainer type = basetypes.get(key);
				TypeDefinition typeDef = type.getTypeDefinition();
				
				// CRITICAL CMIS COMPLIANCE FIX: Use copyTypeDefinitionWithoutProperties when includePropertyDefinitions=false
				System.err.println("PROCESSING BASE TYPE: " + key + ", includePropertyDefinitions=" + includePropertyDefinitions);
				if (!includePropertyDefinitions) {
					System.err.println("USING copyTypeDefinitionWithoutProperties");
					typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(typeDef);
				} else {
					System.err.println("USING copyTypeDefinition");
					typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinition(typeDef);
				}
				System.err.println("TYPE property definitions count after processing: " + 
					(typeDef.getPropertyDefinitions() != null ? typeDef.getPropertyDefinitions().size() : "null"));
				
				result.getList().add(typeDef);
				returnedCount++;
				currentIndex++;
			}

			// CRITICAL FIX: Correct hasMoreItems calculation for base types paging
			// hasMoreItems should be true only if there are more items beyond what we've returned
			int totalItems = basetypes.size();
			int originalSkip = (skipCount == null ? 0 : skipCount.intValue());
			boolean hasMore = (originalSkip + result.getList().size()) < totalItems;
			result.setHasMoreItems(hasMore);
			result.setNumItems(BigInteger.valueOf(totalItems));
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

				TypeDefinition typeDef = child.getTypeDefinition();
				
				// CRITICAL CMIS COMPLIANCE FIX: Use copyTypeDefinitionWithoutProperties when includePropertyDefinitions=false
				System.err.println("PROCESSING CHILD TYPE, includePropertyDefinitions=" + includePropertyDefinitions);
				if (!includePropertyDefinitions) {
					System.err.println("USING copyTypeDefinitionWithoutProperties");
					typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(typeDef);
				} else {
					System.err.println("USING copyTypeDefinition");
					typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinition(typeDef);
				}
				System.err.println("TYPE property definitions count after processing: " + 
					(typeDef.getPropertyDefinitions() != null ? typeDef.getPropertyDefinitions().size() : "null"));
				
				result.getList().add(typeDef);

				max--;
				if (max == 0) {
					break;
				}
			}

			result.setHasMoreItems((result.getList().size() + skip) < tc
					.getChildren().size());
			result.setNumItems(BigInteger.valueOf(tc.getChildren().size()));
		}

		// NOTE: includePropertyDefinitions is now properly handled above using copyTypeDefinitionWithoutProperties
		return result;
	}

	/**
	 * CMIS getTypesDescendants.
	 */
	@Override
	public List<TypeDefinitionContainer> getTypesDescendants(String repositoryId,
			String typeId, BigInteger depth, Boolean includePropertyDefinitions) {
		
		// CRITICAL CMIS COMPLIANCE DEBUG: Log the parameters
		System.err.println("=== TYPE MANAGER getTypesDescendants CALLED ===");
		System.err.println("repositoryId: " + repositoryId);
		System.err.println("typeId: " + typeId);
		System.err.println("depth: " + depth);
		System.err.println("includePropertyDefinitions (Boolean): " + includePropertyDefinitions);
		
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
		
		System.err.println("includePropertyDefinitions (converted to boolean): " + ipd);

		if (typeId == null) {
			System.err.println("=== PROCESSING BASE TYPES for getTypesDescendants ===");
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
			System.err.println("=== PROCESSING SPECIFIC TYPE: " + typeId + " for getTypesDescendants ===");
			TypeDefinitionContainer tdc = types.get(typeId);
			flattenTypeDefinitionContainer(tdc, result, d, ipd);
		}

		System.err.println("=== getTypesDescendants RETURNING " + result.size() + " types ===");
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
			result.add(removePropertyDefinition(tdc));
		}

		List<TypeDefinitionContainer> children = tdc.getChildren();
		if (CollectionUtils.isNotEmpty(children)) {
			for (TypeDefinitionContainer child : children) {
				flattenTypeDefinitionContainer(child, result, depth - 1,
						includePropertyDefinitions);
			}
		}
	}

	private TypeDefinitionContainer removePropertyDefinition(
			TypeDefinitionContainer tdc) {
		if (tdc == null) {
			System.err.println("removePropertyDefinition: tdc is null");
			return null;
		}
		
		// Remove from its own typeDefinition
		TypeDefinition tdf = tdc.getTypeDefinition();
		if (tdf == null) {
			System.err.println("removePropertyDefinition: typeDefinition is null");
			return tdc; // Return original if no type definition
		}
		
		System.err.println("removePropertyDefinition: Processing typeId=" + tdf.getId());
		System.err.println("removePropertyDefinition: Original property definitions count=" + 
			(tdf.getPropertyDefinitions() != null ? tdf.getPropertyDefinitions().size() : "null"));
		
		// CRITICAL CMIS COMPLIANCE FIX: Use copyTypeDefinitionWithoutProperties for proper property removal
		TypeDefinition copy = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(tdf);
		System.err.println("removePropertyDefinition: After copyTypeDefinitionWithoutProperties, property definitions count=" + 
			(copy.getPropertyDefinitions() != null ? copy.getPropertyDefinitions().size() : "null"));
		
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl(
				copy);

		// Remove from children recursively
		List<TypeDefinitionContainer> children = tdc.getChildren();
		if (CollectionUtils.isNotEmpty(children)) {
			System.err.println("removePropertyDefinition: Processing " + children.size() + " children");
			List<TypeDefinitionContainer> l = new ArrayList<TypeDefinitionContainer>();
			for (TypeDefinitionContainer child : children) {
				TypeDefinitionContainer processedChild = removePropertyDefinition(child);
				if (processedChild != null) {
					l.add(processedChild);
				}
			}
			result.setChildren(l);
		} else {
			System.err.println("removePropertyDefinition: No children to process");
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
		// IMPLEMENTED: Delete type definition with cache invalidation
		log.info("deleteTypeDefinition: Deleting type definition for typeId=" + typeId + " in repository=" + repositoryId);
		
		try {
			// 1. Call TypeService to handle property definition cleanup and database deletion
			typeService.deleteTypeDefinition(repositoryId, typeId);
			
			// 2. Invalidate type definition cache to ensure consistency
			invalidateTypeDefinitionCache(repositoryId);
			
			log.info("deleteTypeDefinition: Successfully deleted and invalidated cache for typeId=" + typeId);
		} catch (Exception e) {
			log.error("deleteTypeDefinition: Failed to delete typeId=" + typeId + " in repository=" + repositoryId, e);
			throw new CmisObjectNotFoundException("Failed to delete type definition: " + typeId, e);
		}
	}

	/**
	 * Invalidates the type definition cache for a specific repository.
	 * This method clears and rebuilds the type cache to ensure consistency after type modifications.
	 * 
	 * @param repositoryId The repository ID for which to invalidate the cache
	 */
	private void invalidateTypeDefinitionCache(String repositoryId) {
		synchronized (initLock) {
			log.debug("invalidateTypeDefinitionCache: Invalidating cache for repository=" + repositoryId);
			
			// Remove the specific repository's type cache
			if (TYPES != null && TYPES.containsKey(repositoryId)) {
				Map<String, TypeDefinitionContainer> repositoryTypes = TYPES.get(repositoryId);
				int typesCount = repositoryTypes != null ? repositoryTypes.size() : 0;
				
				log.debug("invalidateTypeDefinitionCache: Removing " + typesCount + " cached types for repository=" + repositoryId);
				TYPES.remove(repositoryId);
			}
			
			// CRITICAL FIX: Immediately regenerate cache to ensure consistency
			log.debug("invalidateTypeDefinitionCache: Immediately regenerating cache for repository=" + repositoryId);
			
			// Initialize the repository cache if needed
			if (!TYPES.containsKey(repositoryId)) {
				TYPES.put(repositoryId, new HashMap<String, TypeDefinitionContainer>());
			}
			
			// Regenerate types for this specific repository
			generate(repositoryId);
			
			// Verify regeneration
			if (TYPES.containsKey(repositoryId)) {
				Map<String, TypeDefinitionContainer> newTypes = TYPES.get(repositoryId);
				log.debug("invalidateTypeDefinitionCache: Cache regenerated with " + newTypes.size() + " types for repository=" + repositoryId);
				log.debug("invalidateTypeDefinitionCache: New type IDs: " + newTypes.keySet());
			}
			
			log.debug("invalidateTypeDefinitionCache: Cache invalidation and regeneration complete for repository=" + repositoryId);
		}
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
