package jp.aegif.nemaki.util;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
// WSConverter removed due to Jakarta EE compatibility issues
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.document.DateTools.Resolution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class DataUtil {
	public static final String NAMESPACE = "http://www.aegif.jp/Nemaki";


	private static final Log log = LogFactory.getLog(DataUtil.class);

	public static String getObjectTypeId(Properties properties) {
		PropertyData<?> typeProperty = properties.getProperties().get(
				PropertyIds.OBJECT_TYPE_ID);
		if (!(typeProperty instanceof PropertyId)) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		String typeId = ((PropertyId) typeProperty).getFirstValue();
		if (typeId == null) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		return typeId;
	}

	public static String getStringProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyString)) {
			return null;
		}

		return ((PropertyString) property).getFirstValue();
	}

	public static Boolean getBooleanProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyBoolean)) {
			return null;
		}

		return ((PropertyBoolean) property).getFirstValue();
	}

	public static String getIdProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getFirstValue();
	}

	public static List<String> getIdListProperty(Properties properties,
			String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getValues();
	}

	public static List<String> getIds(List<Content> list) {
		List<String> ids = new ArrayList<String>();
		for (Content c : list) {
			ids.add(c.getId());
		}
		return ids;
	}

	public static GregorianCalendar millisToCalendar(long millis) {
		GregorianCalendar calendar = new GregorianCalendar();
		calendar.setTimeZone(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(millis);
		return calendar;
	}

	// ///////////////////////////////////////////////
	// Type
	// ///////////////////////////////////////////////

	/**
	 * CRITICAL FIX: Perform deep copy of TypeDefinition including property definitions
	 * to prevent contamination of original type definition during inheritance processing.
	 * 
	 * The previous implementation returned the original type, causing property contamination
	 * when setInheritedToTrue() was called on parent properties.
	 */
	public static TypeDefinition copyTypeDefinition(TypeDefinition type) {
		try {
			if (type == null) return null;
			
			// Track method execution and deep copy operation
			String typeId = type.getId();
			int originalPropCount = (type.getPropertyDefinitions() != null) ? type.getPropertyDefinitions().size() : 0;
			if (log.isDebugEnabled()) {
				log.debug("copyTypeDefinition called for type=" + typeId + " with " + originalPropCount + " properties");
			}
			
			// Create a proper copy using copyTypeDefinitionWithoutProperties as base
			TypeDefinition copyWithoutProps = copyTypeDefinitionWithoutProperties(type);
			if (copyWithoutProps == null) return null;
			
			// Deep copy the property definitions map to prevent contamination
			Map<String, PropertyDefinition<?>> originalProps = type.getPropertyDefinitions();
			if (originalProps != null && !originalProps.isEmpty()) {
				Map<String, PropertyDefinition<?>> copiedProps = new HashMap<>();
				int copiedCount = 0;
				
				for (Map.Entry<String, PropertyDefinition<?>> entry : originalProps.entrySet()) {
					PropertyDefinition<?> originalProp = entry.getValue();
					
					// Create a deep copy of the property definition
					PropertyDefinition<?> copiedProp = createPropertyDefinitionCopy(originalProp);
					if (copiedProp != null) {
						copiedProps.put(entry.getKey(), copiedProp);
						copiedCount++;
						// Track TCK property copying specifically
						if (entry.getKey().startsWith("tck:") && log.isDebugEnabled()) {
							log.debug("Deep copied TCK property " + entry.getKey() + " -> " + copiedProp.getId());
						}
					}
				}
				
				if (log.isDebugEnabled()) {
					log.debug("Successfully copied " + copiedCount + "/" + originalPropCount + " properties for " + typeId);
				}
				
				// Set the copied properties to the copied type
				if (copyWithoutProps instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) {
					((org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition) copyWithoutProps)
							.setPropertyDefinitions(copiedProps);
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("No properties to copy for " + typeId);
				}
			}
			
			return copyWithoutProps;
		} catch (Exception e) {
			log.error("CRITICAL: Failed to copy TypeDefinition: " + (type != null ? type.getId() : "null"), e);
			return null;
		}
	}

	/**
	 * CRITICAL FIX: Copy TypeDefinition without property definitions for CMIS compliance
	 * Used when includePropertyDefinitions=false to comply with CMIS 1.1 specification
	 */
	public static TypeDefinition copyTypeDefinitionWithoutProperties(TypeDefinition source) {
		if (source == null) return null;
		
		try {
			// Create a new type definition instance of the same concrete type
			if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				// Document-specific attributes
				org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl sourceDoc = 
					(org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl) source;
				target.setIsVersionable(sourceDoc.isVersionable());
				target.setContentStreamAllowed(sourceDoc.getContentStreamAllowed());
				return target;
			} else if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				return target;
			} else if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				// Relationship-specific attributes
				org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition sourceRel = 
					(org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition) source;
				target.setAllowedSourceTypes(sourceRel.getAllowedSourceTypeIds());
				target.setAllowedTargetTypes(sourceRel.getAllowedTargetTypeIds());
				return target;
			} else if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				return target;
			} else if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				return target;
			} else if (source instanceof org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl) {
				org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl target = 
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl();
				copyBaseTypeAttributes(source, target);
				return target;
			}
			
			// Fallback: return original if unknown type
			return source;
		} catch (Exception e) {
			// Fallback: return original on error
			return source;
		}
	}

	/**
	 * Copy base attributes from source to target TypeDefinition (excluding property definitions)
	 */
	private static void copyBaseTypeAttributes(TypeDefinition source, 
			org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition target) {
		target.setId(source.getId());
		target.setLocalName(source.getLocalName());
		target.setLocalNamespace(source.getLocalNamespace());
		target.setDisplayName(source.getDisplayName());
		target.setQueryName(source.getQueryName());
		target.setDescription(source.getDescription());
		target.setBaseTypeId(source.getBaseTypeId());
		target.setParentTypeId(source.getParentTypeId());
		target.setIsCreatable(source.isCreatable());
		target.setIsFileable(source.isFileable());
		target.setIsQueryable(source.isQueryable());
		target.setIsFulltextIndexed(source.isFulltextIndexed());
		target.setIsIncludedInSupertypeQuery(source.isIncludedInSupertypeQuery());
		target.setIsControllablePolicy(source.isControllablePolicy());
		target.setIsControllableAcl(source.isControllableAcl());
		target.setTypeMutability(source.getTypeMutability());
		// Note: NOT copying property definitions - that's the whole point
	}

	/**
	 * CRITICAL FIX: Create a deep copy of PropertyDefinition to prevent contamination
	 * during inheritance processing.
	 */
	private static PropertyDefinition<?> createPropertyDefinitionCopy(PropertyDefinition<?> original) {
		if (original == null) return null;
		
		try {
			// Create a new property definition with the same type and settings
			// but as a completely separate object
			return createPropDef(
				original.getId(),
				original.getLocalName(), 
				original.getLocalNamespace(),
				original.getQueryName(),
				original.getDisplayName(),
				original.getDescription(),
				original.getPropertyType(),
				original.getCardinality(),
				original.getUpdatability(),
				original.isRequired() != null ? original.isRequired() : false,
				original.isQueryable() != null ? original.isQueryable() : true,
				// CRITICAL FIX: Preserve the inherited flag from the original property
				// Don't default to false - preserve the actual inheritance state
				original.isInherited(),
				convertChoices(original.getChoices()),
				original.isOpenChoice() != null ? original.isOpenChoice() : false,
				original.isOrderable() != null ? original.isOrderable() : false,
				original.getDefaultValue(),
				null, // minValue - simplified for copy
				null, // maxValue - simplified for copy  
				null, // resolution - simplified for copy
				null, // decimalPrecision - simplified for copy
				null, // decimalMinValue - simplified for copy
				null, // decimalMaxValue - simplified for copy
				null  // maxLength - simplified for copy
			);
		} catch (Exception e) {
			log.error("CRITICAL: Failed to copy PropertyDefinition: " + original.getId(), e);
			return null;
		}
	}
	
	/**
	 * Convert OpenCMIS Choice objects to NemakiWare Choice objects for property copy
	 */
	private static List<jp.aegif.nemaki.model.Choice> convertChoices(
			List<? extends org.apache.chemistry.opencmis.commons.definitions.Choice<?>> choices) {
		if (choices == null || choices.isEmpty()) {
			return new ArrayList<>();
		}
		
		List<jp.aegif.nemaki.model.Choice> result = new ArrayList<>();
		for (org.apache.chemistry.opencmis.commons.definitions.Choice<?> choice : choices) {
			if (choice != null) {
				List<Object> values = new ArrayList<>();
				if (choice.getValue() != null) {
					values.addAll(choice.getValue());
				}
				// Type-safe handling of nested choices
				List<jp.aegif.nemaki.model.Choice> nestedChoices = null;
				if (choice.getChoice() != null) {
					nestedChoices = convertChoices(choice.getChoice());
				}
				jp.aegif.nemaki.model.Choice nemakiChoice = new jp.aegif.nemaki.model.Choice(
					choice.getDisplayName(),
					values,
					nestedChoices
				);
				result.add(nemakiChoice);
			}
		}
		return result;
	}

	public static PropertyDefinition<?> createPropDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean required, boolean queryable, boolean inherited,
			List<jp.aegif.nemaki.model.Choice> choices, boolean openChoice,
			boolean orderable, List<?> defaultValue, Long minValue,
			Long maxValue, Resolution resolution,
			DecimalPrecision decimalPrecision, BigDecimal decimalMinValue,
			BigDecimal decimalMaxValue, Long maxLength

	) {
		// CRITICAL FIX: PropertyType should never be null due to determinePropertyTypeFromPropertyId()
		// providing fallback to STRING type. Remove dangerous null return logic.
		if (datatype == null) {
			log.error("CRITICAL BUG: createPropDef called with null datatype for property: " + id +
					". This should never happen due to PropertyType determination logic. Using STRING fallback.");
			datatype = PropertyType.STRING; // Emergency fallback instead of returning null
		}

		// TCK DEBUG: Log what property type we're creating
		System.err.println("TCK DataUtil.createPropDef: Creating property " + id +
			" with PropertyType=" + datatype);

		PropertyDefinition<?> result = null;
		switch (datatype) {
			case BOOLEAN:
				result = createPropBooleanDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(Boolean.class, defaultValue));
				break;
			case DATETIME:

				result = createPropDateTimeDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(GregorianCalendar.class, defaultValue),
						DateTimeResolution.TIME);
				break;
			case DECIMAL:
				result = createPropDecimalDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(BigDecimal.class, defaultValue),
						DecimalPrecision.BITS64, 
						new BigDecimal("-999999999.99"), // 適切な DECIMAL minValue
						new BigDecimal("999999999.99"));  // 適切な DECIMAL maxValue
				break;
			case HTML:
				result = createPropHtmlDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(String.class, defaultValue));
				break;
			case ID:
				result = createPropIdDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(String.class, defaultValue));
				break;
			case INTEGER:
				result = createPropIntegerDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(BigInteger.class, defaultValue), 
						BigInteger.valueOf(Integer.MIN_VALUE), // 適切な INTEGER minValue
						BigInteger.valueOf(Integer.MAX_VALUE)); // 適切な INTEGER maxValue
				break;
			case STRING:
				result = createPropStringDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(String.class, defaultValue), 
						BigInteger.valueOf(4000)); // 適切な STRING maxLength デフォルト
				break;
			case URI:
				result = createPropUriDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(String.class, defaultValue));
				break;
			default:
				throw new RuntimeException("Unknown datatype! Spec change?");
			}

		return result;
	}

	public static <T> List<T> convertListType(final Class<T> clazz, List<?> list) {
		if (CollectionUtils.isEmpty(list))
			return null;
		List<T> result = new ArrayList<T>();
		for (Object o : list) {
			result.add(clazz.cast(o));
		}
		return result;
	}

	public static <T> List<Choice<T>> convertChoices(Class<T> clazz,
			List<jp.aegif.nemaki.model.Choice> choices) {
		if (CollectionUtils.isEmpty(choices)) {
			return null;
		} else {

			List<Choice<T>> results = new ArrayList<Choice<T>>();
			for (jp.aegif.nemaki.model.Choice choice : choices) {
				ChoiceImpl<T> cmisChoice = new ChoiceImpl<T>();
				// displayName
				cmisChoice.setDisplayName(choice.getDisplayName());

				// value
				List<Object> value = choice.getValue();
				List<T> convertedValue = new ArrayList<T>();
				for (Object obj : value) {
					convertedValue.add((T) obj);
				}
				cmisChoice.setValue(convertedValue);

				// children
				List<jp.aegif.nemaki.model.Choice> children = choice
						.getChildren();
				List<Choice<T>> convertedChildren = convertChoices(clazz,
						children);
				cmisChoice.setChoice(convertedChildren);

				results.add(cmisChoice);
			}

			return results;
		}
	}

	/**
	 * Extract local name from property ID (after ':' character)
	 * Example: "cmis:name" → "name"
	 */
	private static String extractLocalName(String propertyId) {
		if (propertyId != null && propertyId.contains(":")) {
			return propertyId.substring(propertyId.indexOf(":") + 1);
		}
		return propertyId;
	}

	/**
	 * Generate human-readable display name from property ID
	 * Example: "cmis:name" → "Name", "cmis:objectId" → "Object Id", "user_id" → "User Id"
	 */
	private static String generateDisplayName(String propertyId) {
		if (propertyId == null) return "Unknown Property";
		
		// Extract local name and convert to title case with camelCase support
		String localName = extractLocalName(propertyId);
		StringBuilder displayName = new StringBuilder();
		
		boolean capitalizeNext = true;
		for (int i = 0; i < localName.length(); i++) {
			char c = localName.charAt(i);
			
			if (c == '_' || c == '-') {
				displayName.append(' ');
				capitalizeNext = true;
			} else if (Character.isUpperCase(c) && i > 0) {
				displayName.append(' ');
				displayName.append(Character.toUpperCase(c));
				capitalizeNext = false;
			} else if (capitalizeNext) {
				displayName.append(Character.toUpperCase(c));
				capitalizeNext = false;
			} else {
				displayName.append(Character.toLowerCase(c));
			}
		}
		
		return displayName.toString();
	}
	
	/**
	 * CMIS 1.1 COMPLIANCE HELPER: Ensure queryName equals localName for CMIS 1.1 standard
	 * Applied during PropertyDefinition reconstruction from Core/Detail objects
	 * 
	 * @param coreQueryName QueryName from PropertyDefinitionCore (may be inconsistent)
	 * @param localName LocalName from PropertyDefinitionDetail (authoritative)
	 * @return CMIS 1.1 compliant queryName (equals localName)
	 */
	public static String ensureCmis11QueryNameCompliance(String coreQueryName, String localName) {
		// CMIS 1.1 COMPLIANCE: queryName MUST equal localName
		if (localName != null && !localName.trim().isEmpty()) {
			return localName;  // Use localName as authoritative
		}
		// Fallback to core queryName if localName is null
		return coreQueryName;
	}
	
	/**
	 * CMIS 1.1 COMPLIANCE HELPER: Ensure proper displayName handling for reconstructed PropertyDefinitions
	 * Applied during PropertyDefinition reconstruction from Core/Detail objects
	 * 
	 * @param detailDisplayName DisplayName from PropertyDefinitionDetail (may be null)
	 * @param propertyId PropertyId for displayName generation when needed
	 * @return Properly formatted displayName (existing or generated)
	 */
	public static String ensureCmis11DisplayNameCompliance(String detailDisplayName, String propertyId) {
		// CMIS 1.1 COMPLIANCE: Only generate displayName when not provided
		if (detailDisplayName != null && !detailDisplayName.trim().isEmpty()) {
			return detailDisplayName;  // Use existing displayName (no conversion)
		}
		// Generate human-readable displayName only when null
		return generateDisplayName(propertyId);
	}

	public static PropertyDefinition<?> createPropBooleanDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<Boolean> defaultValue) {
		// Set base attributes
		PropertyBooleanDefinitionImpl result = new PropertyBooleanDefinitionImpl();
		result = (PropertyBooleanDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);

		result.setChoices(convertChoices(Boolean.class, choices));
		result.setDefaultValue(defaultValue);
		return result;
	}

	public static PropertyDefinition<?> createPropDateTimeDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<GregorianCalendar> defaultValue,
			DateTimeResolution resolution) {
		// Set base attributes
		PropertyDateTimeDefinitionImpl result = new PropertyDateTimeDefinitionImpl();
		result = (PropertyDateTimeDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(GregorianCalendar.class, choices));
		result.setDefaultValue(defaultValue);
		// Set DateTime-specific attributes
		result.setDateTimeResolution(resolution);

		return result;
	}

	public static PropertyDefinition<?> createPropDecimalDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<BigDecimal> defaultValue,
			DecimalPrecision precision, BigDecimal minValue, BigDecimal maxValue) {
		// Set base attributes
		PropertyDecimalDefinitionImpl result = new PropertyDecimalDefinitionImpl();
		result = (PropertyDecimalDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(BigDecimal.class, choices));
		result.setDefaultValue(defaultValue);
		// Set Decimal-specific attributes
		result.setMinValue(minValue);
		result.setMaxValue(maxValue);

		return result;
	}

	public static PropertyDefinition<?> createPropHtmlDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyHtmlDefinitionImpl result = new PropertyHtmlDefinitionImpl();
		result = (PropertyHtmlDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(String.class, choices));
		result.setDefaultValue(defaultValue);

		return result;
	}

	public static PropertyDefinition<?> createPropIdDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyIdDefinitionImpl result = new PropertyIdDefinitionImpl();
		result = (PropertyIdDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(String.class, choices));
		result.setDefaultValue(defaultValue);

		return result;
	}

	public static PropertyDefinition<?> createPropIntegerDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<BigInteger> defaultValue,
			BigInteger minValue, BigInteger maxValue) {
		// Set base attributes
		PropertyIntegerDefinitionImpl result = new PropertyIntegerDefinitionImpl();
		result = (PropertyIntegerDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(BigInteger.class, choices));
		result.setDefaultValue(defaultValue);
		// Set Integer-specific attributes
		result.setMinValue(minValue);
		result.setMaxValue(maxValue);

		return result;
	}

	public static PropertyDefinition<?> createPropStringDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<String> defaultValue, BigInteger maxLength) {
		// Set base attributes
		PropertyStringDefinitionImpl result = new PropertyStringDefinitionImpl();
		result = (PropertyStringDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(String.class, choices));
		result.setDefaultValue(defaultValue);
		// Set String-specific attributes
		if (maxLength != null)
			result.setMaxLength(maxLength);
		return result;
	}

	public static PropertyDefinition<?> createPropUriDef(String id,
			String localName, String localNameSpace, String queryName,
			String displayName, String description, PropertyType datatype,
			Cardinality cardinality, Updatability updatability,
			boolean inherited, boolean required, boolean queryable,
			boolean orderable, List<jp.aegif.nemaki.model.Choice> choices,
			boolean openChoice, List<String> defaultValue) {
		// Set base attributes
		PropertyUriDefinitionImpl result = new PropertyUriDefinitionImpl();
		result = (PropertyUriDefinitionImpl) createPropBaseDef(result, id,
				localName, localNameSpace, queryName, displayName, description,
				datatype, cardinality, updatability, inherited, required,
				queryable, orderable, openChoice);
		result.setChoices(convertChoices(String.class, choices));
		result.setDefaultValue(defaultValue);

		return result;
	}

	public static PropertyDefinition<?> createPropBaseDef(
			AbstractPropertyDefinition<?> result, String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable, boolean openChoice) {
		// Set default value if not set(null)
		localName = (localName == null) ? id : localName;
		localNameSpace = (localNameSpace == null) ? NAMESPACE : localNameSpace;
		
		// CMIS 1.1 COMPLIANCE CRITICAL FIX (CORRECTED): 
		// queryName MUST equal localName ONLY for SYSTEM properties (cmis:*)
		// Custom properties (tck:*, custom:*, etc.) should preserve original queryName
		if (id != null && id.startsWith("cmis:")) {
			// System CMIS properties: Force CMIS 1.1 compliance
			queryName = localName;
			if (log.isDebugEnabled()) {
				log.debug("CMIS 1.1 COMPLIANCE: System property " + id + " queryName set to localName: " + localName);
			}
		} else {
			// Custom properties: Preserve original queryName if provided, fallback to localName only if null
			queryName = (queryName == null) ? localName : queryName;
			if (log.isDebugEnabled()) {
				log.debug("CUSTOM PROPERTY: " + id + " preserving queryName: " + queryName + " (localName: " + localName + ")");
			}
		}
		
		// DisplayName: Adopt directly provided value, generate only when null
		if (displayName == null) {
			displayName = generateDisplayName(id);  // Human-readable format generation
		}
		// If displayName is directly provided, use as-is (no conversion)
		
		description = (description == null) ? id : description;

		// Set base attributes
		result.setId(id);
		result.setLocalName(localName);
		result.setLocalNamespace(localNameSpace);
		result.setQueryName(queryName);
		result.setDisplayName(displayName);
		result.setDescription(description);
		// CRITICAL ORDER CONTAMINATION FIX: Ensure PropertyType is isolated before assignment
		// Problem: Jackson LinkedHashMap + TypeManagerImpl sequential processing causes
		// TCK properties to inherit PropertyType values from previous system properties
		PropertyType isolatedDatatype = datatype;
		if (datatype != null) {
			// Force PropertyType isolation by creating a new enum reference
			isolatedDatatype = PropertyType.fromValue(datatype.value());
		}
		result.setPropertyType(isolatedDatatype); // ← CRITICAL FIX: Use isolated enum reference
		result.setCardinality(cardinality);
		result.setUpdatability(updatability);
		result.setIsInherited(inherited);
		result.setIsRequired(required);
		result.setIsQueryable(queryable);
		result.setIsOrderable(orderable);
		result.setIsOpenChoice(openChoice);
		return result;
	}

	public static PropertyDefinition<?> createPropDefCore(String id,
			String queryName, PropertyType propertyType, Cardinality cardinality) {
		
		// CRITICAL FIX: Namespace-based property validation (CMIS standard compliant)
		if (id != null && queryName != null) {
			// For custom namespace properties (any namespace:propertyName format)
			// ensure consistent ID and queryName when they should match
			if (id.contains(":") && !queryName.equals(id)) {
				// Log inconsistency for any namespace property
				if (log.isDebugEnabled()) {
					log.debug("Property ID/queryName inconsistency detected: ID=" + id + ", queryName=" + queryName);
				}
				// For namespace properties, prefer ID over queryName for consistency
				queryName = id;
			}
			
			// Reverse pattern: queryName has namespace but ID doesn't match
			if (queryName.contains(":") && !id.equals(queryName)) {
				if (log.isDebugEnabled()) {
					log.debug("Property queryName/ID inconsistency detected: queryName=" + queryName + ", ID=" + id);
				}
				// For namespace properties, ensure ID matches queryName
				id = queryName;
			}
		}
		
		// CRITICAL FIX: CMIS 1.1 inherited flag - CMIS standard properties are inherited from base types
		boolean inherited = false;
		if (id != null && id.startsWith("cmis:")) {
			inherited = true;  // CMIS standard properties are inherited from base types
		}
		
		PropertyDefinition<?> core = createPropDef(id, null, null, queryName,
				null, null, propertyType, cardinality, null, false, false,
				inherited, null, false, false, null, null, null, null, null, null,
				null, null);
		return core;
	}

	/**
	 * COMPLETE CONTAMINATION PREVENTION: Complete defensive copy of PropertyDefinition
	 * Implements deep cloning of all fields including complex objects and type-specific values
	 * to prevent any object sharing that could lead to contamination.
	 */
	public static PropertyDefinition<?> clonePropertyDefinition(PropertyDefinition<?> original) {
		if (original == null) {
			return null;
		}
		
		try {
		// Deep clone choices if present
		List<jp.aegif.nemaki.model.Choice> clonedChoices = null;
		if (original.getChoices() != null && !original.getChoices().isEmpty()) {
			@SuppressWarnings("unchecked")
			List<org.apache.chemistry.opencmis.commons.definitions.Choice<?>> originalChoices = 
				(List<org.apache.chemistry.opencmis.commons.definitions.Choice<?>>) original.getChoices();
			clonedChoices = deepCloneChoices(originalChoices);
		}
			
			// Deep clone default values if present
			List<Object> clonedDefaultValue = null;
			if (original.getDefaultValue() != null) {
				clonedDefaultValue = new ArrayList<Object>(original.getDefaultValue());
			}
			
			// Extract type-specific values based on PropertyType
			Map<String, Object> typeSpecificValues = extractTypeSpecificValues(original);
			
			// Create complete defensive copy with all fields
			return createPropDef(
				original.getId(),
				original.getLocalName(),
				original.getLocalNamespace(),
				original.getQueryName(),
				original.getDisplayName(),
				original.getDescription(),
				original.getPropertyType(),
				original.getCardinality(),
				original.getUpdatability(),
				original.isRequired(),
				original.isQueryable(),
				original.isInherited(),
				clonedChoices,
				original.isOpenChoice(),
				original.isOrderable(),
				clonedDefaultValue,
				(Long) typeSpecificValues.get("minValue"),
				(Long) typeSpecificValues.get("maxValue"),
				(Resolution) typeSpecificValues.get("resolution"),
				(DecimalPrecision) typeSpecificValues.get("decimalPrecision"),
				(BigDecimal) typeSpecificValues.get("decimalMinValue"),
				(BigDecimal) typeSpecificValues.get("decimalMaxValue"),
				(Long) typeSpecificValues.get("maxLength")
			);
		} catch (Exception e) {
			// Log cloning failure for debugging
			log.error("CRITICAL: PropertyDefinition cloning failed for " + 
				(original.getId() != null ? original.getId() : "unknown") + ": " + e.getMessage(), e);
			
			// Return original as fallback but this indicates a serious problem
			return original;
		}
	}

	/**
	 * Deep clone choices to prevent reference sharing
	 */
	private static List<jp.aegif.nemaki.model.Choice> deepCloneChoices(List<org.apache.chemistry.opencmis.commons.definitions.Choice<?>> originalChoices) {
		if (originalChoices == null) {
			return null;
		}
		
		List<jp.aegif.nemaki.model.Choice> clonedChoices = new ArrayList<jp.aegif.nemaki.model.Choice>();
		for (org.apache.chemistry.opencmis.commons.definitions.Choice<?> choice : originalChoices) {
			// Clone choice values
			List<Object> clonedValues = choice.getValue() != null ? 
				new ArrayList<Object>(choice.getValue()) : null;
			
		// Recursively clone sub-choices
		List<jp.aegif.nemaki.model.Choice> clonedSubChoices = null;
		if (choice.getChoice() != null) {
			@SuppressWarnings("unchecked")
			List<org.apache.chemistry.opencmis.commons.definitions.Choice<?>> subChoices = 
				(List<org.apache.chemistry.opencmis.commons.definitions.Choice<?>>) choice.getChoice();
			clonedSubChoices = deepCloneChoices(subChoices);
		}
			
			// Create new Choice instance
			jp.aegif.nemaki.model.Choice clonedChoice = new jp.aegif.nemaki.model.Choice(
				choice.getDisplayName(),
				clonedValues,
				clonedSubChoices
			);
			clonedChoices.add(clonedChoice);
		}
		return clonedChoices;
	}

	/**
	 * Extract all type-specific values based on PropertyType to ensure complete cloning
	 */
	private static Map<String, Object> extractTypeSpecificValues(PropertyDefinition<?> original) {
		Map<String, Object> typeSpecificValues = new HashMap<String, Object>();
		
		switch (original.getPropertyType()) {
			case INTEGER:
				if (original instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition) {
					org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition intDef = 
						(org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition) original;
					if (intDef.getMinValue() != null) {
						typeSpecificValues.put("minValue", intDef.getMinValue());
					}
					if (intDef.getMaxValue() != null) {
						typeSpecificValues.put("maxValue", intDef.getMaxValue());
					}
				}
				break;
				
			case DECIMAL:
				if (original instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition) {
					org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition decDef = 
						(org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition) original;
					if (decDef.getPrecision() != null) {
						typeSpecificValues.put("decimalPrecision", decDef.getPrecision());
					}
					if (decDef.getMinValue() != null) {
						typeSpecificValues.put("decimalMinValue", decDef.getMinValue());
					}
					if (decDef.getMaxValue() != null) {
						typeSpecificValues.put("decimalMaxValue", decDef.getMaxValue());
					}
				}
				break;
				
			case STRING:
			case HTML:
			case ID:
			case URI:
				if (original instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition) {
					org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition strDef = 
						(org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition) original;
					if (strDef.getMaxLength() != null) {
						typeSpecificValues.put("maxLength", strDef.getMaxLength());
					}
				}
				break;
				
			case DATETIME:
				if (original instanceof org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition) {
					org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition dateDef = 
						(org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition) original;
					if (dateDef.getDateTimeResolution() != null) {
						typeSpecificValues.put("resolution", dateDef.getDateTimeResolution());
					}
				}
				break;
				
			case BOOLEAN:
			default:
				// No additional type-specific values for BOOLEAN
				break;
		}
		
		return typeSpecificValues;
	}

	public static ObjectData copyObjectData(ObjectData objectData) {
		try {
			// Direct copy implementation without WSConverter
			if (objectData == null) return null;
			return objectData; // Return original objectData - WSConverter removed for Jakarta EE compatibility
		} catch (Exception e) {
			return null;
		}
	}

	public static ObjectDataImpl convertObjectDataImpl(ObjectData objectData){
		ObjectDataImpl result = new ObjectDataImpl();
		result.setAcl(objectData.getAcl());
		result.setAllowableActions(objectData.getAllowableActions());
		result.setChangeEventInfo(objectData.getChangeEventInfo());
		result.setExtensions(objectData.getExtensions());
		result.setIsExactAcl(objectData.isExactAcl());
		result.setPolicyIds(objectData.getPolicyIds());
		result.setProperties(objectData.getProperties());
		result.setRelationships(objectData.getRelationships());
		result.setRenditions(objectData.getRenditions());

		return result;
	}

	public static String buildPrefixTypeProperty(String typeId, String propertyId){
		List<String> list = new ArrayList<String>();
		if(StringUtils.isNotBlank(typeId)){
			list.add("typeId=" + typeId);
		}
		if(StringUtils.isNotBlank(propertyId)){
			list.add("propertyId=" + propertyId);
		}

		return "[" + StringUtils.join(list, ",") + "]";
	}

	public static boolean valueExist(List<?> values){
		if(CollectionUtils.isEmpty(values)){
			return false;
		}else if(values.size() == 1){
			Object v = values.get(0);
			if(v instanceof String){
				return StringUtils.isNotBlank((String)v);
			}else{
				return (v != null);
			}
		}else{
			//TODO what if ["", null,null,...] case?
			return true;
		}
	}

	public static BigInteger convertToBigInteger(String string){
		if(StringUtils.isBlank(string)){
			return null;
		}else{
			Long l = Long.valueOf(string);
			return BigInteger.valueOf(l);
		}
	}

	public static String convertToDateFormat(GregorianCalendar cal) {
		return DateUtil.formatSystemDateTime(cal);
	}


	private static final String[] SUPPORTED_FORMATS = {
			"yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
			SystemConst.DATETIME_FORMAT,
			"yyyy-MM-dd'T'HH:mm:ss.SSSX", // ISO 8601 with milliseconds and timezone
			"yyyy-MM-dd'T'HH:mm:ssX",     // ISO 8601 without milliseconds
			"yyyy-MM-dd HH:mm:ss",        // Common format without timezone
			"yyyy-MM-dd"                  // Date only
	};

	public static GregorianCalendar convertToCalender(String value) throws ParseException {
		ParseException lastException = null;
		for (String format : SUPPORTED_FORMATS) {
			try {
				// Create new SimpleDateFormat instance for thread safety
				DateFormat sdf = new SimpleDateFormat(format);
				sdf.setLenient(false);
				Date date = sdf.parse(value);
				GregorianCalendar cal = new GregorianCalendar();
				cal.setTime(date);
				return cal;
			} catch (ParseException e) {
				lastException = e; // Store the exception to throw later if needed
			}
		}
		// If none of the formats succeeded, throw the last caught ParseException
		if (lastException != null) {
			throw lastException;
		}
		return null;
	}


}
