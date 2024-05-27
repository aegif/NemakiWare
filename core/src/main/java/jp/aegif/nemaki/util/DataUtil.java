package jp.aegif.nemaki.util;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.*;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.*;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.WSConverter;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.*;
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
import java.util.*;

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

	public static TypeDefinition copyTypeDefinition(TypeDefinition type) {
		try {
			return WSConverter.convert(WSConverter.convert(type));
		} catch (Exception e) {
			return null;
		}
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
		PropertyDefinition<?> result = null;
		if (datatype != null) {
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
						DecimalPrecision.BITS64, null, null);
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
						convertListType(BigInteger.class, defaultValue), null,
						null);
				break;
			case STRING:
				result = createPropStringDef(id, localName, localNameSpace,
						queryName, displayName, description, datatype,
						cardinality, updatability, inherited, required,
						queryable, orderable, choices, openChoice,
						convertListType(String.class, defaultValue), null);
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

	public static PropertyDefinition<?> createPropDefCore(String id,
			String queryName, PropertyType propertyType, Cardinality cardinality) {
		PropertyDefinition<?> core = createPropDef(id, null, null, queryName,
				null, null, propertyType, cardinality, null, false, false,
				false, null, false, false, null, null, null, null, null, null,
				null, null);
		return core;
	}

	public static ObjectData copyObjectData(ObjectData objectData) {
		try {
			return WSConverter.convert(WSConverter.convert(objectData, CmisVersion.CMIS_1_1));
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
		SimpleDateFormat sdf = new SimpleDateFormat(SystemConst.DATETIME_FORMAT);
		return sdf.format(cal.getTime());
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