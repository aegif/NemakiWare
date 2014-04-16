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
package jp.aegif.nemaki.repository.type.impl;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.constant.PropertyKey;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.node.TypeService;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.NemakiPropertyManager;

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
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

/**
 * Type Manager class
 */
public class TypeManagerImpl implements TypeManager {

	private TypeService typeService;
	private NemakiPropertyManager propertyManager;
	private String NAMESPACE;

	/**
	 * Constant
	 */
	public final static String DOCUMENT_TYPE_ID = BaseTypeId.CMIS_DOCUMENT
			.value();
	public final static String FOLDER_TYPE_ID = BaseTypeId.CMIS_FOLDER.value();
	public final static String RELATIONSHIP_TYPE_ID = BaseTypeId.CMIS_RELATIONSHIP
			.value();
	public final static String POLICY_TYPE_ID = BaseTypeId.CMIS_POLICY.value();
	public final static String ITEM_TYPE_ID = BaseTypeId.CMIS_ITEM.value();
	public final static String SECONDARY_TYPE_ID = BaseTypeId.CMIS_SECONDARY
			.value();

	private final static boolean REQUIRED = true;
	private final static boolean QUERYABLE = true;
	private final static boolean ORDERABLE = true;

	private final static String TRUE = "true";

	/**
	 * Global variables containing type information
	 */
	// Map of all types
	private Map<String, TypeDefinitionContainer> types;

	// Map of all base types
	private Map<String, TypeDefinitionContainer> basetypes;

	// Map of subtype-specific property
	private Map<String, List<PropertyDefinition<?>>> subTypeProperties;

	// Map of propertyDefinition cores(id, name, queryName, propertyType)
	private Map<String, PropertyDefinition<?>> propertyDefinitionCoresForQueryName;

	// /////////////////////////////////////////////////
	// Constructor
	// /////////////////////////////////////////////////
	public void init() {
		NAMESPACE = propertyManager
				.readValue(PropertyKey.CMIS_REPOSITORY_MAIN_NAMESPACE);

		types = new HashMap<String, TypeDefinitionContainer>();
		basetypes = new HashMap<String, TypeDefinitionContainer>();
		subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
		propertyDefinitionCoresForQueryName = new HashMap<String, PropertyDefinition<?>>();

		generate();
	}

	private void generate() {
		// Generate basetypes
		addDocumentType();
		addFolderType();
		addRelationshipType();
		addPolicyType();
		addItemType();
		addSecondayType();

		// Generate subtypes
		addSubTypes();

		// Generate property definition cores
		buildPropertyDefinitionCores();
	}

	// /////////////////////////////////////////////////
	// Refresh global variables from DB
	// /////////////////////////////////////////////////
	@Override
	public void refreshTypes() {
		types.clear();
		basetypes.clear();
		subTypeProperties.clear();

		generate();
	}

	// /////////////////////////////////////////////////
	// BaseType Generating Methods
	// /////////////////////////////////////////////////
	private void addDocumentType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_DESCRIPTION);
		String _creatable = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_CREATABLE);
		boolean creatable = Boolean.valueOf(_creatable);
		String _fileable = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_FILEABLE);
		boolean fileable = Boolean.valueOf(_fileable);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _controllablePolicy = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTROLLABLE_POLICY);
		boolean controllablePolicy = Boolean.valueOf(_controllablePolicy);
		String _controllableAcl = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTROLLABLE_ACL);
		boolean controllableAcl = Boolean.valueOf(_controllableAcl);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);
		String _versionable = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_VERSIONABLE);
		boolean versionable = Boolean.valueOf(_versionable);
		String _contentStreamAllowed = propertyManager
				.readValue(PropertyKey.BASETYPE_DOCUMENT_CONTENT_STREAM_ALLOWED);
		ContentStreamAllowed contentStreamAllowed = ContentStreamAllowed
				.fromValue(_contentStreamAllowed);

		// Set attributes
		DocumentTypeDefinitionImpl documentType = new DocumentTypeDefinitionImpl();
		documentType.setId(DOCUMENT_TYPE_ID);
		documentType.setLocalName(localName);
		documentType.setLocalNamespace(NAMESPACE);
		documentType.setQueryName(DOCUMENT_TYPE_ID);
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

		addBasePropertyDefinitions(documentType);
		addDocumentPropertyDefinitions(documentType);

		addTypeInternal(types, documentType);
		addTypeInternal(basetypes, documentType);
	}

	private void addFolderType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_DESCRIPTION);
		String _creatable = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_CREATABLE);
		boolean creatable = Boolean.valueOf(_creatable);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _controllablePolicy = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_CONTROLLABLE_POLICY);
		boolean controllablePolicy = Boolean.valueOf(_controllablePolicy);
		String _controllableAcl = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_CONTROLLABLE_ACL);
		boolean controllableAcl = Boolean.valueOf(_controllableAcl);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_FOLDER_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);

		// Set attributes
		FolderTypeDefinitionImpl folderType = new FolderTypeDefinitionImpl();
		folderType.setId(FOLDER_TYPE_ID);
		folderType.setLocalName(localName);
		folderType.setLocalNamespace(NAMESPACE);
		folderType.setQueryName(FOLDER_TYPE_ID);
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

		addBasePropertyDefinitions(folderType);
		addFolderPropertyDefinitions(folderType);

		addTypeInternal(types, folderType);
		addTypeInternal(basetypes, folderType);
	}

	private void addRelationshipType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_DESCRIPTION);
		String _creatable = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_CREATABLE);
		boolean creatable = Boolean.valueOf(_creatable);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _controllablePolicy = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_CONTROLLABLE_POLICY);
		boolean controllablePolicy = Boolean.valueOf(_controllablePolicy);
		String _controllableAcl = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_CONTROLLABLE_ACL);
		boolean controllableAcl = Boolean.valueOf(_controllableAcl);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_RELATIONSHIP_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);
		List<String> allowedSourceTypes = propertyManager
				.readValues(PropertyKey.BASETYPE_RELATIONSHIP_ALLOWED_SOURCE_TYPES);
		List<String> allowedTargetTypes = propertyManager
				.readValues(PropertyKey.BASETYPE_RELATIONSHIP_ALLOWED_TARGET_TYPES);

		// Set attributes
		RelationshipTypeDefinitionImpl relationshipType = new RelationshipTypeDefinitionImpl();
		relationshipType.setId(RELATIONSHIP_TYPE_ID);
		relationshipType.setLocalName(localName);
		relationshipType.setLocalNamespace(NAMESPACE);
		relationshipType.setQueryName(RELATIONSHIP_TYPE_ID);
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

		addBasePropertyDefinitions(relationshipType);
		addRelationshipPropertyDefinitions(relationshipType);

		addTypeInternal(types, relationshipType);
		addTypeInternal(basetypes, relationshipType);
	}

	private void addPolicyType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_DESCRIPTION);
		String _creatable = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_CREATABLE);
		boolean creatable = Boolean.valueOf(_creatable);
		String _fileable = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_FILEABLE);
		boolean fileable = Boolean.valueOf(_fileable);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _controllablePolicy = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_CONTROLLABLE_POLICY);
		boolean controllablePolicy = Boolean.valueOf(_controllablePolicy);
		String _controllableAcl = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_CONTROLLABLE_ACL);
		boolean controllableAcl = Boolean.valueOf(_controllableAcl);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_POLICY_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);

		// Set attributes
		PolicyTypeDefinitionImpl policyType = new PolicyTypeDefinitionImpl();
		policyType.setId(POLICY_TYPE_ID);
		policyType.setLocalName(localName);
		policyType.setLocalNamespace(NAMESPACE);
		policyType.setQueryName(POLICY_TYPE_ID);
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

		addBasePropertyDefinitions(policyType);
		addPolicyPropertyDefinitions(policyType);

		addTypeInternal(types, policyType);
		addTypeInternal(basetypes, policyType);
	}

	private void addItemType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_DESCRIPTION);
		String _creatable = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_CREATABLE);
		boolean creatable = Boolean.valueOf(_creatable);
		String _fileable = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_FILEABLE);
		boolean fileable = Boolean.valueOf(_fileable);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _controllablePolicy = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_CONTROLLABLE_POLICY);
		boolean controllablePolicy = Boolean.valueOf(_controllablePolicy);
		String _controllableAcl = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_CONTROLLABLE_ACL);
		boolean controllableAcl = Boolean.valueOf(_controllableAcl);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_ITEM_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);

		// Set attributes
		ItemTypeDefinitionImpl itemType = new ItemTypeDefinitionImpl();
		itemType.setId(ITEM_TYPE_ID);
		itemType.setLocalName(localName);
		itemType.setLocalNamespace(NAMESPACE);
		itemType.setQueryName(ITEM_TYPE_ID);
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

		addBasePropertyDefinitions(itemType);

		addTypeInternal(types, itemType);
		addTypeInternal(basetypes, itemType);
	}

	private void addSecondayType() {
		// Read parameters
		String localName = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_LOCAL_NAME);
		String displayName = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_DISPLAY_NAME);
		String description = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_DESCRIPTION);
		String _queryable = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_QUERYABLE);
		boolean queryable = Boolean.valueOf(_queryable);
		String _includedInSupertypeQuery = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_INCLUDED_IN_SUPER_TYPE_QUERY);
		boolean includedInSupertypeQuery = TRUE
				.equals(_includedInSupertypeQuery);
		String _fulltextIndexed = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_FULLTEXT_INDEXED);
		boolean fulltextIndexed = Boolean.valueOf(_fulltextIndexed);
		String _typeMutabilityCanCreate = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_CREATE);
		boolean typeMutabilityCanCreate = Boolean
				.valueOf(_typeMutabilityCanCreate);
		String _typeMutabilityCanUpdate = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_UPDATE);
		boolean typeMutabilityCanUpdate = Boolean
				.valueOf(_typeMutabilityCanUpdate);
		String _typeMutabilityCanDelete = propertyManager
				.readValue(PropertyKey.BASETYPE_SECONDARY_TYPE_MUTABILITY_CAN_DELETE);
		boolean typeMutabilityCanDelete = Boolean
				.valueOf(_typeMutabilityCanDelete);

		// Set attributes
		SecondaryTypeDefinitionImpl secondaryType = new SecondaryTypeDefinitionImpl();
		secondaryType.setId(SECONDARY_TYPE_ID);
		secondaryType.setLocalName(localName);
		secondaryType.setLocalNamespace(NAMESPACE);
		secondaryType.setQueryName(SECONDARY_TYPE_ID);
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

		addTypeInternal(types, secondaryType);
		addTypeInternal(basetypes, secondaryType);
	}

	private void addBasePropertyDefinitions(AbstractTypeDefinition type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.NAME,
				PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READWRITE, REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.DESCRIPTION, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READWRITE, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.OBJECT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.BASE_TYPE_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.OBJECT_TYPE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.ONCREATE, REQUIRED, QUERYABLE,
				ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READWRITE, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.CREATED_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CREATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFIED_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFICATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHANGE_TOKEN, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));
	}

	private void addFolderPropertyDefinitions(FolderTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PARENT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PATH,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!REQUIRED, !QUERYABLE, !ORDERABLE, null));

		List<String> defaults = new ArrayList<String>();
		defaults.add(BaseTypeId.CMIS_FOLDER.value());
		defaults.add(BaseTypeId.CMIS_DOCUMENT.value());
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, defaults));
	}

	private void addDocumentPropertyDefinitions(DocumentTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_IMMUTABLE, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, Arrays.asList(false)));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_PRIVATE_WORKING_COPY, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_LABEL, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
				PropertyType.BOOLEAN, Cardinality.SINGLE,
				Updatability.READONLY, !REQUIRED, QUERYABLE, ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHECKIN_COMMENT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_LENGTH, PropertyType.INTEGER,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_MIME_TYPE, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_FILE_NAME, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));
	}

	private void addRelationshipPropertyDefinitions(
			RelationshipTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.SOURCE_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READWRITE,
				REQUIRED, !QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.TARGET_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READWRITE,
				REQUIRED, !QUERYABLE, !ORDERABLE, null));
	}

	private void addPolicyPropertyDefinitions(PolicyTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.POLICY_TEXT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !REQUIRED,
				!QUERYABLE, !ORDERABLE, null));
	}

	private PropertyDefinition<?> createDefaultPropDef(String id,
			PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean required, boolean queryable,
			boolean orderable, List<?> defaultValue) {
		PropertyDefinition<?> result = null;

		// Default values
		String localName = id;
		String localNameSpace = NAMESPACE;
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

	// /////////////////////////////////////////////////
	// Subtype
	// /////////////////////////////////////////////////
	private List<NemakiTypeDefinition> getNemakiTypeDefinitions() {
		return typeService.getTypeDefinitions();
	}

	private void addSubTypes() {

		List<NemakiTypeDefinition> subtypes = getNemakiTypeDefinitions();
		List<NemakiTypeDefinition> firstGeneration = new ArrayList<NemakiTypeDefinition>();
		for (NemakiTypeDefinition subtype : subtypes) {
			if (subtype.getBaseId().value().equals(subtype.getParentId())) {
				firstGeneration.add(subtype);
			}
		}

		for (NemakiTypeDefinition type : firstGeneration) {
			addSubTypesInternal(subtypes, type);
		}
		return;
	}

	private void addSubTypesInternal(List<NemakiTypeDefinition> subtypes,
			NemakiTypeDefinition type) {
		TypeDefinitionContainerImpl container = new TypeDefinitionContainerImpl();
		container.setTypeDefinition(buildTypeDefinitionFromDB(type));
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
				addSubTypesInternal(subtypes, child);
			}
		}

		TypeDefinitionContainer parentContainer = types.get(type.getParentId());
		parentContainer.getChildren().add(container);
		return;
	}

	@Override
	public AbstractTypeDefinition buildTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		switch (nemakiType.getBaseId()) {
		case CMIS_DOCUMENT:
			return buildDocumentTypeDefinitionFromDB(nemakiType);
		case CMIS_FOLDER:
			return buildFolderTypeDefinitionFromDB(nemakiType);
		case CMIS_RELATIONSHIP:
			return buildRelationshipTypeDefinitionFromDB(nemakiType);
		case CMIS_POLICY:
			return buildPolicyTypeDefinitionFromDB(nemakiType);
		case CMIS_ITEM:
			return buildItemTypeDefinitionFromDB(nemakiType);
		case CMIS_SECONDARY:
			return buildSecondaryTypeDefinitionFromDB(nemakiType);
		default:
			break;
		}

		return null;
	}

	private void buildTypeDefinitionBaseFromDB(AbstractTypeDefinition type,
			AbstractTypeDefinition parentType, NemakiTypeDefinition nemakiType) {
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
						.getPropertyDefinitionDetail(propertyId);
				NemakiPropertyDefinitionCore core = typeService
						.getPropertyDefinitionCore(detail.getCoreNodeId());

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
			NemakiTypeDefinition nemakiType) {
		DocumentTypeDefinitionImpl type = new DocumentTypeDefinitionImpl();
		DocumentTypeDefinitionImpl parentType = (DocumentTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

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
			NemakiTypeDefinition nemakiType) {
		FolderTypeDefinitionImpl type = new FolderTypeDefinitionImpl();
		FolderTypeDefinitionImpl parentType = (FolderTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private RelationshipTypeDefinitionImpl buildRelationshipTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		RelationshipTypeDefinitionImpl type = new RelationshipTypeDefinitionImpl();
		RelationshipTypeDefinitionImpl parentType = (RelationshipTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		// Set specific attributes
		type.setAllowedSourceTypes(nemakiType.getAllowedSourceTypes());
		type.setAllowedTargetTypes(nemakiType.getAllowedTargetTypes());

		return type;
	}

	private PolicyTypeDefinitionImpl buildPolicyTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		PolicyTypeDefinitionImpl type = new PolicyTypeDefinitionImpl();
		PolicyTypeDefinitionImpl parentType = (PolicyTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private ItemTypeDefinitionImpl buildItemTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		ItemTypeDefinitionImpl type = new ItemTypeDefinitionImpl();
		ItemTypeDefinitionImpl parentType = (ItemTypeDefinitionImpl) types.get(
				nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private SecondaryTypeDefinitionImpl buildSecondaryTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
		SecondaryTypeDefinitionImpl parentType = (SecondaryTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private void buildPropertyDefinitionCores() {
		// CMIS default property cores
		Map<String, PropertyDefinition<?>> d = types.get(DOCUMENT_TYPE_ID)
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> f = types.get(FOLDER_TYPE_ID)
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> r = types.get(RELATIONSHIP_TYPE_ID)
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> p = types.get(POLICY_TYPE_ID)
				.getTypeDefinition().getPropertyDefinitions();
		Map<String, PropertyDefinition<?>> i = types.get(ITEM_TYPE_ID)
				.getTypeDefinition().getPropertyDefinitions();

		copyToPropertyDefinitionCore(d);
		copyToPropertyDefinitionCore(f);
		copyToPropertyDefinitionCore(r);
		copyToPropertyDefinitionCore(p);
		copyToPropertyDefinitionCore(i);

		// Subtype property cores(consequently includes secondary property
		// cores)
		List<NemakiPropertyDefinitionCore> subTypeCores = typeService
				.getPropertyDefinitionCores();
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
	public TypeDefinitionContainer getTypeById(String typeId) {
		return types.get(typeId);
	}

	@Override
	public TypeDefinition getTypeByQueryName(String typeQueryName) {
		for (Entry<String, TypeDefinitionContainer> entry : types.entrySet()) {
			if (entry.getValue().getTypeDefinition().getQueryName()
					.equals(typeQueryName))
				return entry.getValue().getTypeDefinition();
		}
		return null;
	}

	@Override
	public Collection<TypeDefinitionContainer> getTypeDefinitionList() {
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
	public List<TypeDefinitionContainer> getRootTypes() {
		List<TypeDefinitionContainer> rootTypes = new ArrayList<TypeDefinitionContainer>();
		for (String key : basetypes.keySet()) {
			rootTypes.add(basetypes.get(key));
		}
		return rootTypes;
	}

	@Override
	public String getPropertyIdForQueryName(TypeDefinition typeDefinition,
			String propQueryName) {
		// TODO Auto-generated method stub
		PropertyDefinition<?> def = getPropertyDefinitionForQueryName(
				typeDefinition, propQueryName);
		if (def == null) {
			return null;
		} else {
			return def.getQueryName();
		}
	}

	public PropertyDefinition<?> getPropertyDefinitionForQueryName(
			TypeDefinition typeDefinition, String propQueryName) {
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
		return propertyDefinitionCoresForQueryName.get(queryName);
	}

	@Override
	public TypeDefinition getTypeDefinition(String typeId) {
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			return null;
		}

		return tc.getTypeDefinition();
	}

	@Override
	public List<PropertyDefinition<?>> getSpecificPropertyDefinitions(
			String typeId) {
		return subTypeProperties.get(typeId);
	}

	/**
	 * CMIS getTypesChildren. If parent type id is not specified, return only
	 * base types.
	 */
	@Override
	public TypeDefinitionList getTypesChildren(CallContext context,
			String typeId, boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount) {
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
					System.out.print(e);
				}

			}
		}

		return result;
	}

	/**
	 * CMIS getTypesDescendants.
	 */
	@Override
	public List<TypeDefinitionContainer> getTypesDescendants(String typeId,
			BigInteger depth, Boolean includePropertyDefinitions) {
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
			flattenTypeDefinitionContainer(types.get(FOLDER_TYPE_ID), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(DOCUMENT_TYPE_ID), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(RELATIONSHIP_TYPE_ID),
					result, d, ipd);
			flattenTypeDefinitionContainer(types.get(POLICY_TYPE_ID), result,
					d, ipd);
			flattenTypeDefinitionContainer(types.get(ITEM_TYPE_ID), result, d,
					ipd);
			flattenTypeDefinitionContainer(types.get(SECONDARY_TYPE_ID),
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
	 * 
	 * @param content
	 * @return
	 */
	@Override
	public TypeDefinition getTypeDefinition(Content content) {
		String typeId = (content.getObjectType() == null) ? content.getType()
				: content.getObjectType();
		return getTypeDefinition(typeId);
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
	public Object getSingleDefaultValue(String propertyId, String typeId) {
		TypeDefinition tdf = getTypeDefinition(typeId);
		PropertyDefinition<?> pdf = tdf.getPropertyDefinitions()
				.get(propertyId);
		return pdf.getDefaultValue().get(0);
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setPropertyManager(NemakiPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
