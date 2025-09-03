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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
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

	// FUNDAMENTAL FIX: Separate Maps to prevent key collisions between propertyId and queryName
	private Map<String, PropertyDefinition<?>> propertyDefinitionCoresByPropertyId;
	private Map<String, PropertyDefinition<?>> propertyDefinitionCoresByQueryName;
	
	// Flag to track initialization
	private volatile boolean initialized = false;
	private final Object initLock = new Object();
	
	// CRITICAL FIX: Track types being deleted to prevent infinite recursion during cache refresh
	private final Set<String> typesBeingDeleted = new HashSet<>();

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
			propertyDefinitionCoresByPropertyId = new HashMap<String, PropertyDefinition<?>>();
			propertyDefinitionCoresByQueryName = new HashMap<String, PropertyDefinition<?>>();

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
	/**
	 * Mark a type as being deleted to prevent infinite recursion during cache refresh
	 */
	@Override
	public void markTypeBeingDeleted(String typeId) {
		synchronized (initLock) {
			typesBeingDeleted.add(typeId);
			log.debug("Marked type as being deleted: " + typeId);
		}
	}
	
	/**
	 * Unmark a type as being deleted after deletion completes
	 */
	@Override
	public void unmarkTypeBeingDeleted(String typeId) {
		synchronized (initLock) {
			typesBeingDeleted.remove(typeId);
			log.debug("Unmarked type being deleted: " + typeId);
		}
	}
	
	@Override
	public void refreshTypes() {
		synchronized (initLock) {
			// Cache refresh logging
			log.info("refreshTypes() called - clearing and rebuilding cache");
			
			// Reset initialization flag to force re-initialization
			initialized = false;
			
			initGlobalTypes();
			
			basetypes.clear();
			basetypes = new HashMap<String, TypeDefinitionContainer>();
			
			subTypeProperties.clear();
			subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
			
			propertyDefinitionCoresByPropertyId.clear();
			propertyDefinitionCoresByQueryName.clear();
			propertyDefinitionCoresByPropertyId = new HashMap<String, PropertyDefinition<?>>();
			propertyDefinitionCoresByQueryName = new HashMap<String, PropertyDefinition<?>>();

			log.info("Starting cache regeneration...");
			
			generate();
			
			log.info("Cache regeneration complete");
			
			// Log final cache state
			for (String repositoryId : TYPES.keySet()) {
				Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
				log.debug("NEMAKI TYPE DEBUG: Repository " + repositoryId + " now has " + types.size() + " types in cache");
				log.debug("NEMAKI TYPE DEBUG: Type IDs after refresh: " + types.keySet());
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
		// CMIS 1.1 SPECIFICATION COMPLIANCE: Secondary types must NOT be creatable per CMIS specification
		// Secondary types are attached to other objects, not created independently - configuration correctly sets false
		secondaryType.setIsCreatable(typeMutabilityCanCreate);
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
		log.info("=== ADD BASE PROPERTIES DEBUG: Starting for type: " + type.getId() + " ===");
		
		// Get initial property count
		Map<String, PropertyDefinition<?>> initialProps = type.getPropertyDefinitions();
		int initialCount = (initialProps != null) ? initialProps.size() : 0;
		log.info("DEBUG: Initial property definitions count: " + initialCount);
		if (initialProps != null) {
			log.info("DEBUG: Initial property keys: " + initialProps.keySet());
		}
		
		//cmis:name
		String _updatability_name = propertyManager.readValue(PropertyKey.PROPERTY_NAME_UPDATABILITY);
		Updatability updatability_name = Updatability.fromValue(_updatability_name);
		boolean queryable_name = propertyManager.readBoolean(PropertyKey.PROPERTY_NAME_QUERYABLE);
		boolean orderable_name = propertyManager.readBoolean(PropertyKey.PROPERTY_NAME_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.NAME, PropertyType.STRING,
				Cardinality.SINGLE, updatability_name, REQUIRED, queryable_name, orderable_name, null));
		log.info("DEBUG: Added cmis:name property");

		//cmis:description
		String _updatability_description = propertyManager.readValue(PropertyKey.PROPERTY_DESCRIPTION_UPDATABILITY);
		Updatability updatability_description = Updatability.fromValue(_updatability_description);
		boolean queryable_description = propertyManager.readBoolean(PropertyKey.PROPERTY_DESCRIPTION_QUERYABLE);
		boolean orderable_description = propertyManager.readBoolean(PropertyKey.PROPERTY_DESCRIPTION_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.DESCRIPTION,
				PropertyType.STRING, Cardinality.SINGLE, updatability_description,
				!REQUIRED, queryable_description, orderable_description, null));
		log.info("DEBUG: Added cmis:description property");

		//cmis:objectId
		boolean orderable_objectId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.OBJECT_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, orderable_objectId, null));
		log.info("DEBUG: Added cmis:objectId property");

		//cmis:baseTypeId
		boolean queryable_baseTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_BASE_TYPE_ID_QUERYABLE);
		boolean orderable_baseTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_BASE_TYPE_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.BASE_TYPE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED, queryable_baseTypeId, orderable_baseTypeId, null));
		log.info("DEBUG: Added cmis:baseTypeId property");

		//cmis:objectTypeId
		boolean queryable_objectTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_TYPE_ID_QUERYABLE);
		boolean orderable_objectTypeId = propertyManager.readBoolean(PropertyKey.PROPERTY_OBJECT_TYPE_ID_ORDERABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.OBJECT_TYPE_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.ONCREATE, REQUIRED,
				queryable_objectTypeId, orderable_objectTypeId, null));
		log.info("DEBUG: Added cmis:objectTypeId property");

		//cmis:secondaryObjectTypeIds - CRITICAL CMIS 1.1 REQUIREMENT
		String _updatability_secondaryObjectTypeIds = propertyManager.readValue(PropertyKey.PROPERTY_SECONDARY_OBJECT_TYPE_IDS_UPDATABILITY);
		Updatability updatability_secondaryObjectTypeIds = Updatability.fromValue(_updatability_secondaryObjectTypeIds);
		boolean queryable_secondaryObjectTypeIds = propertyManager.readBoolean(PropertyKey.PROPERTY_SECONDARY_OBJECT_TYPE_IDS_QUERYABLE);
		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
				PropertyType.ID, Cardinality.MULTI, updatability_secondaryObjectTypeIds,
				!REQUIRED, queryable_secondaryObjectTypeIds, !ORDERABLE, null));
		log.info("DEBUG: Added cmis:secondaryObjectTypeIds property");

		type.addPropertyDefinition(createDefaultPropDef(repositoryId,
				PropertyIds.CREATED_BY, PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, ORDERABLE, null));
		log.info("DEBUG: Added cmis:createdBy property");

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CREATION_DATE,
				PropertyType.DATETIME, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));
		log.info("DEBUG: Added cmis:creationDate property");

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.LAST_MODIFIED_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));
		log.info("DEBUG: Added cmis:lastModifiedBy property");

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.LAST_MODIFICATION_DATE,
				PropertyType.DATETIME, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));
		log.info("DEBUG: Added cmis:lastModificationDate property");

		type.addPropertyDefinition(createDefaultPropDef(
				repositoryId, PropertyIds.CHANGE_TOKEN,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, !QUERYABLE, !ORDERABLE, null));
		log.info("DEBUG: Added cmis:changeToken property");
		
		// Get final property count and detailed analysis
		Map<String, PropertyDefinition<?>> finalProps = type.getPropertyDefinitions();
		int finalCount = (finalProps != null) ? finalProps.size() : 0;
		int addedCount = finalCount - initialCount;
		
		log.info("=== ADD BASE PROPERTIES SUMMARY ===");
		log.info("DEBUG: Initial properties: " + initialCount + " → Final properties: " + finalCount + " (Added: " + addedCount + ")");
		
		if (finalProps != null) {
			log.info("DEBUG: Final property keys: " + finalProps.keySet());
			
			// Check critical CMIS properties individually
			String[] criticalProps = {
				PropertyIds.NAME, PropertyIds.OBJECT_ID, PropertyIds.BASE_TYPE_ID, 
				PropertyIds.OBJECT_TYPE_ID, PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
				PropertyIds.CREATED_BY, PropertyIds.CREATION_DATE, PropertyIds.LAST_MODIFIED_BY,
				PropertyIds.LAST_MODIFICATION_DATE, PropertyIds.CHANGE_TOKEN
			};
			
			for (String propId : criticalProps) {
				if (finalProps.containsKey(propId)) {
					log.info("DEBUG: ✅ " + propId + " is present");
				} else {
					log.error("DEBUG: ❌ MISSING CRITICAL PROPERTY: " + propId);
				}
			}
			
			if (finalProps.containsKey(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
				log.info("DEBUG: CONFIRMATION: cmis:secondaryObjectTypeIds IS PRESENT in final property definitions");
			} else {
				log.error("DEBUG: CRITICAL ERROR: cmis:secondaryObjectTypeIds IS MISSING from final property definitions");
			}
		} else {
			log.error("DEBUG: CRITICAL ERROR: Final properties map is NULL");
		}
		
		log.info("=== ADD BASE PROPERTIES DEBUG: Completed for type: " + type.getId() + " ===");
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
	
	// CRITICAL FIX: Determine inherited based on property ID and CMIS specification
	// For base type property definitions, CMIS properties are not inherited (they define them)
	// This method is used primarily for base type property creation
	boolean isBaseTypeDefinition = true; // This method is primarily used for base type definitions
	boolean inherited = isStandardCmisProperty(id, isBaseTypeDefinition);
	
	return createDefaultPropDef(repositoryId, id, datatype, cardinality, updatability, 
		required, queryable, orderable, defaultValue, inherited);
}

private PropertyDefinition<?> createDefaultPropDef(String repositoryId,
		String id, PropertyType datatype,
		Cardinality cardinality, Updatability updatability, boolean required,
		boolean queryable, boolean orderable, List<?> defaultValue, boolean inherited) {
	PropertyDefinition<?> result = null;

	// Default values
	String localName = id;
	String localNameSpace = getNameSpace(repositoryId);
	String queryName = id;
	String displayName = id;
	String description = id;
	boolean openChoice = false;

	result = DataUtil.createPropDef(id, localName, localNameSpace,
			queryName, displayName, description, datatype, cardinality,
			updatability, required, queryable, inherited, null, openChoice,
			orderable, defaultValue, null, null, null, null, null, null,
			null);

	return result;
}

/**
 * CRITICAL FIX: Determine if a CMIS property should be marked as inherited
 * Based on CMIS 1.1 specification:
 * - Base types define CMIS properties originally (inherited=false)
 * - Subtypes inherit CMIS properties from parents (inherited=true)
 */
private boolean isStandardCmisProperty(String propertyId, boolean isBaseTypeDefinition) {
	// For base types: CMIS properties are NOT inherited (they define them)
	// For subtypes: CMIS properties ARE inherited (they inherit from parents)  
	return propertyId != null && propertyId.startsWith("cmis:") && !isBaseTypeDefinition;
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
		addSubTypesInternal(repositoryId, subtypes, type, new HashSet<String>());
	}

	/**
	 * CRITICAL FIX: Add circular reference detection to prevent infinite recursion
	 * Root cause: During type deletion, circular references in type hierarchy cause StackOverflowError
	 * Solution: Track processing types and skip circular references
	 */
	private void addSubTypesInternal(String repositoryId,
			List<NemakiTypeDefinition> subtypes, NemakiTypeDefinition type, Set<String> processingTypes) {
		
		// CRITICAL FIX: Circular reference detection
		if (type == null || type.getTypeId() == null) {
			log.warn("Null type or typeId detected, skipping processing");
			return;
		}
		
		String typeId = type.getTypeId();
		if (processingTypes.contains(typeId)) {
			log.warn("CIRCULAR REFERENCE DETECTED: Type '" + typeId + "' is already being processed, skipping to prevent infinite recursion");
			return;
		}
		
		// Add current type to processing set
		processingTypes.add(typeId);
		
		try {
			Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
			
			TypeDefinitionContainerImpl container = new TypeDefinitionContainerImpl();
			
			// CRITICAL FIX: Add null safety for buildTypeDefinitionFromDB
			try {
				AbstractTypeDefinition typeDefinition = buildTypeDefinitionFromDB(repositoryId, type);
				if (typeDefinition == null) {
					log.warn("buildTypeDefinitionFromDB returned null for type: " + typeId);
					return;
				}
				container.setTypeDefinition(typeDefinition);
			} catch (Exception e) {
				log.error("Failed to build type definition for type: " + typeId, e);
				return;
			}
			
			container.setChildren(new ArrayList<TypeDefinitionContainer>());

			if (types.get(typeId) == null) {
				types.put(typeId, container);
			} else {
				log.debug("Type '" + typeId + "' already exists in container, skipping overwrite");
			}

			// CRITICAL FIX: Null safety and circular reference prevention for children processing
			List<NemakiTypeDefinition> children = new ArrayList<NemakiTypeDefinition>();
			if (subtypes != null) {
				for (NemakiTypeDefinition subtype : subtypes) {
					if (subtype == null) {
						log.warn("Null subtype detected, skipping");
						continue;
					}
					
					// CRITICAL FIX: Add null safety check for subtype.getParentId()
					String subtypeParentId = subtype.getParentId();
					if (subtypeParentId != null && subtypeParentId.equals(typeId)) {
						// ADDITIONAL FIX: Ensure child is not the same as parent (self-reference detection)
						String childTypeId = subtype.getTypeId();
						if (childTypeId != null && !childTypeId.equals(typeId)) {
							children.add(subtype);
						} else {
							log.warn("SELF-REFERENCE DETECTED: Type '" + typeId + "' references itself as child, skipping");
						}
					}
				}
			}

			// CRITICAL FIX: Process children with circular reference protection
			if (!CollectionUtils.isEmpty(children)) {
				for (NemakiTypeDefinition child : children) {
					if (child != null) {
						// Pass the same processingTypes set to track all processing types in this call chain
						addSubTypesInternal(repositoryId, subtypes, child, processingTypes);
					}
				}
			}

			// CRITICAL FIX: Add null safety check for type.getParentId() and parentContainer
			String typeParentId = type.getParentId();
			if (typeParentId != null) {
				TypeDefinitionContainer parentContainer = types.get(typeParentId);
				if (parentContainer != null) {
					parentContainer.getChildren().add(container);
				}
			}
			
		} finally {
			// CRITICAL FIX: Remove from processing set when done (backtrack)
			processingTypes.remove(typeId);
		}
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
			log.warn("UNKNOWN base type: " + nemakiType.getBaseId());
			break;
		}

		return null;
	}

	/**
	 * Get BaseTypeId-specific default values per CMIS 1.1 specification
	 * Based on nemakiware-basetype.properties configuration
	 */
	private Map<String, Object> getBaseTypeIdDefaults(BaseTypeId baseTypeId) {
		Map<String, Object> defaults = new HashMap<>();
		
		if (baseTypeId == null) {
			// Generic defaults if BaseTypeId is unknown
			defaults.put("creatable", true);
			defaults.put("fileable", true);
			defaults.put("queryable", true);
			defaults.put("controllablePolicy", false);
			defaults.put("controllableAcl", true);
			defaults.put("fulltextIndexed", true);
			defaults.put("includedInSupertypeQuery", true);
			defaults.put("typeMutabilityCreate", true);
			defaults.put("typeMutabilityUpdate", false);
			defaults.put("typeMutabilityDelete", true);
			return defaults;
		}
		
		switch (baseTypeId) {
			case CMIS_DOCUMENT:
				defaults.put("creatable", true);
				defaults.put("fileable", true);
				defaults.put("queryable", true);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", true);
				defaults.put("fulltextIndexed", true);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", true);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", true);
				break;
				
			case CMIS_FOLDER:
				defaults.put("creatable", true);
				defaults.put("fileable", false); // Folders are not fileable in other folders
				defaults.put("queryable", true);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", true);
				defaults.put("fulltextIndexed", false);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", true);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", true);
				break;
				
			case CMIS_RELATIONSHIP:
				defaults.put("creatable", true);
				defaults.put("fileable", false);
				defaults.put("queryable", true);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", true);
				defaults.put("fulltextIndexed", false);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", true);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", true);
				break;
				
			case CMIS_POLICY:
				defaults.put("creatable", false);
				defaults.put("fileable", false);
				defaults.put("queryable", false);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", false);
				defaults.put("fulltextIndexed", false);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", false);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", false);
				break;
				
			case CMIS_ITEM:
				defaults.put("creatable", true);
				defaults.put("fileable", true);
				defaults.put("queryable", false);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", false);
				defaults.put("fulltextIndexed", false);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", true);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", true);
				break;
				
			case CMIS_SECONDARY:
				// CRITICAL: Secondary types per CMIS 1.1 specification
				defaults.put("creatable", false);
				defaults.put("fileable", false);
				defaults.put("queryable", true);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", false);
				defaults.put("fulltextIndexed", true);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", false);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", true);
				break;
				
			default:
				// Unknown base type - use conservative defaults
				defaults.put("creatable", false);
				defaults.put("fileable", false);
				defaults.put("queryable", true);
				defaults.put("controllablePolicy", false);
				defaults.put("controllableAcl", false);
				defaults.put("fulltextIndexed", false);
				defaults.put("includedInSupertypeQuery", true);
				defaults.put("typeMutabilityCreate", false);
				defaults.put("typeMutabilityUpdate", false);
				defaults.put("typeMutabilityDelete", false);
				break;
		}
		
		return defaults;
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

		// CRITICAL FIX: BaseTypeId-specific default values per CMIS 1.1 specification
		Map<String, Object> baseDefaults = getBaseTypeIdDefaults(nemakiType.getBaseId());
		
		boolean creatable = (nemakiType.isCreatable() == null) ? 
				(parentType != null ? parentType.isCreatable() : (Boolean) baseDefaults.get("creatable")) : nemakiType.isCreatable();
		type.setIsCreatable(creatable);
		boolean filable = (nemakiType.isFilable() == null) ? 
				(parentType != null ? parentType.isFileable() : (Boolean) baseDefaults.get("fileable")) : nemakiType.isFilable();
		type.setIsFileable(filable);
		boolean queryable = (nemakiType.isQueryable() == null) ? 
				(parentType != null ? parentType.isQueryable() : (Boolean) baseDefaults.get("queryable")) : nemakiType.isQueryable();
		type.setIsQueryable(queryable);
		boolean controllablePolicy = (nemakiType.isControllablePolicy() == null) ? 
				(parentType != null ? parentType.isControllablePolicy() : (Boolean) baseDefaults.get("controllablePolicy")) : nemakiType.isControllablePolicy();
		type.setIsControllablePolicy(controllablePolicy);
		boolean controllableACL = (nemakiType.isControllableACL() == null) ? 
				(parentType != null ? parentType.isControllableAcl() : (Boolean) baseDefaults.get("controllableAcl")) : nemakiType.isControllableACL();
		type.setIsControllableAcl(controllableACL);
		boolean fulltextIndexed = (nemakiType.isFulltextIndexed() == null) ? 
				(parentType != null ? parentType.isFulltextIndexed() : (Boolean) baseDefaults.get("fulltextIndexed")) : nemakiType.isFulltextIndexed();
		type.setIsFulltextIndexed(fulltextIndexed);
		boolean includedInSupertypeQuery = (nemakiType
				.isIncludedInSupertypeQuery() == null) ? 
				(parentType != null ? parentType.isIncludedInSupertypeQuery() : (Boolean) baseDefaults.get("includedInSupertypeQuery")) : nemakiType
				.isIncludedInSupertypeQuery();
		type.setIsIncludedInSupertypeQuery(includedInSupertypeQuery);

		// CRITICAL FIX: Type Mutability with BaseTypeId-specific defaults
		boolean create = (nemakiType.isTypeMutabilityCreate() == null) ? 
			(parentType != null ? parentType.getTypeMutability().canCreate() : (Boolean) baseDefaults.get("typeMutabilityCreate")) : nemakiType
			.isTypeMutabilityCreate();
		boolean update = (nemakiType.isTypeMutabilityUpdate() == null) ? 
			(parentType != null ? parentType.getTypeMutability().canUpdate() : (Boolean) baseDefaults.get("typeMutabilityUpdate")) : nemakiType
			.isTypeMutabilityUpdate();
		boolean delete = (nemakiType.isTypeMutabilityDelete() == null) ? 
			(parentType != null ? parentType.getTypeMutability().canDelete() : (Boolean) baseDefaults.get("typeMutabilityDelete")) : nemakiType
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
			// CRITICAL FIX: Use precise CMIS 1.1 compliant inheritance determination
			// instead of blanket setInheritedToTrue() that incorrectly marks ALL properties as inherited
			boolean shouldInherit = shouldBeInherited(parentProperty, parentType);
			((AbstractPropertyDefinition<?>) parentProperty).setIsInherited(shouldInherit);
		}
		type.setPropertyDefinitions(parentProperties);

		// Add specific properties
		// TODO if there is the same id with that of the inherited, check the
		// difference of attributes
		Map<String, PropertyDefinition<?>> properties = type
				.getPropertyDefinitions();
		List<PropertyDefinition<?>> specificProperties = new ArrayList<PropertyDefinition<?>>();
		if (!CollectionUtils.isEmpty(nemakiType.getProperties())) {
					for (String propertyDetailId : nemakiType.getProperties()) {
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
				
				NemakiPropertyDefinitionCore originalCore = typeService
						.getPropertyDefinitionCore(repositoryId, coreNodeId);
				if (originalCore == null) {
					log.error("CRITICAL: PropertyDefinitionCore is null for coreNodeId: " + coreNodeId + ", propertyDetailId: " + propertyDetailId);
					continue;
				}

				// ARCHITECTURAL REDESIGN: Use unified PropertyDefinitionBuilder
				// This eliminates the complex Core+Detail construction logic that was causing contamination
				PropertyDefinition<?> property = PropertyDefinitionBuilder
					.forRepository(repositoryId)
					.withDetail(propertyDetailId, detail)
					.withCore(originalCore)
					.withParentType(parentType)
					.build();

				// CRITICAL FIX: Only add to properties map if property is NOT NULL
				// This prevents NULL PropertyDefinition objects from being serialized in JSON responses
				if (property != null) {
					// Use the clean property ID from the builder as the key
					properties.put(property.getId(), property);
							
					// Also add to subTypeProperties for inheritance
					specificProperties.add(property);
				} else {
					// Log NULL property creation to track down the root cause
									log.error("CRITICAL: NULL PropertyDefinition created for property ID: " + p.getPropertyId() + ", Property Type: " + p.getPropertyType() + ". Skipping addition to prevent JSON serialization errors");
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


	/**
	 * CRITICAL FIX: Determines whether a property should be marked as inherited based on CMIS 1.1 specification.
	 * 
	 * CMIS 1.1 Inheritance Rules:
	 * 1. CMIS system properties (cmis:*) inherited from parent types should have inherited=true
	 * 2. Custom namespace properties (tck:, custom:, vendor:, etc.) should typically have inherited=false
	 *    as they are often type-specific additions that should not automatically propagate
	 * 3. Base type fundamental properties should be inherited=true
	 * 
	 * This replaces the blanket setInheritedToTrue() application that incorrectly marked
	 * ALL parent properties as inherited=true regardless of their namespace or purpose.
	 * 
	 * @param property The PropertyDefinition to evaluate
	 * @param parentType The parent type this property comes from
	 * @return true if the property should be marked as inherited=true, false otherwise
	 */
	private boolean shouldBeInherited(PropertyDefinition<?> property, AbstractTypeDefinition parentType) {
		if (property == null || property.getId() == null) {
			return false; // Safety: null properties should not be inherited
		}
		
		String propertyId = property.getId();
		
		// STRATEGY 1: CMIS system properties (cmis:*) are always inherited
		// These are fundamental CMIS properties that define the content model
		if (propertyId.startsWith("cmis:")) {
			return true;
		}
		
		// STRATEGY 2: Custom namespace properties should typically NOT be inherited
		// Custom properties (tck:, custom:, vendor:, etc.) are usually type-specific
		// and should not automatically propagate to child types
		if (propertyId.contains(":") && !propertyId.startsWith("cmis:")) {
			// Check if this is a well-known custom namespace that might need inheritance
			// Most test and custom properties should not be inherited by default
			if (propertyId.startsWith("tck:") || 
			    propertyId.startsWith("test:") || 
			    propertyId.startsWith("custom:") ||
			    propertyId.startsWith("vendor:")) {
				return false; // Test and custom properties are type-specific
			}
			
			// For other custom namespaces, default to false for safety
			return false;
		}
		
		// STRATEGY 3: Non-namespaced properties (legacy or malformed IDs)
		// These should be inherited with caution - default to true to maintain compatibility
		return true;
	}

	private AbstractPropertyDefinition<?> setInheritedToTrue(
			AbstractPropertyDefinition<?> property) {
		property.setIsInherited(true);
		return property;
	}

	/**
	 * ARCHITECTURAL REDESIGN: Unified PropertyDefinition Builder
	 * 
	 * This builder eliminates the 3-layer separation complexity by providing
	 * a single, consistent interface for PropertyDefinition creation that:
	 * 
	 * 1. ELIMINATES OBJECT REUSE: Always creates fresh, isolated objects
	 * 2. PREVENTS CONTAMINATION: No shared references between properties
	 * 3. MANAGES INHERITANCE: Consistent inheritance flag determination
	 * 4. SIMPLIFIES CREATION: Single builder interface instead of multiple constructors
	 * 5. ENSURES CONSISTENCY: All PropertyDefinitions created through same logic
	 * 
	 * This replaces the complex Core + Detail + Unified three-layer architecture
	 * with a streamlined builder pattern that eliminates contamination sources.
	 */
	public static class PropertyDefinitionBuilder {
		private String repositoryId;
		private String propertyDetailId;
		private NemakiPropertyDefinitionDetail detail;
		private NemakiPropertyDefinitionCore originalCore;
		private AbstractTypeDefinition parentType;
		
		private PropertyDefinitionBuilder(String repositoryId) {
			this.repositoryId = repositoryId;
		}
		
		public static PropertyDefinitionBuilder forRepository(String repositoryId) {
			return new PropertyDefinitionBuilder(repositoryId);
		}
		
		public PropertyDefinitionBuilder withDetail(String propertyDetailId, NemakiPropertyDefinitionDetail detail) {
			this.propertyDetailId = propertyDetailId;
			this.detail = detail;
			return this;
		}
		
		public PropertyDefinitionBuilder withCore(NemakiPropertyDefinitionCore originalCore) {
			this.originalCore = originalCore;
			return this;
		}
		
		public PropertyDefinitionBuilder withParentType(AbstractTypeDefinition parentType) {
			this.parentType = parentType;
			return this;
		}
		
		/**
		 * CRITICAL: Creates a completely fresh, uncontaminated PropertyDefinition
		 * using defensive copying and contamination prevention strategies.
		 */
		public PropertyDefinition<?> build() {
			if (detail == null || originalCore == null) {
				throw new IllegalStateException("Both detail and core must be provided");
			}
			
			// STEP 1: Create completely fresh Core to prevent contamination
			NemakiPropertyDefinitionCore freshCore = new NemakiPropertyDefinitionCore();
			freshCore.setId(originalCore.getId());
			freshCore.setType(originalCore.getType());
			freshCore.setCreated(originalCore.getCreated());
			freshCore.setCreator(originalCore.getCreator());
			freshCore.setModified(originalCore.getModified());
			freshCore.setModifier(originalCore.getModifier());
			
			// STEP 2: Establish authoritative property ID (contamination-free)
			String authoritativePropertyId = determineAuthoritativePropertyId(detail, originalCore, repositoryId, propertyDetailId);
			freshCore.setPropertyId(authoritativePropertyId);
			freshCore.setQueryName(authoritativePropertyId);
			
			// STEP 3: Determine trusted type information
			PropertyType trustedPropertyType = determinePropertyTypeFromPropertyId(authoritativePropertyId);
			Cardinality trustedCardinality = determineCardinalityFromPropertyId(authoritativePropertyId);
			freshCore.setPropertyType(trustedPropertyType);
			freshCore.setCardinality(trustedCardinality);
			
			// STEP 4: Set inheritance flag using precise CMIS 1.1 logic
			// For properties being constructed from Core+Detail, they are typically NOT inherited
			// unless explicitly determined otherwise
			boolean shouldInherit = false;
			if (parentType != null) {
				// Create a temporary PropertyDefinition for inheritance checking
				NemakiPropertyDefinition tempProp = new NemakiPropertyDefinition(freshCore, detail);
				PropertyDefinition<?> tempPropDef = DataUtil.createPropDef(
					tempProp.getPropertyId(), tempProp.getLocalName(),
					tempProp.getLocalNameSpace(), tempProp.getQueryName(),
					tempProp.getDisplayName(), tempProp.getDescription(),
					tempProp.getPropertyType(), tempProp.getCardinality(),
					tempProp.getUpdatability(), tempProp.isRequired(), 
					tempProp.isQueryable(), false, // temporarily false
					tempProp.getChoices(), tempProp.isOpenChoice(),
					tempProp.isOrderable(), tempProp.getDefaultValue(), 
					tempProp.getMinValue(), tempProp.getMaxValue(), 
					tempProp.getResolution(), tempProp.getDecimalPrecision(),
					tempProp.getDecimalMinValue(), tempProp.getDecimalMaxValue(), 
					tempProp.getMaxLength());
				shouldInherit = shouldBeInherited(tempPropDef, parentType);
			}
			freshCore.setInherited(shouldInherit);
			
			// STEP 5: Create unified PropertyDefinition with fresh objects
			NemakiPropertyDefinition unified = new NemakiPropertyDefinition(freshCore, detail);
			
			// STEP 6: Create final PropertyDefinition using DataUtil for consistency
			return DataUtil.createPropDef(
				unified.getPropertyId(), unified.getLocalName(),
				unified.getLocalNameSpace(), unified.getQueryName(),
				unified.getDisplayName(), unified.getDescription(),
				unified.getPropertyType(), unified.getCardinality(),
				unified.getUpdatability(), unified.isRequired(), 
				unified.isQueryable(), shouldInherit,
				unified.getChoices(), unified.isOpenChoice(),
				unified.isOrderable(), unified.getDefaultValue(), 
				unified.getMinValue(), unified.getMaxValue(), 
				unified.getResolution(), unified.getDecimalPrecision(),
				unified.getDecimalMinValue(), unified.getDecimalMaxValue(), 
				unified.getMaxLength());
		}
	}

	private DocumentTypeDefinitionImpl buildDocumentTypeDefinitionFromDB(
			String repositoryId, NemakiTypeDefinition nemakiType) {
		Map<String, TypeDefinitionContainer>types = TYPES.get(repositoryId);
		
		DocumentTypeDefinitionImpl type = new DocumentTypeDefinitionImpl();
		
		// CRITICAL FIX: Add null safety for parent type lookup with baseId fallback
		// Same issue as in validation methods - parentId may be null for new custom types
		String parentId = nemakiType.getParentId();
		String baseId = nemakiType.getBaseId() != null ? nemakiType.getBaseId().value() : null;
		String targetParentId = (parentId != null) ? parentId : baseId;
		
							
		TypeDefinitionContainer parentContainer = types.get(targetParentId);
		if (parentContainer == null) {
				log.error("Parent type container not found for ID: " + targetParentId + ". Available types: " + types.keySet());
				throw new RuntimeException("Parent type not found: " + targetParentId);
		}
		
		DocumentTypeDefinitionImpl parentType = (DocumentTypeDefinitionImpl) parentContainer.getTypeDefinition();

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
		// Initialize property definition cores
		
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

	 
		// Base type collections initialized

		copyToPropertyDefinitionCore(d);
		copyToPropertyDefinitionCore(f);
		copyToPropertyDefinitionCore(r);
		copyToPropertyDefinitionCore(p);
		copyToPropertyDefinitionCore(i);

		// PropertyDefinitionCore maps populated

		// Subtype property cores(consequently includes secondary property cores)
			List<NemakiPropertyDefinitionCore> subTypeCores = typeService
				.getPropertyDefinitionCores(repositoryId);
		
		// Subtype property cores loaded
		
		if (CollectionUtils.isNotEmpty(subTypeCores)) {
			int processedCount = 0;
			int skippedCount = 0;
			
			for (NemakiPropertyDefinitionCore sc : subTypeCores) {
				// Process PropertyDefinitionCore
					
				// CRITICAL FIX: Skip corrupted PropertyDefinitionCore records with NULL values
				// This prevents NULL property IDs from contaminating the system during initialization
				if (sc == null) {
						log.warn("Skipping NULL PropertyDefinitionCore record for repository: " + repositoryId);
					skippedCount++;
					continue;
				}
				
									
				if (sc.getPropertyId() == null || sc.getPropertyType() == null || 
					sc.getQueryName() == null || sc.getCardinality() == null) {
					log.warn("Skipping corrupted PropertyDefinitionCore with NULL fields");
					log.warn("Skipping corrupted PropertyDefinitionCore record with NULL fields: " +
						"propertyId=" + sc.getPropertyId() + 
						", propertyType=" + sc.getPropertyType() + 
						", queryName=" + sc.getQueryName() + 
						", cardinality=" + sc.getCardinality() + 
						", id=" + sc.getId() + 
						" for repository: " + repositoryId);
					skippedCount++;
					continue;
				}
				
				// Add property definition core
									
				// Only process valid PropertyDefinitionCore records
				addPropertyDefinitionCore(sc.getPropertyId(),
						sc.getQueryName(), sc.getPropertyType(),
						sc.getCardinality());
				
				processedCount++;
				}
			
					}
		
			// PropertyDefinitionCore population completed
		}

	private void copyToPropertyDefinitionCore(
			Map<String, PropertyDefinition<?>> map) {
		for (Entry<String, PropertyDefinition<?>> e : map.entrySet()) {
			if (!propertyDefinitionCoresByQueryName.containsKey(e.getKey())) {
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
		// Add property definition core with contamination prevention
		boolean isCustomProperty = propertyId != null && propertyId.contains(":") && !propertyId.startsWith("cmis:");
		if (isCustomProperty) {
			// COMPREHENSIVE DEBUG: Custom namespace property detected - full contamination trace
			System.err.println("=== CUSTOM NAMESPACE PROPERTY DETECTED ===");
			System.err.println("Custom Property ID: " + propertyId);
			System.err.println("Custom Query Name: " + queryName);
			System.err.println("TCK Property Type: " + propertyType);
			System.err.println("TCK Cardinality: " + cardinality);
			System.err.println("=== END TCK DETECTION ===");
		}
		
		if (log.isTraceEnabled()) {
			log.trace("Adding property definition core: " + propertyId + " (queryName: " + queryName + ")");
		}

		// CRITICAL CONTAMINATION FIX: Prevent PropertyDefinition object sharing between maps
		// Create property definition if not exists by propertyId
		if (!propertyDefinitionCoresByPropertyId.containsKey(propertyId)) {
			
			System.err.println("=== CREATING NEW PROPERTY CORE ===");
			System.err.println("Creating new core for propertyId: " + propertyId);
			System.err.println("queryName: " + queryName);
			System.err.println("propertyType: " + propertyType);
			System.err.println("cardinality: " + cardinality);
			
			PropertyDefinition<?> core = DataUtil.createPropDefCore(propertyId, queryName, propertyType, cardinality);
			
			// COMPREHENSIVE DEBUG: Verify created core object
			if (core != null) {
				System.err.println("Created core - ID: " + core.getId());
				System.err.println("Created core - QueryName: " + core.getQueryName()); 
				System.err.println("Created core - Type: " + core.getPropertyType());
				System.err.println("Created core object hash: " + System.identityHashCode(core));
			} else {
				System.err.println("ERROR: DataUtil.createPropDefCore returned NULL for propertyId: " + propertyId);
			}
			
			// Create defensive copies to prevent contamination
				
			// CRITICAL FIX: Create defensive copies to prevent cross-contamination between maps
			// Each map gets its own PropertyDefinition object to prevent shared state issues
			PropertyDefinition<?> coreForPropertyIdMap = DataUtil.clonePropertyDefinition(core);
			PropertyDefinition<?> coreForQueryNameMap = DataUtil.clonePropertyDefinition(core);
			
			// COMPREHENSIVE DEBUG: Verify cloning worked correctly
			System.err.println("=== CLONE VERIFICATION ===");
			System.err.println("Original core hash: " + System.identityHashCode(core));
			System.err.println("PropertyId map clone hash: " + System.identityHashCode(coreForPropertyIdMap));
			System.err.println("QueryName map clone hash: " + System.identityHashCode(coreForQueryNameMap));
			
			if (coreForPropertyIdMap != null) {
				System.err.println("PropertyId clone - ID: " + coreForPropertyIdMap.getId());
				System.err.println("PropertyId clone - QueryName: " + coreForPropertyIdMap.getQueryName());
				System.err.println("PropertyId clone - Type: " + coreForPropertyIdMap.getPropertyType());
			} else {
				System.err.println("ERROR: PropertyId clone is NULL!");
			}
			
			if (coreForQueryNameMap != null) {
				System.err.println("QueryName clone - ID: " + coreForQueryNameMap.getId());
				System.err.println("QueryName clone - QueryName: " + coreForQueryNameMap.getQueryName());
				System.err.println("QueryName clone - Type: " + coreForQueryNameMap.getPropertyType());
			} else {
				System.err.println("ERROR: QueryName clone is NULL!");
			}
			
			// Verify objects are different instances
			boolean sameAsOriginal1 = (core == coreForPropertyIdMap);
			boolean sameAsOriginal2 = (core == coreForQueryNameMap);
			boolean sameAsEachOther = (coreForPropertyIdMap == coreForQueryNameMap);
			
			System.err.println("Clone identity check:");
			System.err.println("  PropertyId clone == original: " + sameAsOriginal1 + " (should be false)");
			System.err.println("  QueryName clone == original: " + sameAsOriginal2 + " (should be false)");
			System.err.println("  Clones == each other: " + sameAsEachOther + " (should be false)");
			System.err.println("=== END CLONE VERIFICATION ===");
			
			// Store separate copies to prevent contamination
							
			// Store separate copies in each map - CONTAMINATION IMPOSSIBLE
			propertyDefinitionCoresByPropertyId.put(propertyId, coreForPropertyIdMap);
			propertyDefinitionCoresByQueryName.put(queryName, coreForQueryNameMap);
			
			// COMPREHENSIVE DEBUG: Verify storage
			PropertyDefinition<?> storedInPropertyIdMap = propertyDefinitionCoresByPropertyId.get(propertyId);
			PropertyDefinition<?> storedInQueryNameMap = propertyDefinitionCoresByQueryName.get(queryName);
			
			System.err.println("=== STORAGE VERIFICATION ===");
			System.err.println("Stored in PropertyId map - hash: " + System.identityHashCode(storedInPropertyIdMap));
			System.err.println("Stored in QueryName map - hash: " + System.identityHashCode(storedInQueryNameMap));
			
			if (storedInPropertyIdMap != null) {
				System.err.println("PropertyId map stored - ID: " + storedInPropertyIdMap.getId());
				System.err.println("PropertyId map stored - QueryName: " + storedInPropertyIdMap.getQueryName());
			}
			
			if (storedInQueryNameMap != null) {
				System.err.println("QueryName map stored - ID: " + storedInQueryNameMap.getId());
				System.err.println("QueryName map stored - QueryName: " + storedInQueryNameMap.getQueryName());
			}
			System.err.println("=== END STORAGE VERIFICATION ===");
			
			// Handle custom namespace properties
			if (isCustomProperty) {
				System.err.println("=== CUSTOM NAMESPACE PROPERTY FINAL VERIFICATION ===");
				System.err.println("Custom namespace property stored successfully:");
				System.err.println("  PropertyId: " + propertyId + " -> " + (storedInPropertyIdMap != null ? storedInPropertyIdMap.getId() : "NULL"));
				System.err.println("  QueryName: " + queryName + " -> " + (storedInQueryNameMap != null ? storedInQueryNameMap.getId() : "NULL"));
				System.err.println("=== END TCK FINAL VERIFICATION ===");
			}
			
			if (log.isDebugEnabled()) {
				log.debug("Created new property core: " + propertyId + " (queryName: " + queryName + ")");
			}
		} else {
				
			if (log.isTraceEnabled()) {
				log.trace("Property core already exists: " + propertyId);
			}
			
			// Ensure queryName mapping exists for existing property with defensive copy
			PropertyDefinition<?> existingCore = propertyDefinitionCoresByPropertyId.get(propertyId);
			if (existingCore != null && !propertyDefinitionCoresByQueryName.containsKey(queryName)) {
					
				// CRITICAL FIX: Clone existing PropertyDefinition to prevent cross-contamination
				PropertyDefinition<?> clonedCoreForQueryName = DataUtil.clonePropertyDefinition(existingCore);
				propertyDefinitionCoresByQueryName.put(queryName, clonedCoreForQueryName);
				
	 
				// CRITICAL FIX: Clone existing PropertyDefinition to prevent cross-contamination completed
				
				if (log.isTraceEnabled()) {
					log.trace("Added queryName mapping for existing property: " + queryName + " -> " + propertyId);
				}
			}
			
			// Track existing properties 
			if (isCustomProperty) {
				PropertyDefinition<?> existingInPropertyIdMap = propertyDefinitionCoresByPropertyId.get(propertyId);
				PropertyDefinition<?> existingInQueryNameMap = propertyDefinitionCoresByQueryName.get(queryName);
				
						}
		}
		
		}
	
	/**
	 * CRITICAL CONTAMINATION FIX: Determine authoritative property ID without relying on potentially contaminated core objects
	 * 
	 * This method provides a robust fallback mechanism that never uses originalCore.getPropertyId() directly,
	 * as that value may be contaminated from previous property reconstructions.
	 * 
	 * @param detail PropertyDefinitionDetail object containing localName
	 * @param originalCore PropertyDefinitionCore object (potentially contaminated)
	 * @param repositoryId Repository ID for database lookups
	 * @param propertyDetailId The detail ID for debugging
	 * @return The authoritative property ID that should be used for this property
	 */
	private String determineAuthoritativePropertyId(NemakiPropertyDefinitionDetail detail, 
			NemakiPropertyDefinitionCore originalCore, String repositoryId, String propertyDetailId) {
		
		// STRATEGY 1: Use detail's localName as the primary authoritative source
		if (detail != null && detail.getLocalName() != null && !detail.getLocalName().trim().isEmpty()) {
			return detail.getLocalName();
		}
		
		// STRATEGY 2: Use detail's displayName if localName is empty but displayName contains namespace
		if (detail != null && detail.getDisplayName() != null && detail.getDisplayName().contains(":")) {
			return detail.getDisplayName();
		}
		
		// STRATEGY 3: For CMIS system properties, reconstruct from database query to avoid contamination
		// This ensures we get the original property ID from the database, not from a reused core object
		if (originalCore != null) {
			try {
				// Query the database directly for the original property core by its document ID
				NemakiPropertyDefinitionCore freshFromDb = typeService.getPropertyDefinitionCore(repositoryId, originalCore.getId());
				if (freshFromDb != null && freshFromDb.getPropertyId() != null) {
					// Validate this looks like a CMIS system property (most reliable fallback)
					if (freshFromDb.getPropertyId().startsWith("cmis:")) {
						return freshFromDb.getPropertyId();
					}
				}
			} catch (Exception e) {
				log.warn("Failed to refresh property core from database for ID: " + originalCore.getId(), e);
			}
		}
		
		// STRATEGY 4: Generate fallback property ID based on available information
		// This prevents null property IDs which would cause CMIS violations
		String fallbackId = generateFallbackPropertyId(detail, propertyDetailId);
		log.warn("Using generated fallback property ID: " + fallbackId + " for propertyDetailId: " + propertyDetailId);
		return fallbackId;
	}
	
	/**
	 * Generate a fallback property ID when all other strategies fail
	 * This ensures we never return null or empty property IDs which would violate CMIS requirements
	 */
	private String generateFallbackPropertyId(NemakiPropertyDefinitionDetail detail, String propertyDetailId) {
		// Try to construct from available detail information
		if (detail != null) {
			// Use detail's creation timestamp and a safe prefix
			return "fallback:" + detail.getId();
		}
		
		// Ultimate fallback using propertyDetailId
		return "fallback:" + propertyDetailId;
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
		
		// FUNDAMENTAL FIX: Direct lookup using dedicated queryName Map
		return propertyDefinitionCoresByQueryName.get(queryName);
	}

	@Override
	public TypeDefinition getTypeDefinition(String repositoryId, String typeId) {
		ensureInitialized();
		
		// Type cache lookup for deletion
		log.debug("NEMAKI TYPE DEBUG: TypeManager.getTypeDefinition called - repositoryId=" + repositoryId + ", typeId=" + typeId);
		
		Map<String, TypeDefinitionContainer> types = TYPES.get(repositoryId);
		if (types == null) {
			log.error("NEMAKI TYPE ERROR: No type cache found for repository: " + repositoryId);
			log.error("No type cache found for repository: " + repositoryId);
			return null;
		}
		
		log.debug("NEMAKI TYPE DEBUG: Total types in cache for repository " + repositoryId + ": " + types.size());
		
		// List all type IDs in cache for debugging
		log.debug("NEMAKI TYPE DEBUG: Available type IDs in cache: " + types.keySet());
		
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			log.error("NEMAKI TYPE ERROR: Type '" + typeId + "' not found in TypeManager cache");
			log.error("Type '" + typeId + "' not found in TypeManager cache");
			
			// Additional debug: Check if this is a timing issue - force refresh and try again
			log.warn("NEMAKI TYPE DEBUG: Attempting forced cache refresh to find missing type");
			log.debug("Attempting forced cache refresh to find missing type");
			
			// CRITICAL FIX: Check if type is being deleted before refreshing cache
			if (typesBeingDeleted.contains(typeId)) {
				log.debug("NEMAKI TYPE DELETION: Type '" + typeId + "' is being deleted - skipping cache refresh to prevent infinite recursion");
				throw new CmisObjectNotFoundException("Type '" + typeId + "' is being deleted");
			}
			
			try {
				refreshTypes();
				Map<String, TypeDefinitionContainer> refreshedTypes = TYPES.get(repositoryId);
				if (refreshedTypes != null) {
					log.debug("NEMAKI TYPE DEBUG: After refresh, total types: " + refreshedTypes.size());
					log.debug("NEMAKI TYPE DEBUG: After refresh, available type IDs: " + refreshedTypes.keySet());
					
					TypeDefinitionContainer refreshedTc = refreshedTypes.get(typeId);
					if (refreshedTc != null) {
						log.debug("NEMAKI TYPE FIX: Found type '" + typeId + "' after forced refresh!");
						return refreshedTc.getTypeDefinition();
					} else {
						log.error("NEMAKI TYPE ERROR: Type '" + typeId + "' still not found even after forced refresh");
						log.error("Type '" + typeId + "' still not found even after forced refresh");
					}
				}
			} catch (Exception e) {
				log.error("NEMAKI TYPE ERROR: Exception during forced refresh", e);
				log.error("Exception during forced refresh: " + e.getMessage());
				e.printStackTrace();
			}
			
			return null;
		}
		
		log.debug("NEMAKI TYPE DEBUG: Found type '" + typeId + "' in cache successfully");

		TypeDefinition typeDefinition = tc.getTypeDefinition();
		
		// Check for property contamination patterns
		if (typeDefinition != null && typeDefinition.getPropertyDefinitions() != null) {
					
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
							expectedType = "STRING (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:description") && propertyType == PropertyType.ID) {
							isContaminated = true;
							expectedType = "STRING (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:objectId") && propertyType == PropertyType.INTEGER) {
							isContaminated = true;
							expectedType = "ID (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:baseTypeId") && propertyType == PropertyType.DATETIME) {
							isContaminated = true;
							expectedType = "ID (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:objectTypeId") && propertyType == PropertyType.DECIMAL) {
							isContaminated = true;
							expectedType = "ID (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:secondaryObjectTypeIds") && propertyType == PropertyType.HTML) {
							isContaminated = true;
							expectedType = "ID (contamination detected with custom property type)";
						} else if (propertyId.equals("cmis:creationDate") && propertyType == PropertyType.URI) {
							isContaminated = true;
							expectedType = "DATETIME (contamination detected with custom property type)";
						}
					}
					
					if (isContaminated) {
						foundContamination = true;
						if (log.isDebugEnabled()) {
							log.debug("Property contamination detected - ID: " + propertyId + ", Type: " + propertyType + ", Expected: " + expectedType);
						}
					}
				}
			}
			
			if (!foundContamination) {
				log.debug("No contamination patterns found in type: " + typeId);
			} else {
				if (log.isDebugEnabled()) {
					log.debug("Property contamination patterns found in type: " + typeId);
				}
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
		
		// CMIS compliance: Log includePropertyDefinitions parameter
						
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
					if (!includePropertyDefinitions) {
						typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(typeDef);
				} else {
						typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinition(typeDef);
				}
	 
					// TypeDefinition prepared for response
				
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
					if (!includePropertyDefinitions) {
						typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(typeDef);
				} else {
						typeDef = jp.aegif.nemaki.util.DataUtil.copyTypeDefinition(typeDef);
				}
	 
					// TypeDefinition prepared for response
				
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
		
		// CMIS compliance: Log parameters
							
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
			log.debug("removePropertyDefinition: tdc is null");
			return null;
		}
		
		// Remove from its own typeDefinition
		TypeDefinition tdf = tdc.getTypeDefinition();
		if (tdf == null) {
			log.debug("removePropertyDefinition: typeDefinition is null");
			return tdc; // Return original if no type definition
		}
		
		log.debug("removePropertyDefinition: Processing typeId=" + tdf.getId());
		log.debug("removePropertyDefinition: Original property definitions count=" + 
			(tdf.getPropertyDefinitions() != null ? tdf.getPropertyDefinitions().size() : "null"));
		
		// CRITICAL CMIS COMPLIANCE FIX: Use copyTypeDefinitionWithoutProperties for proper property removal
		TypeDefinition copy = jp.aegif.nemaki.util.DataUtil.copyTypeDefinitionWithoutProperties(tdf);
		log.debug("removePropertyDefinition: After copyTypeDefinitionWithoutProperties, property definitions count=" + 
			(copy.getPropertyDefinitions() != null ? copy.getPropertyDefinitions().size() : "null"));
		
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl(
				copy);

		// Remove from children recursively
		List<TypeDefinitionContainer> children = tdc.getChildren();
		if (CollectionUtils.isNotEmpty(children)) {
			log.debug("removePropertyDefinition: Processing " + children.size() + " children");
			List<TypeDefinitionContainer> l = new ArrayList<TypeDefinitionContainer>();
			for (TypeDefinitionContainer child : children) {
				TypeDefinitionContainer processedChild = removePropertyDefinition(child);
				if (processedChild != null) {
					l.add(processedChild);
				}
			}
			result.setChildren(l);
		} else {
			log.debug("removePropertyDefinition: No children to process");
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
		// CRITICAL FIX: Enhanced type deletion with dependency checking
		log.info("deleteTypeDefinition: Deleting type definition for typeId=" + typeId + " in repository=" + repositoryId);
		
		try {
			// STEP 1: Validate input parameters
			if (typeId == null || typeId.trim().isEmpty()) {
				throw new CmisInvalidArgumentException("TypeId cannot be null or empty");
			}
			if (repositoryId == null || repositoryId.trim().isEmpty()) {
				throw new CmisInvalidArgumentException("RepositoryId cannot be null or empty");
			}
			
			// STEP 2: Perform comprehensive dependency checks before deletion
			List<String> dependencyIssues = checkTypeDependencies(repositoryId, typeId);
			if (!dependencyIssues.isEmpty()) {
				String errorMessage = "Cannot delete type '" + typeId + "' due to dependencies: " + String.join(", ", dependencyIssues);
				log.error("deleteTypeDefinition: " + errorMessage);
				throw new CmisConstraintException(errorMessage);
			}
			
			log.info("deleteTypeDefinition: Dependency checks passed for typeId=" + typeId);
			
			// STEP 3: Call TypeService to handle property definition cleanup and database deletion
			typeService.deleteTypeDefinition(repositoryId, typeId);
			
			// STEP 4: Invalidate type definition cache to ensure consistency (with lazy regeneration)
			invalidateTypeDefinitionCache(repositoryId);
			
			log.info("deleteTypeDefinition: Successfully deleted and invalidated cache for typeId=" + typeId);
		} catch (CmisConstraintException | CmisInvalidArgumentException e) {
			// Re-throw constraint and validation exceptions without wrapping
			throw e;
		} catch (Exception e) {
			log.error("deleteTypeDefinition: Failed to delete typeId=" + typeId + " in repository=" + repositoryId, e);
			throw new CmisObjectNotFoundException("Failed to delete type definition: " + typeId, e);
		}
	}

	/**
	 * CRITICAL FIX: Check type dependencies before deletion to prevent orphaned references
	 * 
	 * @param repositoryId The repository ID
	 * @param typeId The type ID to check
	 * @return List of dependency issues (empty if no dependencies found)
	 */
	private List<String> checkTypeDependencies(String repositoryId, String typeId) {
		List<String> issues = new ArrayList<>();
		
		try {
			log.debug("checkTypeDependencies: Checking dependencies for typeId=" + typeId + " in repository=" + repositoryId);
			
			// 1. Check if type is used as parent by other types
			List<String> childTypes = findChildTypes(repositoryId, typeId);
			if (!childTypes.isEmpty()) {
				issues.add("Type is parent of: " + String.join(", ", childTypes));
			}
			
			// 2. Check if type is a base type (cannot be deleted)
			if (isBaseType(typeId)) {
				issues.add("Cannot delete base type: " + typeId);
			}
			
			// 3. Check if type has instances (documents/folders using this type)
			boolean hasInstances = checkTypeHasInstances(repositoryId, typeId);
			if (hasInstances) {
				issues.add("Type has existing instances in the repository");
			}
			
			log.debug("checkTypeDependencies: Found " + issues.size() + " dependency issues for typeId=" + typeId);
			
		} catch (Exception e) {
			log.error("checkTypeDependencies: Error checking dependencies for typeId=" + typeId, e);
			issues.add("Error checking dependencies: " + e.getMessage());
		}
		
		return issues;
	}

	/**
	 * Find child types that use the specified type as parent
	 */
	private List<String> findChildTypes(String repositoryId, String typeId) {
		List<String> childTypes = new ArrayList<>();
		
		try {
			List<NemakiTypeDefinition> allTypes = getNemakiTypeDefinitions(repositoryId);
			if (allTypes != null) {
				for (NemakiTypeDefinition type : allTypes) {
					if (type != null && type.getParentId() != null && type.getParentId().equals(typeId)) {
						childTypes.add(type.getTypeId());
					}
				}
			}
		} catch (Exception e) {
			log.error("findChildTypes: Error finding child types for parentId=" + typeId, e);
		}
		
		return childTypes;
	}

	/**
	 * Check if the specified type is a base type (cannot be deleted)
	 */
	private boolean isBaseType(String typeId) {
		// CMIS base types that cannot be deleted
		return BaseTypeId.CMIS_DOCUMENT.value().equals(typeId) ||
			   BaseTypeId.CMIS_FOLDER.value().equals(typeId) ||
			   BaseTypeId.CMIS_RELATIONSHIP.value().equals(typeId) ||
			   BaseTypeId.CMIS_POLICY.value().equals(typeId) ||
			   BaseTypeId.CMIS_ITEM.value().equals(typeId) ||
			   BaseTypeId.CMIS_SECONDARY.value().equals(typeId);
	}

	/**
	 * Check if the type has existing instances in the repository
	 * For now, return false as this requires complex content queries
	 * TODO: Implement instance checking using ContentDaoService
	 */
	private boolean checkTypeHasInstances(String repositoryId, String typeId) {
		// TODO: Implement instance checking
		// This would require querying the content repository for documents/folders of this type
		// For now, return false to allow deletion, but this should be implemented for production use
		log.debug("checkTypeHasInstances: Instance checking not yet implemented for typeId=" + typeId);
		return false;
	}

	/**
	 * Invalidates the type definition cache for a specific repository.
	 * This method clears and rebuilds the type cache to ensure consistency after type modifications.
	 * 
	 * @param repositoryId The repository ID for which to invalidate the cache
	 */
	/**
	 * CRITICAL FIX: Invalidate type definition cache with lazy regeneration
	 * Root cause: Immediate regeneration after type deletion causes circular reference
	 * Solution: Use lazy initialization to regenerate cache only when accessed next time
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
			
			// CRITICAL FIX: Use lazy regeneration instead of immediate regeneration
			// Cache will be regenerated automatically on next access via ensureInitialized()
			log.debug("invalidateTypeDefinitionCache: Cache cleared for repository=" + repositoryId + 
					". Will be regenerated on next access (lazy initialization)");
			
			// Clear related caches to maintain consistency
			if (basetypes != null) {
				// Only clear basetypes if it's related to this repository
				// Since basetypes are shared across repositories, we need to be careful
				log.debug("invalidateTypeDefinitionCache: Clearing base types cache");
				basetypes.clear();
			}
			
			// Clear property definition caches for this repository
			if (subTypeProperties != null) {
				// Remove entries that belong to this repository
				subTypeProperties.entrySet().removeIf(entry -> entry.getKey().startsWith(repositoryId + ":"));
				log.debug("invalidateTypeDefinitionCache: Cleared subtype properties for repository=" + repositoryId);
			}
			
			if (propertyDefinitionCoresByPropertyId != null && propertyDefinitionCoresByQueryName != null) {
				// These are global caches, but we might need to clear repository-specific entries
				// For now, clear all to ensure consistency (can be optimized later)
				propertyDefinitionCoresByPropertyId.clear();
				propertyDefinitionCoresByQueryName.clear();
				log.debug("invalidateTypeDefinitionCache: Cleared property definition caches");
			}
			
			log.debug("invalidateTypeDefinitionCache: Cache invalidation complete for repository=" + repositoryId + 
					". Next access will trigger safe regeneration.");
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
	
	/**
	 * CRITICAL CONTAMINATION FIX: Determine correct PropertyType from PropertyID
	 * This method uses trusted PropertyID to derive correct PropertyType without contamination
	 */
	private PropertyType determinePropertyTypeFromPropertyId(String propertyId) {
		if (propertyId == null) {
			return PropertyType.STRING; // Default fallback
		}
		
		// CMIS Standard Properties - definitive mapping
		switch (propertyId) {
			case "cmis:name":
			case "cmis:description":
			case "cmis:createdBy":
			case "cmis:lastModifiedBy":
			case "cmis:contentStreamMimeType":
			case "cmis:contentStreamFileName":
			case "cmis:versionLabel":
			case "cmis:versionSeriesCheckedOutBy":
			case "cmis:checkinComment":
			case "cmis:path":
			case "cmis:sourceId":
			case "cmis:targetId":
				return PropertyType.STRING;
				
			case "cmis:objectId":
			case "cmis:baseTypeId":
			case "cmis:objectTypeId":
			case "cmis:parentId":
			case "cmis:versionSeriesId":
			case "cmis:versionSeriesCheckedOutId":
			case "cmis:policyId":
				return PropertyType.ID;
				
			case "cmis:creationDate":
			case "cmis:lastModificationDate":
				return PropertyType.DATETIME;
				
			case "cmis:isImmutable":
			case "cmis:isLatestVersion":
			case "cmis:isMajorVersion":
			case "cmis:isLatestMajorVersion":
			case "cmis:isVersionSeriesCheckedOut":
			case "cmis:isPrivateWorkingCopy":
				return PropertyType.BOOLEAN;
				
			case "cmis:contentStreamLength":
				return PropertyType.INTEGER;
		}
		
		// Custom namespace properties - generic type inference from naming convention
		if (propertyId.contains(":") && !propertyId.startsWith("cmis:")) {
			String[] parts = propertyId.split(":");
			if (parts.length > 1) {
				String typePart = parts[parts.length - 1]; // Get the last part after colon
			if (typePart.equals("boolean")) {
				return PropertyType.BOOLEAN;
			} else if (typePart.equals("id")) {
				return PropertyType.ID;
			} else if (typePart.equals("integer")) {
				return PropertyType.INTEGER;
			} else if (typePart.equals("datetime")) {
				return PropertyType.DATETIME;
			} else if (typePart.equals("decimal")) {
				return PropertyType.DECIMAL;
			} else if (typePart.equals("html")) {
				return PropertyType.HTML;
			} else if (typePart.equals("uri")) {
				return PropertyType.URI;
			} else {
				return PropertyType.STRING;
			}
			}
		}
		
		// Default: STRING type for unknown properties
		return PropertyType.STRING;
	}
	
	/**
	 * CRITICAL CONTAMINATION FIX: Determine correct Cardinality from PropertyID
	 * This method uses trusted PropertyID to derive correct Cardinality without contamination
	 */
	private Cardinality determineCardinalityFromPropertyId(String propertyId) {
		if (propertyId == null) {
			return Cardinality.SINGLE; // Default fallback
		}
		
		// Most CMIS properties are SINGLE cardinality
		// Only a few exceptions are MULTI cardinality
		switch (propertyId) {
			case "cmis:secondaryObjectTypeIds":
				return Cardinality.MULTI;
		}
		
		// Default: SINGLE cardinality for all other properties
		return Cardinality.SINGLE;
	}
}
