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
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.commons.collections.CollectionUtils;

public class FixedTypeManager {

	/**
	 * Pre-defined types.
	 */
	public final static String DOCUMENT_TYPE_ID = "cmis:document";
	public final static String FOLDER_TYPE_ID = "cmis:folder";
	public final static String RELATIONSHIP_TYPE_ID = "cmis:relationship";
	public final static String POLICY_TYPE_ID = "cmis:policy";
	public final static String SECONDARY_TYPE_ID = "cmis:secondary";

	
	private final static boolean required = true;
	private final static boolean queryable = true;
	private final static boolean orderable = true;

	/**
	 * Types namespace.
	 */
	private static final String NAMESPACE = "http://www.aegif.jp/Nemaki";

	/**
	 * Map of all types.
	 */
	private Map<String, TypeDefinitionContainer> types;


	/**
	 * Constructor.
	 */
	public FixedTypeManager() {
		setup();
	}
	
	public Map<String, TypeDefinitionContainer> getTypes() {
		return types;
	}

	/**
	 * Creates the base types.
	 */
	private void setup() {
		types = new HashMap<String, TypeDefinitionContainer>();

		// folder type
		FolderTypeDefinitionImpl folderType = new FolderTypeDefinitionImpl();
		folderType.setId(FOLDER_TYPE_ID);
		folderType.setLocalName("folder");
		folderType.setLocalNamespace(NAMESPACE);
		folderType.setQueryName(FOLDER_TYPE_ID);
		folderType.setDisplayName("Folder");
		folderType.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
		folderType.setDescription("Folder");
		folderType.setIsCreatable(true);
		folderType.setIsFileable(true);
		folderType.setIsQueryable(true);
		folderType.setIsControllablePolicy(false);
		folderType.setIsControllableAcl(true);
		folderType.setIsFulltextIndexed(false);
		folderType.setIsIncludedInSupertypeQuery(true);
		
		addBasePropertyDefinitions(folderType);
		addFolderPropertyDefinitions(folderType);

		addTypeInternal(folderType);

		// document type
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

		addBasePropertyDefinitions(documentType);
		addDocumentPropertyDefinitions(documentType);

		addTypeInternal(documentType);

		// relationship types
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

		List<String> allowedTypes = new ArrayList<String>();
		allowedTypes.add(DOCUMENT_TYPE_ID);
		allowedTypes.add(FOLDER_TYPE_ID);
		relationshipType.setAllowedSourceTypes(allowedTypes);
		relationshipType.setAllowedTargetTypes(allowedTypes);
		
		addBasePropertyDefinitions(relationshipType);
		addRelationshipPropertyDefinitions(relationshipType);
		
		addTypeInternal(relationshipType);

		// policy type
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
		relationshipType.setIsFulltextIndexed(false);

		addBasePropertyDefinitions(policyType);
		addPolicyPropertyDefinitions(policyType);
		
		addTypeInternal(policyType);

		/*
		 * //secondary type(Parent type) SecondaryTypeDefinitionImpl
		 * secondaryType = new SecondaryTypeDefinitionImpl();
		 * secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		 * secondaryType.setIsControllableAcl(false);
		 * secondaryType.setIsControllablePolicy(false);
		 * secondaryType.setIsCreatable(false);
		 * secondaryType.setDescription("Secondary");
		 * secondaryType.setDisplayName("Secondary");
		 * secondaryType.setIsFileable(false);
		 * secondaryType.setIsIncludedInSupertypeQuery(true);
		 * secondaryType.setLocalName("Secondary");
		 * secondaryType.setLocalNamespace(NAMESPACE);
		 * secondaryType.setIsQueryable(false);
		 * secondaryType.setQueryName("cmis:secondary");
		 * secondaryType.setId(SECONDARY_TYPE_ID);
		 * 
		 * addBasePropertyDefinitions(secondaryType);
		 * 
		 * addTypeInternal(secondaryType);
		 */

	}

	private void addBasePropertyDefinitions(AbstractTypeDefinition type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.NAME,
				PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READWRITE, required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.DESCRIPTION,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READWRITE,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.OBJECT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.BASE_TYPE_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.OBJECT_TYPE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.ONCREATE, required,
				queryable, orderable, null));
		
		/*type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READONLY, !required, queryable,
				!orderable, null));*/

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.CREATED_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CREATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFIED_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFICATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHANGE_TOKEN, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PARENT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, !orderable, null));
	}

	private void addFolderPropertyDefinitions(FolderTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PATH,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!required, !queryable, !orderable, null));
		
		List<String> defaults = new ArrayList<String>();
		defaults.add(BaseTypeId.CMIS_FOLDER.value());
		defaults.add(BaseTypeId.CMIS_DOCUMENT.value());
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READONLY, !required,
				!queryable, !orderable, defaults));
	}

	private void addDocumentPropertyDefinitions(DocumentTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_IMMUTABLE, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_PRIVATE_WORKING_COPY, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_LABEL, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHECKIN_COMMENT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_LENGTH, PropertyType.INTEGER,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_MIME_TYPE, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_FILE_NAME, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
	}

	private void addRelationshipPropertyDefinitions(RelationshipTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.SOURCE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.TARGET_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, required,
				!queryable, !orderable, null));
	}
	
	private void addPolicyPropertyDefinitions(PolicyTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.POLICY_TEXT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
	}
	
	/**
	 * Creates a property definition object.
	 */
	private PropertyDefinition<?> createPropDef(String id, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updateability, boolean inherited, boolean required) {
		AbstractPropertyDefinition<?> result = null;

		switch (datatype) {
		case BOOLEAN:
			result = new PropertyBooleanDefinitionImpl();
			break;
		case DATETIME:
			result = new PropertyDateTimeDefinitionImpl();
			break;
		case DECIMAL:
			result = new PropertyDecimalDefinitionImpl();
			break;
		case HTML:
			result = new PropertyHtmlDefinitionImpl();
			break;
		case ID:
			result = new PropertyIdDefinitionImpl();
			break;
		case INTEGER:
			result = new PropertyIntegerDefinitionImpl();
			break;
		case STRING:
			result = new PropertyStringDefinitionImpl();
			break;
		case URI:
			result = new PropertyUriDefinitionImpl();
			break;
		default:
			throw new RuntimeException("Unknown datatype! Spec change?");
		}

		result.setId(id);
		result.setLocalName(id);
		result.setDisplayName(displayName);
		result.setDescription(description);
		result.setPropertyType(datatype);
		result.setCardinality(cardinality);
		result.setUpdatability(updateability);
		result.setIsInherited(inherited);
		result.setIsRequired(required);
		result.setIsQueryable(false);
		result.setQueryName(id);

		return result;
	}

	/**
	 * Adds a type to collection.
	 */
	private void addTypeInternal(AbstractTypeDefinition type) {
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
				tdc.getChildren().add(tc);
			}
		}

		types.put(type.getId(), tc);
	}

	private PropertyDefinition createPropBaseDef(
			AbstractPropertyDefinition<?> result, String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable, boolean openChoice) {
		// Set default value if not set(null)
		if (localName == null)
			localName = id;
		if (localNameSpace == null)
			localName = id;
		if (queryName == null)
			localName = id;
		if (displayName == null)
			localName = id;
		if (description == null)
			localName = id;
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

	private <T> List<T> convertListType(final Class<T> clazz, List<?> list) {
		if (CollectionUtils.isEmpty(list))
			return null;
		List<T> result = new ArrayList<T>();
		for (Object o : list) {
			result.add(clazz.cast(o));
		}
		return result;
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

	private PropertyDefinition<?> createPropBooleanTimeDef(String id,
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

	private PropertyDefinition<?> createPropDateTimeDef(String id,
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

	private PropertyDefinition<?> createPropDecimalDef(String id,
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

	private PropertyDefinition<?> createPropHtmlDef(String id,
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

	private PropertyDefinition<?> createPropIdDef(String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable,
			List<Choice<String>> choiceList, boolean openChoice,
			List<String> defaultValue) {
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

	private PropertyDefinition<?> createPropIntegerDef(String id,
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

	private PropertyDefinition<?> createPropStringDef(String id,
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

	private PropertyDefinition<?> createPropUriDef(String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable,
			List<Choice<String>> choiceList, boolean openChoice,
			List<String> defaultValue) {
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
}
