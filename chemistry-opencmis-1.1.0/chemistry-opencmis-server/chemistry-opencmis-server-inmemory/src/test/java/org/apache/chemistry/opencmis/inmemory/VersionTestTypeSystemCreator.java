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

import java.util.LinkedList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.MutableDocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.inmemory.types.DocumentTypeCreationHelper;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeDefinitionFactory;

public class VersionTestTypeSystemCreator implements TypeCreator {
    public static final String VERSION_TEST_DOCUMENT_TYPE_ID = "MyVersionedType";
    public static final String PROPERTY_ID = "StringProp";
    public static final List<TypeDefinition> singletonTypes = buildTypesList();

    /**
     * in the public interface of this class we return the singleton containing
     * the required types for testing
     */
    @Override
    public List<TypeDefinition> createTypesList() {
        return singletonTypes;
    }

    public static List<TypeDefinition> getTypesList() {
        return singletonTypes;
    }

    public static TypeDefinition getTypeById(String typeId) {
        for (TypeDefinition typeDef : singletonTypes) {
            if (typeDef.getId().equals(typeId)) {
                return typeDef;
            }
        }
        return null;
    }

    /**
     * create root types and a collection of sample types
     * 
     * @return typesMap map filled with created types
     */
    private static List<TypeDefinition> buildTypesList() {
        TypeDefinitionFactory typeFactory = DocumentTypeCreationHelper.getTypeDefinitionFactory();
        // always add CMIS default types
        List<TypeDefinition> typesList = new LinkedList<TypeDefinition>();

        try {
            // create a complex type with properties
            MutableDocumentTypeDefinition cmisComplexType;
            cmisComplexType = (MutableDocumentTypeDefinition) typeFactory.createChildTypeDefinition(
                    DocumentTypeCreationHelper.getCmisDocumentType(), VERSION_TEST_DOCUMENT_TYPE_ID);
            cmisComplexType.setDisplayName("VersionedType");
            cmisComplexType.setDescription("InMemory test type definition " + VERSION_TEST_DOCUMENT_TYPE_ID);
            cmisComplexType.setIsVersionable(true); // make it a versionable
                                                    // type;

            // create a boolean property definition

            PropertyStringDefinitionImpl prop1 = PropertyCreationHelper.createStringDefinition(PROPERTY_ID,
                    "Sample String Property", Updatability.WHENCHECKEDOUT);
            cmisComplexType.addPropertyDefinition(prop1);

            // add type to types collection
            typesList.add(cmisComplexType);

            return typesList;
        } catch (Exception e) {
            throw new CmisRuntimeException("Error when creating types.", e);
        }
    }

}
