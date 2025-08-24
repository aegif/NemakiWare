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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.MutableDocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableFolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableSecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.inmemory.types.DocumentTypeCreationHelper;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeDefinitionFactory;

public class UnitTestTypeSystemCreator implements TypeCreator {
    private static final String PROP_ID_PICK_LIST = "PickListProp";
    public static final List<TypeDefinition> singletonTypes = buildTypesList();
    public static final String COMPLEX_TYPE = "ComplexType";
    public static final String TOPLEVEL_TYPE = "DocumentTopLevel";
    public static final String LEVEL1_TYPE = "DocumentLevel1";
    public static final String LEVEL2_TYPE = "DocumentLevel2";
    public static final String VERSIONED_TYPE = "MyVersionedType";
    public static final String VERSION_PROPERTY_ID = "StringProp";
    public static final String FOLDER_TYPE = "FolderType";
    public static final String SECONDARY_TYPE = "MySecondaryType";
    public static final String SECONDARY_TYPE_2 = "MySecondaryType2";

    public static final String PROP_ID_BOOLEAN = "BooleanProp";
    public static final String PROP_ID_DATETIME = "DateTimeProp";
    public static final String PROP_ID_DECIMAL = "DecimalProp";
    public static final String PROP_ID_HTML = "HtmlProp";
    public static final String PROP_ID_ID = "IdProp";
    public static final String PROP_ID_INT = "IntProp";
    public static final String PROP_ID_STRING = "StringProp";
    public static final String PROP_ID_URI = "UriProp";
    public static final String PROP_ID_BOOLEAN_MULTI_VALUE = "BooleanPropMV";
    public static final String PROP_ID_DATETIME_MULTI_VALUE = "DateTimePropMV";
    public static final String PROP_ID_DECIMAL_MULTI_VALUE = "DecimalPropMV";
    public static final String PROP_ID_HTML_MULTI_VALUE = "HtmlPropMV";
    public static final String PROP_ID_ID_MULTI_VALUE = "IdPropMV";
    public static final String PROP_ID_INT_MULTI_VALUE = "IntPropMV";
    public static final String PROP_ID_STRING_MULTI_VALUE = "StringPropMV";
    public static final String PROP_ID_URI_MULTI_VALUE = "UriPropMV";
    public static final String SECONDARY_STRING_PROP = "SecondaryStringProp";
    public static final String SECONDARY_INTEGER_PROP = "SecondaryIntegerProp";

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
        List<TypeDefinition> typesList = new LinkedList<TypeDefinition>();

        try {
            MutableTypeDefinition cmisType1;
            cmisType1 = typeFactory.createChildTypeDefinition(DocumentTypeCreationHelper.getCmisDocumentType(),
                    LEVEL1_TYPE);
            cmisType1.setDisplayName("Document type with inherited properties, Level 2");
            cmisType1.setDescription("Builtin InMemory type definition " + LEVEL1_TYPE);

            cmisType1 = typeFactory.createChildTypeDefinition(DocumentTypeCreationHelper.getCmisDocumentType(),
                    "MyDocType1");
            cmisType1.setDisplayName("My Type 1 Level 1");
            cmisType1.setDescription("Builtin InMemory type definition MyDocType1");
            typesList.add(cmisType1);

            MutableTypeDefinition cmisType2;
            cmisType2 = typeFactory.createChildTypeDefinition(DocumentTypeCreationHelper.getCmisDocumentType(),
                    "MyDocType2");
            cmisType2.setDisplayName("My Type 2 Level 1");
            cmisType2.setDescription("Builtin InMemory type definition MyDocType2");
            typesList.add(cmisType2);

            MutableTypeDefinition cmisType11;
            cmisType11 = typeFactory.createChildTypeDefinition(cmisType1, "MyDocType1.1");
            cmisType11.setDisplayName("My Type 3 Level 2");
            cmisType11.setDescription("Builtin InMemory type definition MyDocType1.1");
            typesList.add(cmisType11);

            MutableTypeDefinition cmisType111;
            cmisType111 = typeFactory.createChildTypeDefinition(cmisType11, "MyDocType1.1.1");
            cmisType111.setDisplayName("My Type 4 Level 3");
            cmisType111.setDescription("Builtin InMemory type definition MyDocType1.1.1");
            typesList.add(cmisType111);

            MutableTypeDefinition cmisType112;
            cmisType112 = typeFactory.createChildTypeDefinition(cmisType11, "MyDocType1.1.2");
            cmisType112.setDisplayName("My Type 5 Level 3");
            cmisType112.setDescription("Builtin InMemory type definition MyDocType1.1.2");
            typesList.add(cmisType112);

            MutableTypeDefinition cmisType12;
            cmisType12 = typeFactory.createChildTypeDefinition(cmisType1, "MyDocType1.2");
            cmisType12.setDisplayName("My Type 6 Level 2");
            cmisType12.setDescription("Builtin InMemory type definition MyDocType1.2");
            typesList.add(cmisType12);

            MutableTypeDefinition cmisType21;
            cmisType21 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.1");
            cmisType21.setDisplayName("My Type 7 Level 2");
            cmisType21.setDescription("Builtin InMemory type definition MyDocType2.1");
            typesList.add(cmisType21);

            MutableTypeDefinition cmisType22;
            cmisType22 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.2");
            cmisType22.setDisplayName("My Type 8 Level 2");
            cmisType22.setDescription("Builtin InMemory type definition MyDocType2.2");
            typesList.add(cmisType22);

            MutableTypeDefinition cmisType23;
            cmisType23 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.3");
            cmisType23.setDisplayName("My Type 9 Level 2");
            cmisType23.setDescription("Builtin InMemory type definition MyDocType2.3");
            typesList.add(cmisType23);

            MutableTypeDefinition cmisType24;
            cmisType24 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.4");
            cmisType24.setDisplayName("My Type 10 Level 2");
            cmisType24.setDescription("Builtin InMemory type definition MyDocType2.4");
            typesList.add(cmisType24);

            MutableTypeDefinition cmisType25;
            cmisType25 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.5");
            cmisType25.setDisplayName("My Type 11 Level 2");
            cmisType25.setDescription("Builtin InMemory type definition MyDocType2.5");
            typesList.add(cmisType25);

            MutableTypeDefinition cmisType26;
            cmisType26 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.6");
            cmisType26.setDisplayName("My Type 12 Level 2");
            cmisType26.setDescription("Builtin InMemory type definition MyDocType2.6");
            typesList.add(cmisType26);

            MutableTypeDefinition cmisType27;
            cmisType27 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.7");
            cmisType27.setDisplayName("My Type 13 Level 2");
            cmisType27.setDescription("Builtin InMemory type definition MyDocType2.7");
            typesList.add(cmisType27);

            MutableTypeDefinition cmisType28;
            cmisType28 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.8");
            cmisType28.setDisplayName("My Type 14 Level 2");
            cmisType28.setDescription("Builtin InMemory type definition MyDocType2.8");
            typesList.add(cmisType28);

            MutableTypeDefinition cmisType29;
            cmisType29 = typeFactory.createChildTypeDefinition(cmisType2, "MyDocType2.9");
            cmisType29.setDisplayName("My Type 15 Level 2");
            cmisType29.setDescription("Builtin InMemory type definition MyDocType2.9");
            typesList.add(cmisType29);

            // create a complex type with properties
            MutableDocumentTypeDefinition cmisComplexType;
            cmisComplexType = typeFactory.createDocumentTypeDefinition(CmisVersion.CMIS_1_1, DocumentTypeCreationHelper
                    .getCmisDocumentType().getId());
            cmisComplexType.setId(COMPLEX_TYPE);
            cmisComplexType.setDisplayName("Complex type with properties, Level 1");
            cmisComplexType.setDescription("Builtin InMemory type definition ComplexType");

            // create a boolean property definition

            PropertyDefinition<Boolean> prop = PropertyCreationHelper.createBooleanDefinition(PROP_ID_BOOLEAN,
                    "Sample Boolean Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop);

            prop = PropertyCreationHelper.createBooleanMultiDefinition(PROP_ID_BOOLEAN_MULTI_VALUE,
                    "Sample Boolean multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop);

            PropertyDateTimeDefinitionImpl prop2 = PropertyCreationHelper.createDateTimeDefinition(PROP_ID_DATETIME,
                    "Sample DateTime Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop2);

            prop2 = PropertyCreationHelper.createDateTimeMultiDefinition(PROP_ID_DATETIME_MULTI_VALUE,
                    "Sample DateTime multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop2);

            PropertyDecimalDefinitionImpl prop3 = PropertyCreationHelper.createDecimalDefinition(PROP_ID_DECIMAL,
                    "Sample Decimal Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop3);

            prop3 = PropertyCreationHelper.createDecimalMultiDefinition(PROP_ID_DECIMAL_MULTI_VALUE,
                    "Sample Decimal multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop3);

            PropertyHtmlDefinitionImpl prop4 = PropertyCreationHelper.createHtmlDefinition(PROP_ID_HTML,
                    "Sample Html Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop4);

            prop4 = PropertyCreationHelper.createHtmlMultiDefinition(PROP_ID_HTML_MULTI_VALUE,
                    "Sample Html multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop4);

            PropertyIdDefinitionImpl prop5 = PropertyCreationHelper.createIdDefinition(PROP_ID_ID,
                    "Sample Id Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop5);

            prop5 = PropertyCreationHelper.createIdMultiDefinition(PROP_ID_ID_MULTI_VALUE,
                    "Sample Id Html multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop5);

            PropertyIntegerDefinitionImpl prop6 = PropertyCreationHelper.createIntegerDefinition(PROP_ID_INT,
                    "Sample Int Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop6);

            prop6 = PropertyCreationHelper.createIntegerMultiDefinition(PROP_ID_INT_MULTI_VALUE,
                    "Sample Int multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop6);

            PropertyStringDefinitionImpl prop7 = PropertyCreationHelper.createStringDefinition(PROP_ID_STRING,
                    "Sample String Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop7);

            prop7 = PropertyCreationHelper.createStringMultiDefinition(PROP_ID_STRING_MULTI_VALUE,
                    "Sample String multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop7);

            PropertyUriDefinitionImpl prop8 = PropertyCreationHelper.createUriDefinition(PROP_ID_URI,
                    "Sample Uri Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop8);

            prop8 = PropertyCreationHelper.createUriMultiDefinition(PROP_ID_URI_MULTI_VALUE,
                    "Sample Uri multi-value Property", Updatability.READONLY);
            cmisComplexType.addPropertyDefinition(prop8);

            PropertyStringDefinitionImpl prop9 = PropertyCreationHelper.createStringDefinition(PROP_ID_PICK_LIST,
                    "Sample Pick List Property", Updatability.READONLY);
            List<Choice<String>> choiceList = new ArrayList<Choice<String>>();
            ChoiceImpl<String> elem = new ChoiceImpl<String>();
            elem.setValue(Collections.singletonList("red"));
            choiceList.add(elem);
            elem = new ChoiceImpl<String>();
            elem.setValue(Collections.singletonList("green"));
            choiceList.add(elem);
            elem = new ChoiceImpl<String>();
            elem.setValue(Collections.singletonList("blue"));
            choiceList.add(elem);
            elem = new ChoiceImpl<String>();
            elem.setValue(Collections.singletonList("black"));
            choiceList.add(elem);
            prop9.setChoices(choiceList);
            prop9.setDefaultValue(Collections.singletonList("blue"));
            cmisComplexType.addPropertyDefinition(prop9);

            // add type to types collection
            typesList.add(cmisComplexType);

            // create a type hierarchy with inherited properties
            MutableDocumentTypeDefinition cmisDocTypeTopLevel;
            cmisDocTypeTopLevel = typeFactory.createDocumentTypeDefinition(CmisVersion.CMIS_1_1,
                    DocumentTypeCreationHelper.getCmisDocumentType().getId());
            cmisDocTypeTopLevel.setId(TOPLEVEL_TYPE);
            cmisDocTypeTopLevel.setDisplayName("Document type with properties, Level 1");
            cmisDocTypeTopLevel.setDescription("Builtin InMemory type definition " + TOPLEVEL_TYPE);

            MutableTypeDefinition cmisDocTypeLevel1;
            cmisDocTypeLevel1 = typeFactory.createChildTypeDefinition(cmisDocTypeTopLevel, LEVEL1_TYPE);
            cmisDocTypeLevel1.setDisplayName("Document type with inherited properties, Level 2");
            cmisDocTypeLevel1.setDescription("Builtin InMemory type definition " + LEVEL1_TYPE);

            MutableTypeDefinition cmisDocTypeLevel2;
            cmisDocTypeLevel2 = typeFactory.createChildTypeDefinition(cmisDocTypeLevel1, LEVEL2_TYPE);
            cmisDocTypeLevel2.setDisplayName("Document type with inherited properties, Level 3");
            cmisDocTypeLevel2.setDescription("Builtin InMemory type definition " + LEVEL2_TYPE);

            PropertyStringDefinitionImpl propTop = PropertyCreationHelper.createStringDefinition("StringPropTopLevel",
                    "Sample String Property", Updatability.READWRITE);
            cmisDocTypeTopLevel.addPropertyDefinition(propTop);

            PropertyStringDefinitionImpl propLevel1 = PropertyCreationHelper.createStringDefinition("StringPropLevel1",
                    "String Property Level 1", Updatability.READWRITE);
            cmisDocTypeLevel1.addPropertyDefinition(propLevel1);

            PropertyStringDefinitionImpl propLevel2 = PropertyCreationHelper.createStringDefinition("StringPropLevel2",
                    "String Property Level 2", Updatability.READWRITE);
            cmisDocTypeLevel2.addPropertyDefinition(propLevel2);

            // create a versioned type with properties
            MutableDocumentTypeDefinition cmisVersionedType;
            cmisVersionedType = (MutableDocumentTypeDefinition) typeFactory.createChildTypeDefinition(
                    DocumentTypeCreationHelper.getCmisDocumentType(), VERSIONED_TYPE);
            cmisVersionedType.setDisplayName("Versioned Type");
            cmisVersionedType.setDescription("Builtin InMemory type definition " + VERSIONED_TYPE);
            cmisVersionedType.setIsVersionable(true); // make it a versionable
                                                      // type;

            // create a single String property definition
            PropertyStringDefinitionImpl prop1 = PropertyCreationHelper.createStringDefinition(VERSION_PROPERTY_ID,
                    "Sample String Property", Updatability.WHENCHECKEDOUT);
            cmisVersionedType.addPropertyDefinition(prop1);

            // add type to types collection

            // create a folder type
            // create a complex type with properties
            MutableFolderTypeDefinition cmisFolderType;
            cmisFolderType = typeFactory.createFolderTypeDefinition(CmisVersion.CMIS_1_1, DocumentTypeCreationHelper
                    .getCmisFolderType().getId());
            cmisFolderType.setId(FOLDER_TYPE);
            cmisFolderType.setDisplayName("Folder type with properties");
            cmisFolderType.setDescription("InMemory test type definition " + FOLDER_TYPE);

            // create a two property definitions for folder
            prop6 = PropertyCreationHelper.createIntegerDefinition(PROP_ID_INT, "Sample Folder Int Property",
                    Updatability.READONLY);
            cmisFolderType.addPropertyDefinition(prop6);

            prop7 = PropertyCreationHelper.createStringDefinition(PROP_ID_STRING, "Sample Folder String Property",
                    Updatability.READONLY);
            cmisFolderType.addPropertyDefinition(prop7);

            // CMIS 1.1 create a secondary type
            MutableSecondaryTypeDefinition cmisSecondaryType;
            cmisSecondaryType = typeFactory.createSecondaryTypeDefinition(CmisVersion.CMIS_1_1,
                    DocumentTypeCreationHelper.getCmisSecondaryType().getId());
            cmisSecondaryType.setId(SECONDARY_TYPE);
            cmisSecondaryType.setDisplayName("MySecondaryType");
            cmisSecondaryType.setDescription("Builtin InMemory type definition " + SECONDARY_TYPE);
            DocumentTypeCreationHelper.setDefaultTypeCapabilities(cmisSecondaryType);
            cmisSecondaryType.setIsCreatable(false);
            cmisSecondaryType.setIsFileable(false);

            // create a single String property definition
            PropertyStringDefinitionImpl propS1 = PropertyCreationHelper.createStringDefinition(SECONDARY_STRING_PROP,
                    "Secondary String Property", Updatability.READWRITE);
            cmisSecondaryType.addPropertyDefinition(propS1);
            PropertyIntegerDefinitionImpl propS2 = PropertyCreationHelper.createIntegerDefinition(
                    SECONDARY_INTEGER_PROP, "Secondary Integer Property", Updatability.READWRITE);
            propS2.setIsRequired(true);
            cmisSecondaryType.addPropertyDefinition(propS2);

            MutableSecondaryTypeDefinition cmisSecondaryType2;
            cmisSecondaryType2 = typeFactory.createSecondaryTypeDefinition(CmisVersion.CMIS_1_1,
                    DocumentTypeCreationHelper.getCmisSecondaryType().getId());
            cmisSecondaryType2.setId(SECONDARY_TYPE_2);
            cmisSecondaryType2.setDisplayName("MySecondaryType-2");
            cmisSecondaryType2.setDescription("Builtin InMemory type definition " + SECONDARY_TYPE_2);
            DocumentTypeCreationHelper.setDefaultTypeCapabilities(cmisSecondaryType2);
            cmisSecondaryType2.setIsCreatable(false);
            cmisSecondaryType2.setIsFileable(false);
            
            // add type to types collectio
            typesList.add(cmisDocTypeTopLevel);
            typesList.add(cmisDocTypeLevel1);
            typesList.add(cmisDocTypeLevel2);
            typesList.add(cmisVersionedType);
            typesList.add(cmisFolderType);
            typesList.add(cmisSecondaryType);
            typesList.add(cmisSecondaryType2);
            return typesList;
        } catch (Exception e) {
            throw new CmisRuntimeException("Error when creating built-in InMemory types.", e);
        }
    }

}
