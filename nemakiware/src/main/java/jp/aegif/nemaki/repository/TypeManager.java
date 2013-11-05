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
package jp.aegif.nemaki.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.service.dao.ContentDaoService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.ItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.SecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.WSConverter;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.CollectionUtils;

import com.sun.xml.ws.org.objectweb.asm.Type;

/**
 * Type Manager class, defines document/folder/relationship/policy
 */
public class TypeManager implements
		org.apache.chemistry.opencmis.server.support.TypeManager {

	private static final Log log = LogFactory.getLog(TypeManager.class);
	
	/**
	 * Spring bean
	 */
	private ContentDaoService contentDaoService;
	
	/**
	 * Constant
	 */
	public final static String DOCUMENT_TYPE_ID = BaseTypeId.CMIS_DOCUMENT.value();
	public final static String FOLDER_TYPE_ID = BaseTypeId.CMIS_FOLDER.value();
	public final static String RELATIONSHIP_TYPE_ID = BaseTypeId.CMIS_RELATIONSHIP.value();
	public final static String POLICY_TYPE_ID = BaseTypeId.CMIS_POLICY.value();
	public final static String ITEM_TYPE_ID = BaseTypeId.CMIS_ITEM.value();
	public final static String SECONDARY_TYPE_ID = BaseTypeId.CMIS_SECONDARY.value();
	private static final String NAMESPACE = "http://www.aegif.jp/Nemaki";
	private final static boolean REQUIRED = true;
	private final static boolean QUERYABLE = true;
	private final static boolean ORDERABLE = true;

	/**
	 * Global variables containing type information 
	 */
	//Map of all types
	private Map<String, TypeDefinitionContainer> types;
	
	//Map of all base types
	private Map<String, TypeDefinitionContainer> basetypes;
	
	//Map of subtype-specific property
	private Map<String, List<PropertyDefinition<?>>> subTypeProperties;
	
	
	// /////////////////////////////////////////////////
	// Constructor
	// /////////////////////////////////////////////////
	public TypeManager(ContentDaoService contentDaoService) {
		setContentDaoService(contentDaoService);
		
		types = new HashMap<String, TypeDefinitionContainer>();
		basetypes = new HashMap<String, TypeDefinitionContainer>();
		subTypeProperties = new HashMap<String, List<PropertyDefinition<?>>>();
		
		// Generate basetypes
		addDocumentType();
		addFolderType();
		addRelationshipType();
		addPolicyType();
		addItemType();
		addSecondayType();

		// Generate subtypes
		addSubTypes();
	}

	// /////////////////////////////////////////////////
	// BaseType Generating Methods
	// /////////////////////////////////////////////////
	private void addDocumentType() {
		DocumentTypeDefinitionImpl documentType = new DocumentTypeDefinitionImpl();
		documentType.setId(DOCUMENT_TYPE_ID);
		documentType.setLocalName("document");
		documentType.setLocalNamespace(NAMESPACE);
		documentType.setQueryName(DOCUMENT_TYPE_ID);
		documentType.setDisplayName("document");
		documentType.setDescription("Document");
		documentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
		documentType.setIsCreatable(true);
		documentType.setIsFileable(true);
		documentType.setIsQueryable(true);
		documentType.setIsControllablePolicy(false);
		documentType.setIsControllableAcl(true);
		documentType.setIsIncludedInSupertypeQuery(true);
		documentType.setIsFulltextIndexed(true);
		documentType.setIsVersionable(true);
		documentType.setContentStreamAllowed(ContentStreamAllowed.REQUIRED);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(true);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		documentType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(documentType);
		addDocumentPropertyDefinitions(documentType);

		addTypeInternal(types, documentType);
		addTypeInternal(basetypes, documentType);
	}

	private void addFolderType() {
		FolderTypeDefinitionImpl folderType = new FolderTypeDefinitionImpl();
		folderType.setId(FOLDER_TYPE_ID);
		folderType.setLocalName("folder");
		folderType.setLocalNamespace(NAMESPACE);
		folderType.setQueryName(FOLDER_TYPE_ID);
		folderType.setDisplayName("folder");
		folderType.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
		folderType.setDescription("Folder");
		folderType.setIsCreatable(true);
		folderType.setIsFileable(true);
		folderType.setIsQueryable(true);
		folderType.setIsControllablePolicy(false);
		folderType.setIsControllableAcl(true);
		folderType.setIsFulltextIndexed(false);
		folderType.setIsIncludedInSupertypeQuery(true);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(true);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		folderType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(folderType);
		addFolderPropertyDefinitions(folderType);

		addTypeInternal(types, folderType);
		addTypeInternal(basetypes, folderType);
	}

	private void addRelationshipType() {
		RelationshipTypeDefinitionImpl relationshipType = new RelationshipTypeDefinitionImpl();
		relationshipType.setId(RELATIONSHIP_TYPE_ID);
		relationshipType.setLocalName("relationship");
		relationshipType.setLocalNamespace(NAMESPACE);
		relationshipType.setQueryName(RELATIONSHIP_TYPE_ID);
		relationshipType.setDisplayName("relationship");
		relationshipType.setBaseTypeId(BaseTypeId.CMIS_RELATIONSHIP);
		relationshipType.setDescription("Relationship");
		relationshipType.setIsCreatable(false);
		relationshipType.setIsFileable(false);
		relationshipType.setIsQueryable(false);
		relationshipType.setIsControllablePolicy(false);
		relationshipType.setIsControllableAcl(false);
		relationshipType.setIsIncludedInSupertypeQuery(true);
		relationshipType.setIsFulltextIndexed(false);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(false);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		relationshipType.setTypeMutability(typeMutability);

		List<String> allowedTypes = new ArrayList<String>();
		allowedTypes.add(DOCUMENT_TYPE_ID);
		allowedTypes.add(FOLDER_TYPE_ID);
		relationshipType.setAllowedSourceTypes(allowedTypes);
		relationshipType.setAllowedTargetTypes(allowedTypes);

		addBasePropertyDefinitions(relationshipType);
		addRelationshipPropertyDefinitions(relationshipType);

		addTypeInternal(types, relationshipType);
		addTypeInternal(basetypes, relationshipType);
	}

	private void addPolicyType() {
		PolicyTypeDefinitionImpl policyType = new PolicyTypeDefinitionImpl();
		policyType.setId(POLICY_TYPE_ID);
		policyType.setLocalName("policy");
		policyType.setLocalNamespace(NAMESPACE);
		policyType.setQueryName(POLICY_TYPE_ID);
		policyType.setDisplayName("policy");
		policyType.setBaseTypeId(BaseTypeId.CMIS_POLICY);
		policyType.setDescription("Policy");
		policyType.setIsCreatable(false);
		policyType.setIsFileable(false);
		policyType.setIsQueryable(false);
		policyType.setIsControllablePolicy(false);
		policyType.setIsControllableAcl(false);
		policyType.setIsIncludedInSupertypeQuery(true);
		policyType.setIsFulltextIndexed(false);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(false);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		policyType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(policyType);
		addPolicyPropertyDefinitions(policyType);

		addTypeInternal(types, policyType);
		addTypeInternal(basetypes, policyType);
	}

	private void addItemType() {
		ItemTypeDefinitionImpl itemType = new ItemTypeDefinitionImpl();
		itemType.setId(ITEM_TYPE_ID);
		itemType.setLocalName("item");
		itemType.setLocalNamespace(NAMESPACE);
		itemType.setQueryName(ITEM_TYPE_ID);
		itemType.setDisplayName("item");
		itemType.setBaseTypeId(BaseTypeId.CMIS_ITEM);
		itemType.setDescription("Item");
		itemType.setIsCreatable(false);
		itemType.setIsFileable(false);
		itemType.setIsQueryable(false);
		itemType.setIsControllablePolicy(false);
		itemType.setIsControllableAcl(false);
		itemType.setIsIncludedInSupertypeQuery(true);
		itemType.setIsFulltextIndexed(false);

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(false);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		itemType.setTypeMutability(typeMutability);

		addBasePropertyDefinitions(itemType);

		addTypeInternal(types, itemType);
		addTypeInternal(basetypes, itemType);
	}

	private void addSecondayType() {
		SecondaryTypeDefinitionImpl secondaryType = new SecondaryTypeDefinitionImpl();
		secondaryType.setId(SECONDARY_TYPE_ID);
		secondaryType.setLocalName("secondary");
		secondaryType.setLocalNamespace(NAMESPACE);
		secondaryType.setQueryName(SECONDARY_TYPE_ID);
		secondaryType.setDisplayName("secondary");
		secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		secondaryType.setDescription("Secondary");
		secondaryType.setIsCreatable(false);
		secondaryType.setIsFileable(false);
		secondaryType.setIsQueryable(true);
		secondaryType.setIsControllablePolicy(false);
		secondaryType.setIsControllableAcl(false);
		secondaryType.setIsIncludedInSupertypeQuery(true);
		secondaryType.setIsFulltextIndexed(true);
		secondaryType
				.setPropertyDefinitions(new HashMap<String, PropertyDefinition<?>>());

		TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
		typeMutability.setCanCreate(true);
		typeMutability.setCanUpdate(false);
		typeMutability.setCanDelete(false);
		secondaryType.setTypeMutability(typeMutability);

		secondaryType.setPropertyDefinitions(new HashMap<String, PropertyDefinition<?>>());
		
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
				!QUERYABLE, !ORDERABLE, null));

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
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				REQUIRED, !QUERYABLE, !ORDERABLE, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.TARGET_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
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

		result = createPropDef(id, localName, localNameSpace,
				queryName, displayName, description, datatype, cardinality,
				updatability, required, queryable, inherited, openChoice,
				orderable, defaultValue);

		return result;
	}

	// /////////////////////////////////////////////////
	// SubType Generating Methods
	// /////////////////////////////////////////////////
	private List<NemakiTypeDefinition> getNemakiTypeDefinitions() {
		return contentDaoService.getTypeDefinitions();
	}

	/**
	 * Build Subtypes
	 */
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
		
		if(types.get(type.getTypeId()) == null){
			types.put(type.getTypeId(), container);
		}else{
			//TODO logging: can't overwrite the type
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

	private TypeDefinition buildTypeDefinitionFromDB(
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
		TypeDefinition copied = copyTypeDefinition(parentType);
		Map<String,PropertyDefinition<?>> parentProperties = copied.getPropertyDefinitions();
		if(CollectionUtils.isEmpty(parentProperties)){
			parentProperties = new HashMap<String,PropertyDefinition<?>>();
		}
		for(String key : parentProperties.keySet()){
			PropertyDefinition<?> parentProperty = parentProperties.get(key);
			setInheritedToTrue((AbstractPropertyDefinition<?>)parentProperty);
		}
		type.setPropertyDefinitions(parentProperties);
		
		// Add specific properties
		Map<String, PropertyDefinition<?>> properties = type
				.getPropertyDefinitions();
		List<PropertyDefinition<?>> specificProperties = new ArrayList<PropertyDefinition<?>>();
		for (String propertyId : nemakiType.getProperties()) {
			NemakiPropertyDefinition p = contentDaoService
					.getPropertyDefinition(propertyId);
			PropertyDefinition<?> property = createPropDef(
					p.getPropertyId(), p.getLocalName(), p.getLocalNameSpace(),
					p.getQueryName(), p.getDisplayName(), p.getDescription(),
					p.getPropertyType(), p.getCardinality(),
					p.getUpdatability(), p.isRequired(), p.isQueryable(),
					false, p.isOpenChoice(), p.isOrderable(),
					p.getDefaultValue());
			properties.put(p.getPropertyId(), property);
			
			//for subTypeProperties
			specificProperties.add(property);
		}
		
		//for subTypeProperties
		if(subTypeProperties.containsKey(type.getParentTypeId())){
			List<PropertyDefinition<?>> parentSpecificProperties = subTypeProperties.get(type.getParentTypeId());
			subTypeProperties.put(type.getId(), specificProperties);
			specificProperties.addAll(parentSpecificProperties);
			subTypeProperties.put(type.getId(), specificProperties);
		}else{
			subTypeProperties.put(type.getId(), specificProperties);
		}
	}

	private AbstractPropertyDefinition<?> setInheritedToTrue(AbstractPropertyDefinition<?> property){
		property.setIsInherited(true);
		return property;
	}
	
	private DocumentTypeDefinition buildDocumentTypeDefinitionFromDB(
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

	private FolderTypeDefinition buildFolderTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		FolderTypeDefinitionImpl type = new FolderTypeDefinitionImpl();
		FolderTypeDefinitionImpl parentType = (FolderTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private RelationshipTypeDefinition buildRelationshipTypeDefinitionFromDB(
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

	private PolicyTypeDefinition buildPolicyTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		PolicyTypeDefinitionImpl type = new PolicyTypeDefinitionImpl();
		PolicyTypeDefinitionImpl parentType = (PolicyTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private ItemTypeDefinition buildItemTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		ItemTypeDefinitionImpl type = new ItemTypeDefinitionImpl();
		ItemTypeDefinitionImpl parentType = (ItemTypeDefinitionImpl) types.get(
				nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
	}

	private SecondaryTypeDefinition buildSecondaryTypeDefinitionFromDB(
			NemakiTypeDefinition nemakiType) {
		SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
		SecondaryTypeDefinitionImpl parentType = (SecondaryTypeDefinitionImpl) types
				.get(nemakiType.getParentId()).getTypeDefinition();

		// Set base attributes, and properties(with specific properties
		// included)
		buildTypeDefinitionBaseFromDB(type, parentType, nemakiType);

		return type;
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
		for(String key : basetypes.keySet()){
			rootTypes.add(basetypes.get(key));
		}
		return rootTypes;
	}

	@Override
	public String getPropertyIdForQueryName(TypeDefinition typeDefinition,
			String propQueryName) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * For Nemaki use
	 */
	public TypeDefinition getTypeDefinition(String typeId) {
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			return null;
		}

		return tc.getTypeDefinition();
	}

	public List<PropertyDefinition<?>>getSpecificPropertyDefinitions(String typeId){
		return subTypeProperties.get(typeId);
	}
	
	/**
	 * CMIS getTypesChildren. If parent type id is not specified, return only
	 * base types.
	 */
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
				result.getList().add(copyTypeDefinition(type.getTypeDefinition()));
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

				result.getList().add(child.getTypeDefinition());

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
						//TODO clear() destroys PropertyDefinitions of "types"
						//type.getPropertyDefinitions().clear();
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
	public List<TypeDefinitionContainer> getTypesDescendants(String typeId,
			BigInteger depth, Boolean includePropertyDefinitions) {
		List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

		// check depth
		int d = (depth == null ? -1 : depth.intValue());
		if (d == 0) {
			throw new CmisInvalidArgumentException("Depth must not be 0!");
		}else if(d < -1){
			throw new CmisInvalidArgumentException("Depth must be positive(except for -1, that means infinity!");
		}

		// set property definition flag to default value if not set
		boolean ipd = (includePropertyDefinitions == null ? false
				: includePropertyDefinitions.booleanValue());

		if (typeId == null) {
			flattenTypeDefinitionContainer(types.get(FOLDER_TYPE_ID), result, d, ipd);
			flattenTypeDefinitionContainer(types.get(DOCUMENT_TYPE_ID), result, d, ipd);
			flattenTypeDefinitionContainer(types.get(RELATIONSHIP_TYPE_ID), result, d, ipd);
			flattenTypeDefinitionContainer(types.get(POLICY_TYPE_ID), result, d, ipd);
			flattenTypeDefinitionContainer(types.get(ITEM_TYPE_ID), result, d, ipd);
			flattenTypeDefinitionContainer(types.get(SECONDARY_TYPE_ID), result, d, ipd);
		} else {
			TypeDefinitionContainer tdc = types.get(typeId);
			flattenTypeDefinitionContainer(tdc, result, d, ipd);
		}

		return result;
	}
	
	/**
	 * TODO includePropertyDefinitions flag doesn't work
	 * TODO and type.getPropertyDefinitions().clear() destroys PropertyDefinitions of "types" 
	 * @param tdc
	 * @param result
	 * @param includePropertyDefinitions
	 */
	private void flattenTypeDefinitionContainer(TypeDefinitionContainer tdc, List<TypeDefinitionContainer> result, int depth, boolean includePropertyDefinitions){
		if(depth == 0) return;
		
		result.add(tdc);
		List<TypeDefinitionContainer> children = tdc.getChildren();
		if(!CollectionUtils.isEmpty(children)){
			for(TypeDefinitionContainer child : children){
				flattenTypeDefinitionContainer(child, result, depth -1, includePropertyDefinitions);
			}
		}
	}

	/**
	 * Gathers the type descendants tree.
	 */
	private TypeDefinitionContainer getTypesDescendantsInternal(int depth,
			TypeDefinitionContainer tc, boolean includePropertyDefinitions) {
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl();

		TypeDefinition type = copyTypeDefinition(tc.getTypeDefinition());
		if (!includePropertyDefinitions) {
			//TODO clear() destroys PropertyDefinitions of "types"
			//type.getPropertyDefinitions().clear();
		}

		result.setTypeDefinition(type);

		if (depth != 0) {
			if (tc.getChildren() != null) {
				result.setChildren(new ArrayList<TypeDefinitionContainer>());
				for (TypeDefinitionContainer tdc : tc.getChildren()) {
					result.getChildren().add(
							getTypesDescendantsInternal(depth < 0 ? -1 : depth - 1,
									tdc, includePropertyDefinitions));
				}
			}
		}

		return result;
	}
	
	public TypeDefinition getTypeDefinition(Content content) {
		String typeId = (content.getObjectType() == null) ? content.getType()
				: content.getObjectType();
		return getTypeDefinition(typeId);
	}
	
	
	// //////////////////////////////////////////////////////////////////////////////
	// Utility
	// //////////////////////////////////////////////////////////////////////////////
	private TypeDefinition copyTypeDefinition(TypeDefinition type) {
		return WSConverter.convert(WSConverter.convert(type));
	}

	public static void addTypeInternal(
			Map<String, TypeDefinitionContainer> types,
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

	private static boolean isDuplicateChild(TypeDefinitionContainer parent,
			TypeDefinition type) {
		for (TypeDefinitionContainer child : parent.getChildren()) {
			if (child.getTypeDefinition().getId().equals(type.getId())) {
				return true;
			}
		}
		return false;
	}
	
	public static PropertyDefinition<?> createPropDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean required, boolean queryable, boolean inherited,
			boolean openChoice, boolean orderable, List<?> defaultValue) {
		PropertyDefinition<?> result = null;
		switch (datatype) {
		case BOOLEAN:
			result = createPropBooleanTimeDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(String.class, defaultValue));
			break;
		case DATETIME:

			result = createPropDateTimeDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(GregorianCalendar.class, defaultValue),
					DateTimeResolution.TIME);
			break;
		case DECIMAL:
			result = createPropDecimalDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(BigDecimal.class, defaultValue),
					DecimalPrecision.BITS64, null, null);
			break;
		case HTML:
			result = createPropHtmlDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(String.class, defaultValue));
			break;
		case ID:
			result = createPropIdDef(id, localName, localNameSpace, queryName,
					displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(String.class, defaultValue));
			break;
		case INTEGER:
			result = createPropIntegerDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(BigInteger.class, defaultValue), null, null);
			break;
		case STRING:
			result = createPropStringDef(id, localName, localNameSpace,
					queryName, displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(String.class, defaultValue), null);
			break;
		case URI:
			result = createPropUriDef(id, localName, localNameSpace, queryName,
					displayName, description, datatype, cardinality,
					updatability, inherited, required, queryable, orderable,
					null, openChoice,
					convertListType(String.class, defaultValue));
			break;
		default:
			throw new RuntimeException("Unknown datatype! Spec change?");
		}
		return result;
	}

	private static <T> List<T> convertListType(final Class<T> clazz,
			List<?> list) {
		if (CollectionUtils.isEmpty(list))
			return null;
		List<T> result = new ArrayList<T>();
		for (Object o : list) {
			result.add(clazz.cast(o));
		}
		return result;
	}

	private static PropertyDefinition<?> createPropBooleanTimeDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<String>> choiceList,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyStringDefinitionImpl result = new PropertyStringDefinitionImpl();
		result = (PropertyStringDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);
		return result;
	}

	private static PropertyDefinition<?> createPropDateTimeDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<GregorianCalendar>> choiceList,
			boolean openChoice, List<GregorianCalendar> defaultValue,
			DateTimeResolution resolution) {
		// Set base attributes
		PropertyDateTimeDefinitionImpl result = new PropertyDateTimeDefinitionImpl();
		result = (PropertyDateTimeDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);
		// Set DateTime-specific attributes
		result.setDateTimeResolution(resolution);

		return result;
	}

	private static PropertyDefinition<?> createPropDecimalDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<BigDecimal>> choiceList,
			boolean openChoice, List<BigDecimal> defaultValue,
			DecimalPrecision precision, BigDecimal minValue, BigDecimal maxValue) {
		// Set base attributes
		PropertyDecimalDefinitionImpl result = new PropertyDecimalDefinitionImpl();
		result = (PropertyDecimalDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);
		// Set Decimal-specific attributes
		result.setMinValue(minValue);
		result.setMaxValue(maxValue);

		return result;
	}

	private static PropertyDefinition<?> createPropHtmlDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<String>> choiceList,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyHtmlDefinitionImpl result = new PropertyHtmlDefinitionImpl();
		result = (PropertyHtmlDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);

		return result;
	}

	private static PropertyDefinition<?> createPropIdDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<String>> choiceList,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyIdDefinitionImpl result = new PropertyIdDefinitionImpl();
		result = (PropertyIdDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);

		return result;
	}

	private static PropertyDefinition<?> createPropIntegerDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<BigInteger>> choiceList,
			boolean openChoice, List<BigInteger> defaultValue,
			BigInteger minValue, BigInteger maxValue) {
		// Set base attributes
		PropertyIntegerDefinitionImpl result = new PropertyIntegerDefinitionImpl();
		result = (PropertyIntegerDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);
		// Set Integer-specific attributes
		result.setMinValue(minValue);
		result.setMaxValue(maxValue);

		return result;
	}

	private static PropertyDefinition<?> createPropStringDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<String>> choiceList,
			boolean openChoice, List<String> defaultValue, BigInteger maxLength) {
		// Set base attributes
		PropertyStringDefinitionImpl result = new PropertyStringDefinitionImpl();
		result = (PropertyStringDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);
		// Set String-specific attributes
		if (maxLength != null)
			result.setMaxLength(maxLength);
		return result;
	}

	private static PropertyDefinition<?> createPropUriDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<Choice<String>> choiceList,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyUriDefinitionImpl result = new PropertyUriDefinitionImpl();
		result = (PropertyUriDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(choiceList);
		result.setDefaultValue(defaultValue);

		return result;
	}

	private static PropertyDefinition<?> createPropBaseDef(
			AbstractPropertyDefinition<?> result, String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable, boolean openChoice) {
		// Set default value if not set(null)
		localName = (localName == null) ? id : localName;
		localNameSpace = (localNameSpace == null)?NAMESPACE:localNameSpace;
		queryName = (queryName == null) ? id : queryName;
		displayName = (displayName == null) ? id : displayName;
		description = (description == null) ? id : description;
		
		// Set base attributes
		result.setId(id);
		result.setLocalName(localName);
		result.setLocalNamespace(localNameSpace);
		result.setQueryName(queryName);
		result.setDisplayName(displayName);
		result.setDescription(description);
		result.setPropertyType(datatype);
		result.setCardinality(cardinality);
		result.setUpdatability(updatability);
		result.setIsInherited(inherited);
		result.setIsRequired(required);
		result.setIsQueryable(queryable);
		result.setIsOrderable(orderable);
		result.setIsOpenChoice(openChoice);
		return result;
	}
	
	
	// /////////////////////////////////////////////////
	// Spring Injection
	// /////////////////////////////////////////////////
	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}
}