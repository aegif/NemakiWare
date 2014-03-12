package jp.aegif.nemaki.util;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.constant.PropertyKey;
import jp.aegif.nemaki.repository.type.TypeManager;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.comparators.ComparatorChain;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SortUtil {

	private static final Log log = LogFactory.getLog(SortUtil.class);

	private TypeManager typeManager;
	private RepositoryInfo repositoryInfo;
	private NemakiPropertyManager propertyManager;

	@SuppressWarnings("unchecked")
	public void sort(List<ObjectData> list, String orderBy) {
		//Check empty list
		if(CollectionUtils.isEmpty(list)){
			return;
		}
		
		// Check orderBy argument
		if (StringUtils.isEmpty(orderBy)) {
			String defaultOrderBy = propertyManager
					.readValue(PropertyKey.CAPABILITY_EXTENDED_ORDERBY_DEFAULT);
			if (StringUtils.isBlank(defaultOrderBy)) {
				return;
			} else {
				orderBy = defaultOrderBy;
			}
		}

		// Check CapabilityOrderBy
		CapabilityOrderBy capabilityOrderBy = repositoryInfo.getCapabilities()
				.getOrderByCapability();
		if (CapabilityOrderBy.NONE == capabilityOrderBy) {
			return;
		}

		// Check the parsed result of orderBy argument
		LinkedHashMap<PropertyDefinition<?>, Boolean> _orderBy = parseOrderBy(
				orderBy, capabilityOrderBy);
		if (MapUtils.isEmpty(_orderBy)) {
			return;
		}

		// Build ComparatorChain
		ComparatorChain chain = new ComparatorChain();
		for (Entry<PropertyDefinition<?>, Boolean> o : _orderBy.entrySet()) {
			PropertyComparator comparator = new PropertyComparator(o.getKey());
			chain.addComparator(comparator, o.getValue());
		}

		// Sort
		Collections.sort(list, chain);
	}

	private class PropertyComparator implements Comparator<ObjectData> {
		PropertyDefinition<?> propertyDefinition;

		public PropertyComparator(PropertyDefinition<?> propertyDefinition) {
			this.propertyDefinition = propertyDefinition;
		}

		@Override
		public int compare(ObjectData o1, ObjectData o2) {
			String propertyId = propertyDefinition.getId();
			PropertyData<?> pd1 = o1.getProperties().getProperties()
					.get(propertyId);
			PropertyData<?> pd2 = o2.getProperties().getProperties()
					.get(propertyId);

			Object val1 = pd1.getFirstValue();
			Object val2 = pd2.getFirstValue();

			// Null values are put to the last
			if (val1 == null && val2 == null) {
				return 0;
			} else if (val1 == null && val2 != null) {
				return 1;
			} else if (val1 != null && val2 == null) {
				return -1;
			} else {
				PropertyType pType = propertyDefinition.getPropertyType();
				if (PropertyType.STRING == pType || PropertyType.ID == pType
						|| PropertyType.HTML == pType
						|| PropertyType.URI == pType) {
					String _val1 = (String) val1;
					String _val2 = (String) val2;
					return _val1.compareTo(_val2);
				} else if (PropertyType.BOOLEAN == pType) {
					Boolean _val1 = (Boolean) val1;
					Boolean _val2 = (Boolean) val2;
					return _val1.compareTo(_val2);
				} else if (PropertyType.DATETIME == pType) {
					GregorianCalendar _val1 = (GregorianCalendar) val1;
					GregorianCalendar _val2 = (GregorianCalendar) val2;
					return _val1.compareTo(_val2);
				} else if (PropertyType.INTEGER == pType) {
					Integer _val1 = (Integer) val1;
					Integer _val2 = (Integer) val2;
					return _val1.compareTo(_val2);
				} else if (PropertyType.DECIMAL == pType) {
					BigDecimal _val1 = (BigDecimal) val1;
					BigDecimal _val2 = (BigDecimal) val2;
					return _val1.compareTo(_val2);
				}
			}

			return 0;
		}
	}

	/**
	 * Build an ordered map of PropertyDefinition and reverse order flag
	 * 
	 * @param orderBy
	 * @return
	 */
	private LinkedHashMap<PropertyDefinition<?>, Boolean> parseOrderBy(
			String orderBy, CapabilityOrderBy capabilityOrderBy) {

		if (StringUtils.isBlank(orderBy)) {
			return null;
		}

		String[] orders = StringUtils.split(orderBy, ",");
		LinkedHashMap<PropertyDefinition<?>, Boolean> map = new LinkedHashMap<PropertyDefinition<?>, Boolean>();
		for (int i = 0; i < orders.length; i++) {
			// Split queryName and its modifier(separated with space)
			String[] order = orders[i].split("[\\s]+");

			// Property definition
			PropertyDefinition<?> pdf = convertToPropertyDefinition(order[0]);
			if (pdf == null) {
				log.warn("Invalid property query name in orderBy argument is ignored: propertyId="
						+ order[0]);
				continue;
			} else if (capabilityOrderBy == CapabilityOrderBy.COMMON &&
					!isCommonProperty(pdf)) {
				log.warn("This property query name in orderBy argument is not supported when capabilityOrderBy=common: propertyId="
						+ order[0]);
				continue;
			}else if(!pdf.isOrderable()){
				log.warn("This property query name in orderBy argument is not orderable and ignored: propertyId="
						+ order[0]);
			}

			// Modifier
			Boolean desc = false;
			if (order.length > 1) {
				if ("DESC".equals(order[1])) {
					desc = true;
				} else if (StringUtils.isNotBlank(order[1])) {
					log.warn("Invalid modifier other than DESC in orderBy argument is ignored: propertyId="
							+ order[0]);
				}
			}

			map.put(pdf, desc);
		}
		return map;
	}

	/**
	 * Get a PropertyDefinition cores from queryName
	 * 
	 * @param queryName
	 * @return
	 */
	private PropertyDefinition<?> convertToPropertyDefinition(String queryName) {
		String[] _queryName = StringUtils.split(queryName, ".");
		if (_queryName.length == 1) {
			return typeManager.getPropertyDefinitionCoreForQueryName(queryName);
		} else {
			return typeManager
					.getPropertyDefinitionCoreForQueryName(_queryName[1]);
		}
	}

	private boolean isCommonProperty(PropertyDefinition<?> propertyDefinition) {
		String id = propertyDefinition.getId();

		return PropertyIds.NAME.equals(id) || PropertyIds.OBJECT_ID.equals(id)
				|| PropertyIds.OBJECT_TYPE_ID.equals(id)
				|| PropertyIds.BASE_TYPE_ID.equals(id)
				|| PropertyIds.CREATED_BY.equals(id)
				|| PropertyIds.CREATION_DATE.equals(id)
				|| PropertyIds.LAST_MODIFIED_BY.equals(id)
				|| PropertyIds.LAST_MODIFICATION_DATE.equals(id)
				|| PropertyIds.IS_IMMUTABLE.equals(id)
				|| PropertyIds.IS_PRIVATE_WORKING_COPY.equals(id)
				|| PropertyIds.IS_LATEST_VERSION.equals(id)
				|| PropertyIds.IS_MAJOR_VERSION.equals(id)
				|| PropertyIds.IS_LATEST_MAJOR_VERSION.equals(id)
				|| PropertyIds.VERSION_LABEL.equals(id)
				|| PropertyIds.VERSION_SERIES_ID.equals(id)
				|| PropertyIds.IS_VERSION_SERIES_CHECKED_OUT.equals(id)
				|| PropertyIds.VERSION_SERIES_CHECKED_OUT_BY.equals(id)
				|| PropertyIds.VERSION_SERIES_CHECKED_OUT_ID.equals(id)
				|| PropertyIds.CHECKIN_COMMENT.equals(id)
				|| PropertyIds.CONTENT_STREAM_LENGTH.equals(id)
				|| PropertyIds.CONTENT_STREAM_MIME_TYPE.equals(id)
				|| PropertyIds.CONTENT_STREAM_FILE_NAME.equals(id)
				|| PropertyIds.CONTENT_STREAM_ID.equals(id)
				|| PropertyIds.PARENT_ID.equals(id)
				|| PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS.equals(id)
				|| PropertyIds.PATH.equals(id);
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setRepositoryInfo(RepositoryInfo repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setPropertyManager(NemakiPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}