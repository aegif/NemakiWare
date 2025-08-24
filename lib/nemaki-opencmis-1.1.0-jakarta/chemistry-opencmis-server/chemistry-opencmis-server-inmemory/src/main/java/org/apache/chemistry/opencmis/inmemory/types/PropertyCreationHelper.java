/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.inmemory.types;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BindingsObjectFactoryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
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
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.DataObjectCreator;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.NameValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PropertyCreationHelper {

    private static final Logger LOG = LoggerFactory.getLogger(PropertyCreationHelper.class);

    private PropertyCreationHelper() {
    }

    public static PropertyBooleanDefinitionImpl createBooleanDefinition(String id, String displayName, 
            Updatability upd) {
        PropertyBooleanDefinitionImpl prop = new PropertyBooleanDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.BOOLEAN, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyBooleanDefinitionImpl createBooleanMultiDefinition(String id, String displayName,
            Updatability upd) {
        PropertyBooleanDefinitionImpl prop = new PropertyBooleanDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.BOOLEAN, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyDateTimeDefinitionImpl createDateTimeDefinition(String id, String displayName,
            Updatability upd) {
        PropertyDateTimeDefinitionImpl prop = new PropertyDateTimeDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.DATETIME, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyDateTimeDefinitionImpl createDateTimeMultiDefinition(String id, String displayName,
            Updatability upd) {
        PropertyDateTimeDefinitionImpl prop = new PropertyDateTimeDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.DATETIME, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyDecimalDefinitionImpl createDecimalDefinition(String id, String displayName, 
            Updatability upd) {
        PropertyDecimalDefinitionImpl prop = new PropertyDecimalDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.DECIMAL, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyDecimalDefinitionImpl createDecimalMultiDefinition(String id, String displayName,
            Updatability upd) {
        PropertyDecimalDefinitionImpl prop = new PropertyDecimalDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.DECIMAL, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyHtmlDefinitionImpl createHtmlDefinition(String id, String displayName, Updatability upd) {
        PropertyHtmlDefinitionImpl prop = new PropertyHtmlDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.HTML, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyHtmlDefinitionImpl createHtmlMultiDefinition(String id, String displayName, 
            Updatability upd) {
        PropertyHtmlDefinitionImpl prop = new PropertyHtmlDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.HTML, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyIdDefinitionImpl createIdDefinition(String id, String displayName, Updatability upd) {
        PropertyIdDefinitionImpl prop = new PropertyIdDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.ID, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyIdDefinitionImpl createIdMultiDefinition(String id, String displayName, Updatability upd) {
        PropertyIdDefinitionImpl prop = new PropertyIdDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.ID, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyIntegerDefinitionImpl createIntegerDefinition(String id, String displayName, 
            Updatability upd) {
        PropertyIntegerDefinitionImpl prop = new PropertyIntegerDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.INTEGER, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyIntegerDefinitionImpl createIntegerMultiDefinition(String id, String displayName,
            Updatability upd) {
        PropertyIntegerDefinitionImpl prop = new PropertyIntegerDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.INTEGER, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyStringDefinitionImpl createStringDefinition(String id, String displayName, Updatability upd) {
        PropertyStringDefinitionImpl prop = new PropertyStringDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.STRING, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyStringDefinitionImpl createStringMultiDefinition(String id, String displayName,
            Updatability upd) {
        PropertyStringDefinitionImpl prop = new PropertyStringDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.STRING, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static PropertyUriDefinitionImpl createUriDefinition(String id, String displayName, Updatability upd) {
        PropertyUriDefinitionImpl prop = new PropertyUriDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.URI, displayName, Cardinality.SINGLE, upd);
        return prop;
    }

    public static PropertyUriDefinitionImpl createUriMultiDefinition(String id, String displayName, Updatability upd) {
        PropertyUriDefinitionImpl prop = new PropertyUriDefinitionImpl();
        createStandardDefinition(prop, id, PropertyType.URI, displayName, Cardinality.MULTI, upd);
        return prop;
    }

    public static <T> void addElemToPicklist(AbstractPropertyDefinition<T> prop, T value) {
        List<Choice<T>> choiceList = prop.getChoices();
        if (choiceList == null) {
            choiceList = new ArrayList<Choice<T>>();
            prop.setChoices(choiceList);
        }

        ChoiceImpl<T> elem = new ChoiceImpl<T>();
        elem.setValue(Collections.singletonList(value));
        choiceList.add(elem);
    }

    public static <T> void setDefaultValue(AbstractPropertyDefinition<T> prop, T defVal) {
        prop.setDefaultValue(Collections.singletonList(defVal));
    }

    public static Properties getPropertiesFromObject(StoredObject so, ObjectStore objectStore, TypeManager typeManager,
            List<String> requestedIds, boolean fillOptionalPropertyData) {
        // build properties collection

        BindingsObjectFactory objectFactory = new BindingsObjectFactoryImpl();
        Map<String, PropertyData<?>> properties = new HashMap<String, PropertyData<?>>();
        so.fillProperties(properties, objectFactory, requestedIds);
        TypeDefinition td = typeManager.getTypeById(so.getTypeId()).getTypeDefinition();

        String typeId = so.getTypeId();
        if (so instanceof Folder && FilterParser.isContainedInFilter(PropertyIds.PATH, requestedIds)) {
            String path = objectStore.getFolderPath(so.getId());
            properties.put(PropertyIds.PATH, objectFactory.createPropertyStringData(PropertyIds.PATH, path));
        }
        if (FilterParser.isContainedInFilter(PropertyIds.BASE_TYPE_ID, requestedIds)) {
            if (td == null) {
                LOG.warn("getPropertiesFromObject(), cannot get type definition, a type with id " + typeId
                        + " is unknown");
                return null;
            } else {
                String baseTypeId = td.getBaseTypeId().value();
                properties.put(PropertyIds.BASE_TYPE_ID,
                        objectFactory.createPropertyIdData(PropertyIds.BASE_TYPE_ID, baseTypeId));
            }
        }

        // fill not-set properties from type definition (as spec requires)
        Map<String, PropertyDefinition<?>> propDefs = td.getPropertyDefinitions();
        for (PropertyDefinition<?> propDef : propDefs.values()) {
            if (!properties.containsKey(propDef.getId())
                    && FilterParser.isContainedInFilter(propDef.getId(), requestedIds)) {
                properties.put(propDef.getId(), getEmptyValue(propDef));
            }
        }

        // fill not-set properties from secondary types
        List<String> secTypeIds = so.getSecondaryTypeIds();
        for (String secTypeId : secTypeIds) {
            td = typeManager.getTypeById(secTypeId).getTypeDefinition();
            propDefs = td.getPropertyDefinitions();
            for (PropertyDefinition<?> propDef : propDefs.values()) {
                if (!properties.containsKey(propDef.getId())
                        && FilterParser.isContainedInFilter(propDef.getId(), requestedIds)) {
                    properties.put(propDef.getId(), getEmptyValue(propDef));
                }
            }
        }

        List<PropertyData<?>> propertiesList = new ArrayList<PropertyData<?>>(properties.values());

        // add query name, local name, display name
        if (fillOptionalPropertyData) {
            fillOptionalPropertyData(td, propertiesList);
        }

        Properties props = objectFactory.createPropertiesData(propertiesList);
        return props;
    }

    public static Properties getPropertiesFromObject(TypeManager tm, StoredObject so, ObjectStore objectStore,
            TypeDefinition primaryType, List<TypeDefinition> secondaryTypes, Map<String, String> requestedIds,
            Map<String, String> requestedFuncs) {
        // build properties collection

        List<String> idList = new ArrayList<String>(requestedIds.values());
        BindingsObjectFactory objectFactory = new BindingsObjectFactoryImpl();
        Map<String, PropertyData<?>> properties = new HashMap<String, PropertyData<?>>();
        so.fillProperties(properties, objectFactory, idList);
        // special handling for path
        if (so instanceof Folder && FilterParser.isContainedInFilter(PropertyIds.PATH, idList)) {
            String path = objectStore.getFolderPath(so.getId());
            properties.put(PropertyIds.PATH, objectFactory.createPropertyStringData(PropertyIds.PATH, path));
        }

        if (FilterParser.isContainedInFilter(PropertyIds.BASE_TYPE_ID, idList)) {
            String baseTypeId = primaryType.getBaseTypeId().value();
            properties.put(PropertyIds.BASE_TYPE_ID,
                    objectFactory.createPropertyIdData(PropertyIds.BASE_TYPE_ID, baseTypeId));
        }

        Map<String, PropertyData<?>> mappedProperties = new HashMap<String, PropertyData<?>>();

        // primary type:
        if (requestedIds.containsValue("*")) {
            for (Map.Entry<String, PropertyData<?>> prop : properties.entrySet()) {
                addPropertyToMap(mappedProperties, primaryType, prop.getValue(), null);
            }
            // add all values that are not set:
            Map<String, PropertyDefinition<?>> propDefs = primaryType.getPropertyDefinitions();
            for (PropertyDefinition<?> propDef : propDefs.values()) {
                if (!mappedProperties.containsKey(propDef.getQueryName())) {
                    mappedProperties.put(propDef.getId(), getEmptyValue(propDef));
                }
            }

        } else {
            // replace all ids with query names or alias:
            for (Entry<String, String> propAlias : requestedIds.entrySet()) {
                String queryNameOrAlias = propAlias.getKey();
                PropertyData<?> prop = properties.get(propAlias.getValue());
                if (null != prop) {
                    addPropertyToMap(mappedProperties, primaryType, prop, queryNameOrAlias);
                } else {
                    addNotSetPropertyToMap(mappedProperties, primaryType, propAlias.getValue(), queryNameOrAlias);
                }
            }
        }

        // secondary types:
        if (null != secondaryTypes) {
            for (TypeDefinition typeDef : secondaryTypes) {
                if (requestedIds.containsValue("*")) {
                    for (Map.Entry<String, PropertyData<?>> prop : properties.entrySet()) {
                        addPropertyToMap(mappedProperties, typeDef, prop.getValue(), null);
                    }
                    // add all values that are not set:
                    Map<String, PropertyDefinition<?>> propDefs = typeDef.getPropertyDefinitions();
                    for (PropertyDefinition<?> propDef : propDefs.values()) {
                        if (!mappedProperties.containsKey(propDef.getQueryName())) {
                            mappedProperties.put(propDef.getId(), getEmptyValue(propDef));
                        }
                    }
                } else {
                    // replace all ids with query names or alias:
                    for (Entry<String, String> propAlias : requestedIds.entrySet()) {
                        String queryNameOrAlias = propAlias.getKey();
                        PropertyData<?> prop = properties.get(propAlias.getValue());
                        if (null != prop) {
                            addPropertyToMap(mappedProperties, typeDef, prop, queryNameOrAlias);
                        } else {
                            addNotSetPropertyToMap(mappedProperties, typeDef, propAlias.getValue(), queryNameOrAlias);
                        }
                    }
                }
            }
        }
        
        // add secondary properties that are not part of a JOIN in a query, e.g.
        // SELECT cmis:name, mySecondaryProp FROM cmis:document ...
        // we do not have an exact type definition in this case mySecondaryProp can be part of multiple
        // type definitions, take the first one found.
        if (!requestedIds.containsValue("*")) {
			for (Map.Entry<String, String> prop : requestedIds.entrySet()) {
				if (!mappedProperties.containsKey(prop.getValue())) {
					PropertyData<?> pd = properties.get(prop.getValue());
					TypeDefinition typeDef = findFirstTypeDefHavingProperty(tm, so, pd);
					addPropertyToMap(mappedProperties, typeDef, pd, prop.getKey());
				}
			}
        }

        // add functions:
        for (Entry<String, String> funcEntry : requestedFuncs.entrySet()) {
            String queryName;
            if (funcEntry.getValue().equals("SCORE")) {
                queryName = "SEARCH_SCORE";
                if (!funcEntry.getKey().equals("SCORE")) {
                    queryName = funcEntry.getKey();
                }

                PropertyDecimalImpl pd = new PropertyDecimalImpl();
                // fixed dummy value
                pd.setValue(BigDecimal.valueOf(1.0));
                pd.setId(queryName);
                pd.setQueryName(queryName);
                pd.setLocalName("SCORE");
                pd.setDisplayName("Score");
                mappedProperties.put(funcEntry.getKey(), pd);
            }
        }

        Properties props = new PropertiesImpl(mappedProperties.values());
        return props;
    }

    private static TypeDefinition findFirstTypeDefHavingProperty(TypeManager tm, StoredObject so, PropertyData<?> pd) {
    	List<String> typeIds = so.getSecondaryTypeIds();
    	for (String typeId : typeIds) {
    		TypeDefinition typeDef = tm.getTypeById(typeId).getTypeDefinition();
    		Map<String, PropertyDefinition<?>> propMap = typeDef.getPropertyDefinitions();
    		if (propMap.containsKey(pd.getId())) {
    			return typeDef;
    		}
    	}
		return null;
	}

	private static void addNotSetPropertyToMap(Map<String, PropertyData<?>> mappedProperties, TypeDefinition typeDef,
            String propId, String queryNameOrAlias) {
        PropertyDefinition<?> propDef = typeDef.getPropertyDefinitions().get(propId);
        if (null != propDef) {
            AbstractPropertyData<?> ad = getEmptyValue(propDef);
            String localName = propDef.getLocalName();
            String displayName = propDef.getDisplayName();
            ad.setQueryName(queryNameOrAlias);
            ad.setLocalName(localName);
            ad.setDisplayName(displayName);
            mappedProperties.put(queryNameOrAlias, ad);
        }
    }

    private static void addPropertyToMap(Map<String, PropertyData<?>> mappedProperties, TypeDefinition typeDef,
            PropertyData<?> propData, String queryNameOrAlias) {
        String propId = propData.getId();
        if (typeDef.getPropertyDefinitions().containsKey(propId)) {
            // map property id to property query name
            PropertyDefinition<?> propDef = typeDef.getPropertyDefinitions().get(propId);
            String queryName = propDef.getQueryName();
            String localName = propDef.getLocalName();
            String displayName = propDef.getDisplayName();

            AbstractPropertyData<?> ad = clonePropertyData(propData);
            ad.setQueryName(queryNameOrAlias == null ? queryName : queryNameOrAlias);
            ad.setLocalName(localName);
            ad.setDisplayName(displayName);

            mappedProperties.put(queryName, ad);
        }
    }

    public static ObjectData getObjectData(CallContext context, TypeManager tm, ObjectStore objectStore, StoredObject so, String filter,
            String user, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeACL, ExtensionsData extension) {

        ObjectDataImpl od = new ObjectDataImpl();

        if (so == null) {
            throw new CmisObjectNotFoundException("Illegal object id: null");
        }

        // build properties collection
        List<String> requestedIds = FilterParser.getRequestedIdsFromFilter(filter);
        Properties props = getPropertiesFromObject(so, objectStore, tm, requestedIds, true);

        // fill output object
        if (null != includeAllowableActions && includeAllowableActions) {
            AllowableActions allowableActions = so.getAllowableActions(context, user);
            od.setAllowableActions(allowableActions);
        }

        List<RenditionData> renditions = objectStore.getRenditions(so, renditionFilter, 0, 0);
        if (null != renditions && renditions.size() > 0) {
            od.setRenditions(renditions);
        }

        if (null != includeACL && includeACL) {
            int aclId = so instanceof DocumentVersion ? ((DocumentVersion) so).getParentDocument().getAclId() : so
                    .getAclId();
            Acl acl = objectStore.getAcl(aclId);
            od.setAcl(acl);
        }
        od.setIsExactAcl(true);

        if (null != includePolicyIds && includePolicyIds) {
            od.setPolicyIds(DataObjectCreator.fillPolicyIds(so));
        }

        if (null != includeRelationships && includeRelationships != IncludeRelationships.NONE) {
            od.setRelationships(DataObjectCreator.fillRelationships(context, tm, objectStore, includeRelationships, so, user));
        }

        if (null != includePolicyIds && includePolicyIds) {
            List<String> polIds = so.getAppliedPolicies();
            if (null != polIds) {
                PolicyIdListImpl policies = new PolicyIdListImpl();
                policies.setPolicyIds(polIds);
                od.setPolicyIds(policies);
            }
        }
        od.setProperties(props);

        // Note: do not set change event info for this call
        return od;
    }

    public static ObjectData getObjectDataQueryResult(CallContext context, TypeManager tm, ObjectStore objectStore,
            TypeDefinition primaryType, StoredObject so, String user, Map<String, String> requestedProperties,
            Map<String, String> requestedFuncs, List<TypeDefinition> secondaryTypes, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter) {

        ObjectDataImpl od = new ObjectDataImpl();

        // build properties collection
        Properties props = getPropertiesFromObject(tm, so, objectStore, primaryType, secondaryTypes, requestedProperties,
                requestedFuncs);

        // fill output object
        if (null != includeAllowableActions && includeAllowableActions) {
            AllowableActions allowableActions = so.getAllowableActions(context, user);
            od.setAllowableActions(allowableActions);
        }

        int aclId = so.getAclId();
        Acl acl = objectStore.getAcl(aclId);
        od.setAcl(acl);

        od.setIsExactAcl(true);

        if (null != includeRelationships && includeRelationships != IncludeRelationships.NONE) {
            od.setRelationships(DataObjectCreator.fillRelationships(context, tm, objectStore, includeRelationships, so, user));
        }

        List<RenditionData> renditions = objectStore.getRenditions(so, renditionFilter, 0, 0);
        if (null != renditions && renditions.size() > 0) {
            od.setRenditions(renditions);
        }

        od.setProperties(props);

        return od;
    }

    // internal helpers
    private static void createStandardDefinition(AbstractPropertyDefinition<?> prop, String id, PropertyType propType,
            String displayName, Cardinality card, Updatability upd) {

        if (!NameValidator.isValidId(id)) {
            throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME);
        }

        prop.setId(id);
        if (displayName == null) {
            prop.setDisplayName("Sample " + prop.getId() + " boolean property");
        } else {
            prop.setDisplayName(displayName);
        }
        prop.setDescription("This is a " + prop.getDisplayName() + " property.");
        prop.setLocalName(id);
        prop.setLocalNamespace(null);
        prop.setQueryName(id);
        prop.setIsInherited(false);
        prop.setCardinality(card);
        prop.setIsOpenChoice(false);
        prop.setIsQueryable(true);
        prop.setIsRequired(false);
        prop.setIsOrderable(card == Cardinality.SINGLE ? true : false);
        prop.setPropertyType(propType);
        prop.setUpdatability(upd);
    }

    private static void fillOptionalPropertyData(TypeDefinition td, List<PropertyData<?>> properties) {
        for (PropertyData<?> pd : properties) {
            fillOptionalPropertyData(td, (AbstractPropertyData<?>) pd);
        }
    }

    private static void fillOptionalPropertyData(TypeDefinition td, AbstractPropertyData<?> property) {
        PropertyDefinition<?> pd = td.getPropertyDefinitions().get(property.getId());
        if (null != pd) {
            String displayName = pd.getDisplayName();
            String queryName = pd.getQueryName();
            String localName = pd.getLocalName();
            property.setDisplayName(displayName);
            property.setLocalName(localName);
            property.setQueryName(queryName);
        }
    }

    private static AbstractPropertyData<?> getEmptyValue(PropertyDefinition<?> propDef) {
        AbstractPropertyData<?> emptyValue;
        if (propDef.getPropertyType().equals(PropertyType.BOOLEAN)) {
            emptyValue = new PropertyBooleanImpl(propDef.getId(), (Boolean) null);
        } else if (propDef.getPropertyType().equals(PropertyType.DATETIME)) {
            emptyValue = new PropertyDateTimeImpl(propDef.getId(), (GregorianCalendar) null);
        } else if (propDef.getPropertyType().equals(PropertyType.DECIMAL)) {
            emptyValue = new PropertyDecimalImpl(propDef.getId(), (BigDecimal) null);
        } else if (propDef.getPropertyType().equals(PropertyType.HTML)) {
            emptyValue = new PropertyHtmlImpl(propDef.getId(), (String) null);
        } else if (propDef.getPropertyType().equals(PropertyType.ID)) {
            emptyValue = new PropertyIdImpl(propDef.getId(), (String) null);
        } else if (propDef.getPropertyType().equals(PropertyType.INTEGER)) {
            emptyValue = new PropertyIntegerImpl(propDef.getId(), (BigInteger) null);
        } else if (propDef.getPropertyType().equals(PropertyType.STRING)) {
            emptyValue = new PropertyStringImpl(propDef.getId(), (String) null);
        } else if (propDef.getPropertyType().equals(PropertyType.URI)) {
            emptyValue = new PropertyUriImpl(propDef.getId(), (String) null);
        } else {
            throw new CmisRuntimeException("Unknown property type " + propDef.getPropertyType());
        }
        emptyValue.setDisplayName(propDef.getDisplayName());
        emptyValue.setQueryName(propDef.getQueryName());
        emptyValue.setLocalName(propDef.getLocalName());
        return emptyValue;
    }

    private static AbstractPropertyData<?> clonePropertyData(PropertyData<?> prop) {
        AbstractPropertyData<?> ad = null;

        if (prop instanceof PropertyBooleanImpl) {
            PropertyBooleanImpl clone = new PropertyBooleanImpl();
            clone.setValues(((PropertyBooleanImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyDateTimeImpl) {
            PropertyDateTimeImpl clone = new PropertyDateTimeImpl();
            clone.setValues(((PropertyDateTimeImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyDecimalImpl) {
            PropertyDecimalImpl clone = new PropertyDecimalImpl();
            clone.setValues(((PropertyDecimalImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyHtmlImpl) {
            PropertyHtmlImpl clone = new PropertyHtmlImpl();
            clone.setValues(((PropertyHtmlImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyIdImpl) {
            PropertyIdImpl clone = new PropertyIdImpl();
            clone.setValues(((PropertyIdImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyIntegerImpl) {
            PropertyIntegerImpl clone = new PropertyIntegerImpl();
            clone.setValues(((PropertyIntegerImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyStringImpl) {
            PropertyStringImpl clone = new PropertyStringImpl();
            clone.setValues(((PropertyStringImpl) prop).getValues());
            ad = clone;
        } else if (prop instanceof PropertyUriImpl) {
            PropertyUriImpl clone = new PropertyUriImpl();
            clone.setValues(((PropertyUriImpl) prop).getValues());
            ad = clone;
        } else {
            throw new CmisRuntimeException("Unknown property type: " + prop.getClass());
        }

        ad.setDisplayName(prop.getDisplayName());
        ad.setId(prop.getId());
        ad.setLocalName(prop.getLocalName());
        ad.setQueryName(prop.getQueryName());
        ad.setExtensions(prop.getExtensions());

        return ad;
    }
}
