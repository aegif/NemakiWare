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
package org.apache.chemistry.opencmis.inmemory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.inmemory.types.DocumentTypeCreationHelper;
import org.apache.chemistry.opencmis.inmemory.types.TypeUtil;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that manages a type system for a repository types can be added, the
 * inheritance can be managed and type can be retrieved for a given type id.
 */
public class TypeManagerImpl implements TypeManager {

    private static final Logger LOG = LoggerFactory.getLogger(TypeManagerImpl.class.getName());
    /**
     * map from repository id to a types map.
     */
    private final Map<String, TypeDefinitionContainer> fTypesMap = new HashMap<String, TypeDefinitionContainer>();

    @Override
    public TypeDefinitionContainer getTypeById(String typeId) {
        return fTypesMap.get(typeId);
    }

    @Override
    public TypeDefinition getTypeByQueryName(String typeQueryName) {
        for (Entry<String, TypeDefinitionContainer> entry : fTypesMap.entrySet()) {
            if (entry.getValue().getTypeDefinition().getQueryName().equals(typeQueryName)) {
                return entry.getValue().getTypeDefinition();
            }
        }
        return null;
    }

    @Override
    public Collection<TypeDefinitionContainer> getTypeDefinitionList() {
        return Collections.unmodifiableCollection(fTypesMap.values());
    }

    @Override
    public List<TypeDefinitionContainer> getRootTypes() {
        // just take first repository
        List<TypeDefinitionContainer> rootTypes = new ArrayList<TypeDefinitionContainer>();

        for (TypeDefinitionContainer type : fTypesMap.values()) {
            if (isRootType(type)) {
                rootTypes.add(type);
            }
        }

        return rootTypes;
    }

    /**
     * Initialize the type system with the given types. This list must not
     * contain the CMIS default types. The default type are always contained by
     * default.
     * 
     * @param typesList
     *            list of types to add to the repository
     * 
     * @param createCmisDefaultTypes
     *            indicates if CMIS base types should be added to list
     */
    public void initTypeSystem(List<TypeDefinition> typesList, boolean createCmisDefaultTypes) {

        if (createCmisDefaultTypes) {
            createCmisDefaultTypes();
        }

        // merge all types from the list and build the correct hierachy with
        // children and property lists
        if (null != typesList) {
            for (TypeDefinition typeDef : typesList) {
                addTypeDefinition(typeDef, true);
            }
        }

    }

    @Override
    public void addTypeDefinition(TypeDefinition cmisType, boolean addInheritedProperties) {

        LOG.info("Adding type definition with name " + cmisType.getLocalName() + " and id " + cmisType.getId()
                + " to repository.");
        TypeDefinitionContainerImpl typeContainer = new TypeDefinitionContainerImpl(cmisType);

        if (null != cmisType.getParentTypeId()) {
            // add new type to children of parent types
            TypeDefinitionContainer parentTypeContainer = fTypesMap.get(cmisType.getParentTypeId());
            parentTypeContainer.getChildren().add(typeContainer);

            if (addInheritedProperties) {
                // recursively add inherited properties
                Map<String, PropertyDefinition<?>> propDefs = typeContainer.getTypeDefinition()
                        .getPropertyDefinitions();
                addInheritedProperties(propDefs, parentTypeContainer.getTypeDefinition());
            }
        }
        // add type to type map
        fTypesMap.put(cmisType.getId(), typeContainer);
    }

    @Override
    public void updateTypeDefinition(TypeDefinition typeDefinition) {
        throw new CmisNotSupportedException("updating a type definition is not supported.");
    }

    @Override
    public void deleteTypeDefinition(String typeId) {
        TypeDefinitionContainer typeDef = fTypesMap.remove(typeId);
        // remove type from children of parent types
        TypeDefinitionContainer parentTypeContainer = fTypesMap.get(typeDef.getTypeDefinition().getParentTypeId());
        parentTypeContainer.getChildren().remove(typeDef);
        fTypesMap.remove(typeId);
    }

    /**
     * Removes all types from the type system. After this call only the default
     * CMIS types are present in the type system. Use this method with care, its
     * mainly intended for unit tests.
     */
    public void clearTypeSystem() {
        fTypesMap.clear();
        createCmisDefaultTypes();
    }

    @Override
    public String getPropertyIdForQueryName(TypeDefinition typeDefinition, String propQueryName) {
        for (PropertyDefinition<?> pd : typeDefinition.getPropertyDefinitions().values()) {
            if (pd.getQueryName().equals(propQueryName)) {
                return pd.getId();
            }
        }
        return null;
    }

    private void addInheritedProperties(Map<String, PropertyDefinition<?>> propDefs, TypeDefinition typeDefinition) {

        if (null == typeDefinition) {
            return;
        }

        if (null != typeDefinition.getPropertyDefinitions()) {
            addInheritedPropertyDefinitions(propDefs, typeDefinition.getPropertyDefinitions());
        }

        TypeDefinitionContainer parentTypeContainer = fTypesMap.get(typeDefinition.getParentTypeId());
        TypeDefinition parentType = (null == parentTypeContainer ? null : parentTypeContainer.getTypeDefinition());
        addInheritedProperties(propDefs, parentType);
    }

    private static void addInheritedPropertyDefinitions(Map<String, PropertyDefinition<?>> propDefs,
            Map<String, PropertyDefinition<?>> superPropDefs) {

        for (Entry<String, PropertyDefinition<?>> superProp : superPropDefs.entrySet()) {
            PropertyDefinition<?> superPropDef = superProp.getValue();
            PropertyDefinition<?> clone = clonePropertyDefinition(superPropDef);
            ((AbstractPropertyDefinition<?>) clone).setIsInherited(true);
            propDefs.put(superProp.getKey(), clone);
        }
    }

    private void createCmisDefaultTypes() {
        List<TypeDefinition> typesList = DocumentTypeCreationHelper.createDefaultTypes();
        for (TypeDefinition typeDef : typesList) {
            TypeDefinitionContainerImpl typeContainer = new TypeDefinitionContainerImpl(typeDef);
            fTypesMap.put(typeDef.getId(), typeContainer);
        }
    }

    private static boolean isRootType(TypeDefinitionContainer c) {
        return c.getTypeDefinition().getId().equals(c.getTypeDefinition().getBaseTypeId().value());
    }

    private static PropertyDefinition<?> clonePropertyDefinition(PropertyDefinition<?> src) {
        PropertyDefinition<?> clone = TypeUtil.clonePropertyDefinition(src);
        return clone;
    }

}
