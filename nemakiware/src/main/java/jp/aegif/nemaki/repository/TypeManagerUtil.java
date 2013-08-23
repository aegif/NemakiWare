package jp.aegif.nemaki.repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.commons.collections.CollectionUtils;

public class TypeManagerUtil {
	//TODO Move to NemakiConstant class
	private static final String NAMESPACE = "http://www.aegif.jp/Nemaki";
	
	public static Object deepCopy(Object o) throws IOException, ClassNotFoundException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(o);

		byte[] buff = baos.toByteArray();
		
		ByteArrayInputStream bais = new ByteArrayInputStream(buff);
		ObjectInputStream os = new ObjectInputStream(bais);
		Object copy = os.readObject();
		return copy;
	}
	
	/**
	 * NOTE: CN add a new type only after the parent type is added.
	 * Adds a type to collection.
	 */
	public static void addTypeInternal(Map<String, TypeDefinitionContainer> types, AbstractTypeDefinition type) {
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
				
				if(!isDuplicateChild(tdc,type)){
					tdc.getChildren().add(tc);
				}
			}
		}

		types.put(type.getId(), tc);
	}
	
	private static boolean isDuplicateChild(TypeDefinitionContainer parent, TypeDefinition type){
		for(TypeDefinitionContainer child : parent.getChildren()){
			if(child.getTypeDefinition().getId().equals(type.getId())){
				return true;
			}
		}
		return false;
	}
	
	public static PropertyDefinition<?> createPropDef(String id, String localName, String localNameSpace, String queryName, String displayName, String description,
			PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean required, boolean queryable,
			boolean inherited, boolean openChoice, boolean orderable, List<?> defaultValue) {
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
	
	
	private static <T> List<T> convertListType(final Class<T> clazz, List<?> list) {
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

	private static PropertyDefinition<?> createPropIdDef(String id, String localName,
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

	public static PropertyDefinition<?> createPropStringDef(String id,
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

	private static PropertyDefinition<?> createPropUriDef(String id, String localName,
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
	
	private static PropertyDefinition createPropBaseDef(
			AbstractPropertyDefinition<?> result, String id, String localName,
			String localNameSpace, String queryName, String displayName,
			String description, PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean inherited, boolean required,
			boolean queryable, boolean orderable, boolean openChoice) {
		// Set default value if not set(null)
		if (localName == null)
			localName = id;
		if (localNameSpace == null)
			localNameSpace = id;
		if (queryName == null)
			queryName = id;
		if (displayName == null)
			displayName = id;
		if (description == null)
			description = id;
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
	
	public static PropertyDefinition<?> createDefaultPropDef(String id,
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

		result = TypeManagerUtil.createPropDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, required, queryable, inherited, openChoice, orderable, defaultValue);
		
		return result;

	}

}
