/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
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

import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.Converter;
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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Type Manager class, defines document/folder/relationship/policy
 */
public class TypeManager implements
		org.apache.chemistry.opencmis.server.support.TypeManager {

	private static final Log log = LogFactory.getLog(TypeManager.class);
	private static final String ns = "http://www.aegif.jp/Nemaki";

	/**
	 * Pre-defined types.
	 */
	public final static String DOCUMENT_TYPE_ID = "cmis:document";
	public final static String FOLDER_TYPE_ID = "cmis:folder";
	public final static String RELATIONSHIP_TYPE_ID = "cmis:relationship";
	public final static String POLICY_TYPE_ID = "cmis:policy";
	public final static String ITEM_TYPE_ID = "cmis:item";
	public final static String SECONDARY_TYPE_ID = "cmis:secondary";

	/**
	 * Yaml manager
	 */
	private final String baseModelFile = "base_model.yml";
	YamlManager ymlMgr;



	/**
	 * Types namespace.
	 */
	private static final String NAMESPACE = "http://aegif.jp/nemaki";

	/**
	 * Map of all types.
	 */
	private Map<String, TypeDefinitionContainer> types;

	/**
	 * List of all types. Contains the same information as the "types" Map, in a
	 * different form.
	 */
	private List<TypeDefinitionContainer> typesAsList;

	private FixedTypeManager fixedTypeManager;
	
	/**
	 * Constructor.
	 */
	public TypeManager() {

	}

	public TypeManager(FixedTypeManager fixedTypeManager) {
		types = fixedTypeManager.getTypes();
		typesAsList = fixedTypeManager.getTypesAsList();
		ymlMgr = new YamlManager(baseModelFile);

		// addSecondaryTypes();
	}


	private void addBasePropertyDefinitions(AbstractTypeDefinition type) {
		//type.addPropertyDefinition(createSimplePropDef(PropertyIds.OBJECT_ID, PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, true, true, true, null));
		
		type.addPropertyDefinition(createPropDef(PropertyIds.OBJECT_ID,
				"Object Id", "Object Id", PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.BASE_TYPE_ID,
				"Base Type Id", "Base Type Id", PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.OBJECT_TYPE_ID,
				"Type Id", "Type Id", PropertyType.ID, Cardinality.SINGLE,
				Updatability.ONCREATE, false, true));

		// SecondaryType Ids
		type.addPropertyDefinition(createPropDef(
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS, "secondary type ids",
				"secondary type ids", PropertyType.ID, Cardinality.MULTI,
				Updatability.READWRITE, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.NAME, "Name",
				"Name", PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READWRITE, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.DESCRIPTION,
				"Description", "Description", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READWRITE, false, false));

		// Nemaki specific(for document)
		type.addPropertyDefinition(createPropDef(PropertyIds.PARENT_ID,
				"Parent Id", "Parent Id", PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.CREATED_BY,
				"Created By", "Created By", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.CREATION_DATE,
				"Creation Date", "Creation Date", PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.LAST_MODIFIED_BY,
				"Last Modified By", "Last Modified By", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.LAST_MODIFICATION_DATE, "Last Modification Date",
				"Last Modification Date", PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.CHANGE_TOKEN,
				"Change Token", "Change Token", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, false, false));
	}

	private void addFolderPropertyDefinitions(FolderTypeDefinitionImpl type) {
		type.addPropertyDefinition(createPropDef(
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS,
				"Allowed Child Object Type Ids",
				"Allowed Child Object Type Ids", PropertyType.ID,
				Cardinality.MULTI, Updatability.READONLY, false, false));
	}

	private void addDocumentPropertyDefinitions(DocumentTypeDefinitionImpl type) {
		
		type.addPropertyDefinition(createPropDef(PropertyIds.IS_IMMUTABLE,
				"Is Immutable", "Is Immutable", PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.IS_LATEST_VERSION,
				"Is Latest Version", "Is Latest Version", PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.IS_MAJOR_VERSION,
				"Is Major Version", "Is Major Version", PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.IS_LATEST_MAJOR_VERSION, "Is Latest Major Version",
				"Is Latest Major Version", PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.VERSION_LABEL,
				"Version Label", "Version Label", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(PropertyIds.VERSION_SERIES_ID,
				"Version Series Id", "Version Series Id", PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
				"Is Verison Series Checked Out",
				"Is Verison Series Checked Out", PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, false, true));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
				"Version Series Checked Out Id",
				"Version Series Checked Out Id", PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.CHECKIN_COMMENT,
				"Checkin Comment", "Checkin Comment", PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.CONTENT_STREAM_LENGTH, "Content Stream Length",
				"Content Stream Length", PropertyType.INTEGER,
				Cardinality.SINGLE, Updatability.READONLY, false, false));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.CONTENT_STREAM_MIME_TYPE, "MIME Type", "MIME Type",
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				false, false));

		type.addPropertyDefinition(createPropDef(
				PropertyIds.CONTENT_STREAM_FILE_NAME, "Filename", "Filename",
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				false, false));

		type.addPropertyDefinition(createPropDef(PropertyIds.CONTENT_STREAM_ID,
				"Content Stream Id", "Content Stream Id", PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, false, false));
	}

	/**
	 * Creates a property definition object.
	 */
	private PropertyDefinition<?> createPropDef(String id, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required) {
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
		result.setUpdatability(updatability);
		result.setIsInherited(inherited);
		result.setIsRequired(required);
		result.setIsQueryable(false);
		result.setQueryName(id);

		return result;
	}

	private PropertyDefinition createPropBaseDef(
			AbstractPropertyDefinition<?> result, String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable, boolean openChoice) {
		//Set default value if not set(null)
		if(localName == null) localName = id;
		if(localNameSpace == null) localName = id;
		if(queryName == null) localName = id;
		if(displayName == null) localName = id;
		if(description == null) localName = id;
		//Set base attributes
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
	

	private PropertyDefinition<?> createSimplePropDef(String id,
			PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean required, boolean queryable,
			boolean orderable,List<Object> defaultValue) {
		PropertyDefinition<?> result = null;
		
		//Default values
		String localName = null;
		String localNameSpace = null;
		String queryName = null;
		String displayName = null;
		String description = null;
		boolean inherited = false;
		boolean openChoice = false;
		
		switch (datatype) {
		case BOOLEAN:
			result = createPropBooleanTimeDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null);
			break;
		case DATETIME:
			result = createPropDateTimeDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null, DateTimeResolution.TIME);
			result = new PropertyDateTimeDefinitionImpl();
			break;
		case DECIMAL:
			result = createPropDecimalDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null, DecimalPrecision.BITS64, null, null);
			break;
		case HTML:
			result = createPropHtmlDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null);
			break;
		case ID:
			result = createPropIdDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null);
			break;
		case INTEGER:
			result = createPropIntegerDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null, null, null);
			break;
		case STRING:
			result = createPropStringDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null, null);
			break;
		case URI:
			result = createPropUriDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, inherited, required, queryable, orderable, null, openChoice, null);
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

	/**
	 * For Secondary Type properties
	 * 
	 * @param propertyName
	 * @param property
	 * @return
	 */
	private PropertyDefinition<?> createPropDefFromMap(String propertyName,
			Map<String, String> property) {
		String id = propertyName;
		String displayName = property.get("displayName");
		String description = property.get("description");
		PropertyType datatype = PropertyType.STRING;
		Cardinality cardinality = Cardinality.SINGLE;
		Updatability updatability = Updatability.READONLY;
		Boolean inherited = false;
		Boolean required = false;

		return createPropDef(id, displayName, description, datatype,
				cardinality, updatability, inherited, required);
	}

	/**
	 * Adds a type to collection with inheriting base type properties.
	 */
	public boolean addType(TypeDefinition type) {
		if (type == null) {
			return false;
		}

		if (type.getBaseTypeId() == null) {
			return false;
		}

		// find base type
		TypeDefinition baseType = null;
		if (type.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
			baseType = copyTypeDefinition(types.get(DOCUMENT_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
			baseType = copyTypeDefinition(types.get(FOLDER_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
			baseType = copyTypeDefinition(types.get(RELATIONSHIP_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_POLICY) {
			baseType = copyTypeDefinition(types.get(POLICY_TYPE_ID)
					.getTypeDefinition());
		} else {
			return false;
		}

		AbstractTypeDefinition newType = (AbstractTypeDefinition) copyTypeDefinition(type);

		// copy property definition
		for (PropertyDefinition<?> propDef : baseType.getPropertyDefinitions()
				.values()) {
			((AbstractPropertyDefinition<?>) propDef).setIsInherited(true);
			newType.addPropertyDefinition(propDef);
		}

		// add it
		addTypeInternal(newType);

		log.info("Added type '" + newType.getId() + "'.");

		return true;
	}

	/**
	 * Adds a type to collection.
	 */
	private void addTypeInternal(AbstractTypeDefinition type) {
		if (type == null) {
			return;
		}

		if (types.containsKey(type.getId())) {
			log.warn("Can't overwrite a type");
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
		typesAsList.add(tc);
	}

	/**
	 * CMIS getTypesChildren.
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
			if (skip < 1) {
				result.getList().add(
						copyTypeDefinition(types.get(FOLDER_TYPE_ID)
								.getTypeDefinition()));
				max--;
			}
			if ((skip < 2) && (max > 0)) {
				result.getList().add(
						copyTypeDefinition(types.get(DOCUMENT_TYPE_ID)
								.getTypeDefinition()));
				max--;
			}

			result.setHasMoreItems((result.getList().size() + skip) < 2);
			result.setNumItems(BigInteger.valueOf(2));
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
						copyTypeDefinition(child.getTypeDefinition()));

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
				type.getPropertyDefinitions().clear();
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
		}

		// set property definition flag to default value if not set
		boolean ipd = (includePropertyDefinitions == null ? false
				: includePropertyDefinitions.booleanValue());

		if (typeId == null) {
			result.add(getTypesDescendants(d, types.get(FOLDER_TYPE_ID), ipd));
			result.add(getTypesDescendants(d, types.get(DOCUMENT_TYPE_ID), ipd));
			result.add(getTypesDescendants(d, types.get(RELATIONSHIP_TYPE_ID),
					includePropertyDefinitions));
			result.add(getTypesDescendants(d, types.get(POLICY_TYPE_ID),
					includePropertyDefinitions));
		} else {
			TypeDefinitionContainer tc = types.get(typeId);
			if (tc != null) {
				result.add(getTypesDescendants(d, tc, ipd));
			}
		}

		return result;
	}

	/**
	 * Gathers the type descendants tree.
	 */
	private TypeDefinitionContainer getTypesDescendants(int depth,
			TypeDefinitionContainer tc, boolean includePropertyDefinitions) {
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl();

		TypeDefinition type = copyTypeDefinition(tc.getTypeDefinition());
		if (!includePropertyDefinitions) {
			type.getPropertyDefinitions().clear();
		}

		result.setTypeDefinition(type);

		if (depth != 0) {
			if (tc.getChildren() != null) {
				result.setChildren(new ArrayList<TypeDefinitionContainer>());
				for (TypeDefinitionContainer tdc : tc.getChildren()) {
					result.getChildren().add(
							getTypesDescendants(depth < 0 ? -1 : depth - 1,
									tdc, includePropertyDefinitions));
				}
			}
		}

		return result;
	}

	/**
	 * For internal use.
	 */
	public TypeDefinition getType(String typeId) {
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			return null;
		}

		return tc.getTypeDefinition();
	}

	/**
	 * CMIS getTypeDefinition.
	 */
	public TypeDefinition getTypeDefinition(CallContext context, String typeId) {
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			throw new CmisObjectNotFoundException("Type '" + typeId
					+ "' is unknown!");
		}

		return copyTypeDefinition(tc.getTypeDefinition());
	}

	private TypeDefinition copyTypeDefinition(TypeDefinition type) {
		return Converter.convert(Converter.convert(type));
	}

	public TypeDefinitionContainer getTypeById(String typeId) {
		return types.get(typeId);
	}

	public TypeDefinition getTypeByQueryName(String typeQueryName) {
		for (Entry<String, TypeDefinitionContainer> entry : types.entrySet()) {
			if (entry.getValue().getTypeDefinition().getQueryName()
					.equals(typeQueryName))
				return entry.getValue().getTypeDefinition();
		}
		return null;
	}

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

	public List<TypeDefinitionContainer> getRootTypes() {
		// just take first repository
		List<TypeDefinitionContainer> rootTypes = new ArrayList<TypeDefinitionContainer>();
		for (TypeDefinitionContainer type : types.values()) {
			if (isRootType(type)) {
				rootTypes.add(type);
			}
		}
		return rootTypes;
	}

	public String getPropertyIdForQueryName(TypeDefinition typeDefinition,
			String propQueryName) {
		// TODO Auto-generated method stub
		return null;
	}

	private static boolean isRootType(TypeDefinitionContainer c) {
		log.debug("c.getTypeDefinition(): " + c.getTypeDefinition());

		return false;
	}

	private void addSecondaryTypes() {
		Map<String, Object> aspects = null;
		try {
			aspects = (Map<String, Object>) ymlMgr.loadYml();
		} catch (Exception e) {
			// TODO logging
			e.printStackTrace();
		}

		if (aspects == null)
			return;

		for (String aspectName : aspects.keySet()) {
			SecondaryTypeDefinitionImpl aspectScdType = buildBaseAspect(aspectName);

			Map<String, Object> aspect = (Map<String, Object>) aspects
					.get(aspectName);
			Map<String, Object> properties = (Map<String, Object>) aspect
					.get("properties");
			for (String propertyName : properties.keySet()) {
				Map<String, String> property = (Map<String, String>) properties
						.get(propertyName);

				// Nemaki Property name convention: <aspectname>:<propertyname>
				aspectScdType.addPropertyDefinition(createPropDefFromMap(
						"cmis:" + propertyName, property));

			}

			addTypeInternal(aspectScdType);

		}

	}

	private SecondaryTypeDefinitionImpl buildBaseAspect(String aspectName) {
		String cmisAspectName = aspectName;

		SecondaryTypeDefinitionImpl secondaryType = new SecondaryTypeDefinitionImpl();
		secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		secondaryType.setIsControllableAcl(false);
		secondaryType.setIsControllablePolicy(false);
		secondaryType.setIsCreatable(false);
		secondaryType.setDescription(cmisAspectName);
		secondaryType.setDisplayName(cmisAspectName);
		secondaryType.setIsFileable(false);
		secondaryType.setIsIncludedInSupertypeQuery(true);
		secondaryType.setLocalName(cmisAspectName);
		secondaryType.setLocalNamespace(NAMESPACE);
		secondaryType.setIsQueryable(false);
		secondaryType.setQueryName(cmisAspectName);
		secondaryType.setParentTypeId(SECONDARY_TYPE_ID);
		secondaryType.setId("cmis:" + cmisAspectName);

		return secondaryType;
	}

	public void setFixedTypeManager(FixedTypeManager fixedTypeManager) {
		this.fixedTypeManager = fixedTypeManager;
	}
}
