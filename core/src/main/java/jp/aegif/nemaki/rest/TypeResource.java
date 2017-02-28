package jp.aegif.nemaki.rest;

import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Choice;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@Path("/repo/{repositoryId}/type")
public class TypeResource extends ResourceBase {

	private TypeService typeService;
	private TypeManager typeManager;

	private final Log log = LogFactory.getLog(TypeResource.class);

	private final HashMap<String, NemakiTypeDefinition> typeMaps = new HashMap<String, NemakiTypeDefinition>();
	private final HashMap<String, NemakiPropertyDefinitionCore> coreMaps = new HashMap<String, NemakiPropertyDefinitionCore>();
	private final HashMap<String, NemakiPropertyDefinitionDetail> detailMaps = new HashMap<String, NemakiPropertyDefinitionDetail>();
	private final HashMap<String, List<String>> typeProperties = new HashMap<String, List<String>>();

	@POST
	@Path("/register")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public String register(@PathParam("repositoryId") String repositoryId, @FormDataParam("data") InputStream is) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			parse(repositoryId, is);
			create(repositoryId);
			typeManager.refreshTypes();

			status = true;
		} catch (Exception e) {
			log.warn("Type registrations fails", e);
			addErrMsg(errMsg, "types", "failsToRegister");
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private void parse(String repositoryId, InputStream is) throws DocumentException {
		SAXReader saxReader = new SAXReader();
		Document document = saxReader.read(is);
		Element model = document.getRootElement();

		// Types
		Element _types = getElement(model, "types");
		List<Element> types = getElements(_types, "type");
		parseTypes(repositoryId, types);

		// Aspects
		Element _aspects = getElement(model, "aspects");
		List<Element> aspects = getElements(_aspects, "aspect");
		parseTypes(repositoryId, aspects);
	}

	private void parseTypes(String repositoryId, List<Element> types) {
		for (Element type : types) {
			// Extract values
			// TODO "enabled"

			// ////
			// type
			// ////
			NemakiTypeDefinition tdf = new NemakiTypeDefinition();

			// typeId
			String typeId = getAttributeValue(type, "name");
			if (StringUtils.isEmpty(typeId)) {
				log.warn("typeId should be specified. SKIP.");
				continue;
			} else if (existType(repositoryId, typeId)) {
				log.warn(MessageFormat.format("typeId:{0} already exists in DB! SKIP.",typeId));
				continue;
			}
			tdf.setTypeId(typeId);
			tdf.setLocalName(typeId);

			// title
			String title = getElementValue(type, "title");
			if (StringUtils.isEmpty(title)) {
				log.warn(MessageFormat.format("typeId:{0} 'title' is nos specified. Default to typeId.",typeId));
			}
			tdf.setLocalNameSpace("");
			tdf.setDisplayName(title);
			tdf.setDescription(title);

			// parent and baseType
			String parent = getElementValue(type, "parent");
			if ("type".equals(type.getName())) {
				if (StringUtils.isEmpty(parent)) {
					log.warn(MessageFormat.format("typeId:{0} 'parent' should be specified. SKIP.",typeId));
					continue;
				}

				if ("cm:content".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_DOCUMENT);
					tdf.setParentId(BaseTypeId.CMIS_DOCUMENT.value());
				} else if ("cm:folder".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_FOLDER);
					tdf.setParentId(BaseTypeId.CMIS_FOLDER.value());
				} else if ("cm:relationship".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_RELATIONSHIP);
					tdf.setParentId(BaseTypeId.CMIS_RELATIONSHIP.value());
				}
			} else if ("aspect".equals(type.getName())) {
				tdf.setBaseId(BaseTypeId.CMIS_SECONDARY);
				if (StringUtils.isBlank(parent)) {
					tdf.setParentId(BaseTypeId.CMIS_SECONDARY.value());
				} else {
					tdf.setParentId(parent);
				}
			}

			// properties
			Element _properties = getElement(type, "properties");
			List<Element> properties = getElements(_properties, "property");
			if (CollectionUtils.isNotEmpty(properties)) {
				parseProperties(repositoryId, typeId, properties);
			}

			// Put to map
			typeMaps.put(typeId, tdf);
		}

	}

	private void parseProperties(String repositoryId, String typeId, List<Element> properties) {
		List<String> propertyIds = new ArrayList<String>();

		for (Element property : properties) {
			NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
			NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();

			// propertyId
			String propName = getAttributeValue(property, "name");
			// Check existing property definitions
			if (existProperty(repositoryId, propName)) {
				log.warn(MessageFormat.format("propertyId:{0} already exists in DB! SKIP.",propName));
				continue;
			}
			propertyIds.add(propName);

			// ////
			// core
			// ////
			// propertyId
			core.setPropertyId(propName);

			// queryName
			core.setQueryName(propName);

			// data type
			String dataType = getElementValue(property, "type");
			if ("d:text".equals(dataType) || "d:mltext".equals(dataType) || "d:content".equals(dataType)) {
				core.setPropertyType(PropertyType.STRING);
			} else if ("d:int".equals(dataType) || "d:long".equals(dataType)) {
				core.setPropertyType(PropertyType.INTEGER);
			} else if ("d:float".equals(dataType) || "d:double".equals(dataType)) {
				// FIXME is this mapping OK?
				core.setPropertyType(PropertyType.DECIMAL);
			} else if ("d:date".equals(dataType) || "d:datetime".equals(dataType)) {
				// TODO implement datePrecision
				core.setPropertyType(PropertyType.DATETIME);
			} else if ("d:boolean".equals(dataType)) {
				core.setPropertyType(PropertyType.BOOLEAN);
			} else if ("d:any".equals(dataType)) {
				log.info(buildMsg(typeId, propName, "'d:any data' types is not allowed. Defaults to STRING."));
				core.setPropertyType(PropertyType.STRING);
			} else {
				log.info(buildMsg(typeId, propName, "'Unknown data type. Defaults to STRING."));
				core.setPropertyType(PropertyType.STRING);
			}

			// cardinality
			String multiple = getElementValue(property, "multiple");
			if ("true".equals(multiple)) {
				core.setCardinality(Cardinality.MULTI);
			} else {
				if (StringUtils.isBlank(multiple)) {
					log.info(buildMsg(typeId, propName, "'multiple' is not specified. Default to false"));
				}
				core.setCardinality(Cardinality.SINGLE);
			}

			coreMaps.put(propName, core);

			// //////
			// detail
			// //////
			detail.setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());

			// defaultValue
			String defaultValue = getElementValue(property, "default");
			// TODO multiple default values are allowed?
			if (!StringUtils.isBlank(defaultValue)) {
				List<Object> defaults = new ArrayList<Object>();
				defaults.add(defaultValue);
				detail.setDefaultValue(defaults);
			} // if defaultValue not set, it should be null for WSConverter

			// constraints
			Element _constraints = getElement(property, "constraints");
			setConstraints(detail, _constraints);

			// updatability
			detail.setUpdatability(Updatability.READWRITE);

			// required
			if (existElement(property, "mandatory")) {
				detail.setRequired(true);
			} else {
				log.info(buildMsg(typeId, propName, "'mandatory' is not specified. Default to false"));
				detail.setRequired(false);
			}

			// queryable
			Element index = getElement(property, "index");
			String _indexEnabled = getAttributeValue(index, "enabled");

			boolean indexEnabled = ("true".equals(_indexEnabled)) ? true : false;

			if (indexEnabled) {
				detail.setQueryable(true);
			} else {
				log.info(buildMsg(typeId, propName, "'index' is not specified. Default to false"));

				detail.setQueryable(false);
			}

			// FIXME openChoice is default to false?
			detail.setOpenChoice(false);

			detailMaps.put(propName, detail);
		}

		typeProperties.put(typeId, propertyIds);
	}

	private void setConstraints(NemakiPropertyDefinitionDetail detail, Element _constraints) {
		List<Element> constraints = getElements(_constraints, "constraint");
		for (Element constraint : constraints) {
			String type = getAttributeValue(constraint, "type");
			if (type != null) {
				if ("LENGTH".equals(type)) {
					String minLength = getElementValue(constraint, "minLength");
					String maxLength = getElementValue(constraint, "maxLength");
					if (StringUtils.isNotBlank(maxLength)) {
						detail.setMaxLength(Long.valueOf(maxLength));
					}
				} else if ("MINMAX".equals(type)) {
					String minValue = getElementValue(constraint, "minValue");
					if (StringUtils.isNotBlank(minValue)) {
						detail.setMaxValue(Long.valueOf(minValue));
					}
					String maxValue = getElementValue(constraint, "maxValue");
					if (StringUtils.isNotBlank(maxValue)) {
						detail.setMaxValue(Long.valueOf(maxValue));
					}
				} else if ("LIST".equals(type)) {
					Element _allowed = getElement(constraint, "parameter");
					if (_allowed != null) {
						Element list = getElement(_allowed, "list");
						List<String> values = getElementsValues(_allowed, "value");

						Choice choice = new Choice();
						List<Object> _values = new ArrayList<Object>();
						for (String s : values) {
							_values.add(s);
						}
						choice.setValue(_values);
						List<Choice> choices = new ArrayList<Choice>();
						detail.setChoices(choices);
					}
				}
			}
		}
	}

	private void create(String repositoryId) {
		// First, create properties
		for (Entry<String, NemakiPropertyDefinitionCore> coreEntry : coreMaps.entrySet()) {
			NemakiPropertyDefinition p = new NemakiPropertyDefinition(coreEntry.getValue(),
					detailMaps.get(coreEntry.getKey()));
			typeService.createPropertyDefinition(repositoryId, p);
			NemakiPropertyDefinitionCore createdCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
					p.getPropertyId());

			coreEntry.getValue().setId(createdCore.getId());
		}

		// Prepare types
		for (Entry<String, NemakiTypeDefinition> typeEntry : typeMaps.entrySet()) {
			NemakiTypeDefinition t = typeEntry.getValue();

			// TODO Set property detail ids
			List<String> propertyNodeIds = new ArrayList<String>();
			List<String> propertyIds = typeProperties.get(t.getTypeId());
			if (CollectionUtils.isNotEmpty(propertyIds)) {
				for (String propertyId : typeProperties.get(t.getTypeId())) {
					NemakiPropertyDefinitionCore core = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
							propertyId);
					// propertyNodeIds.add(core.getId());
					List<NemakiPropertyDefinitionDetail> details = typeService
							.getPropertyDefinitionDetailByCoreNodeId(repositoryId, core.getId());
					if (CollectionUtils.isEmpty(details)) {
						log.warn(buildMsg(t.getTypeId(), propertyId,
								"Skipped to add this property because of incorrect data in DB."));
					} else {
						// Presuppose there is no multiple detail for each core
						NemakiPropertyDefinitionDetail detail = details.get(0);
						propertyNodeIds.add(detail.getId());
					}
				}
				t.setProperties(propertyNodeIds);
			}

			// Remove orphan types
			if (typeMaps.get(t.getParentId()) == null && !isBaseType(t.getParentId())) {
				log.warn(buildMsg(t.getId(), null,
						"Skipped to create this type because it has an unknown parent type."));
			} else {
				typeService.createTypeDefinition(repositoryId, t);
			}
		}
	}

	private Element getElement(Element parent, String name) {
		Element result = null;

		if (existElement(parent, name)) {
			for (Iterator<Element> iterator = parent.elementIterator(name); iterator.hasNext();) {
				result = iterator.next();
			}
			return result;
		} else {
			log.info("Cannot parse " + "'" + name + "'.");
			return result;
		}
	}

	private List<Element> getElements(Element parent, String name) {
		List<Element> results = new ArrayList<Element>();

		if (existElement(parent, name)) {
			for (Iterator<Element> iterator = parent.elementIterator(name); iterator.hasNext();) {
				results.add(iterator.next());
			}
			return results;
		} else {
			log.info(MessageFormat.format("Cannot parse '{0}'.",name));
			return results;
		}
	}

	private String getElementValue(Element parent, String name) {
		Element elm = getElement(parent, name);
		if (elm != null) {
			return elm.getStringValue();
		} else {
			log.info(MessageFormat.format("Cannot parse '{0}'.",name));
			return null;
		}
	}

	private List<String> getElementsValues(Element parent, String name) {
		List<Element> elements = getElements(parent, name);

		List<String> result = new ArrayList<String>();
		for (Element element : elements) {
			result.add(element.getStringValue());
		}
		return result;
	}

	private String getAttributeValue(Element element, String name) {
		if (existAttribute(element, name)) {
			Attribute attr = element.attribute(name);
			return attr.getStringValue();
		} else {
			return null;
		}
	}

	private boolean isBaseType(String typeId) {
		if (BaseTypeId.CMIS_DOCUMENT.value().equals(typeId) || BaseTypeId.CMIS_FOLDER.value().equals(typeId)
				|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(typeId) || BaseTypeId.CMIS_POLICY.value().equals(typeId)
				|| BaseTypeId.CMIS_ITEM.value().equals(typeId) || BaseTypeId.CMIS_SECONDARY.value().equals(typeId)) {
			return true;
		} else {
			return false;
		}
	}

	private boolean existType(String repositoryId, String typeId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, typeId);
		if (existing == null) {
			return false;
		} else {
			return true;
		}
	}

	private boolean existProperty(String repositoryId, String propertyId) {
		NemakiPropertyDefinitionCore existing = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
				propertyId);
		if (existing == null) {
			return false;
		} else {
			return true;
		}
	}

	private boolean existElement(Element parent, String name) {
		try {
			parent.element(name);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private boolean existAttribute(Element element, String name) {
		try {
			element.attribute(name);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private String buildMsg(String typeId, String propertyId, String msg) {
		List<String> header = new ArrayList<String>();

		String _typeId = "";
		if (StringUtils.isNotBlank(typeId)) {
			_typeId = "typeId=" + typeId;
			header.add(_typeId);
		}

		String _proeprtyId = "";
		if (StringUtils.isNotBlank(propertyId)) {
			_proeprtyId = "propertyId=" + propertyId;
			header.add(_proeprtyId);
		}

		String _header = StringUtils.join(header, ",");
		_header = "[" + _header + "]";

		return _header + msg;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}
}
