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

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.ItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableDocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableFolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutablePolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutablePropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableRelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableSecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.SecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.server.support.TypeDefinitionFactory;

public final class DocumentTypeCreationHelper {

    public static class InMemoryDocumentType extends DocumentTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(DocumentTypeCreationHelper.getQueryName(id));
        }
    }

    public static class InMemoryFolderType extends FolderTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(DocumentTypeCreationHelper.getQueryName(id));
        }
    }

    public static class InMemoryRelationshipType extends RelationshipTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(DocumentTypeCreationHelper.getQueryName(id));
        }
    }

    public static class InMemoryPolicyType extends PolicyTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(DocumentTypeCreationHelper.getQueryName(id));
        }
    }

    public static class InMemoryItemType extends ItemTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(id);
        }
    }

    public static class InMemorySecondaryType extends SecondaryTypeDefinitionImpl {

        private static final long serialVersionUID = 1L;

        @Override
        public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
            DocumentTypeCreationHelper.addPropertyDefinition(propertyDefinition);
            super.addPropertyDefinition(propertyDefinition);
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            super.setLocalName(id);
            super.setQueryName(id);
        }
    }

    private static final List<TypeDefinition> DEFAULT_TYPES = createCmisDefaultTypes();
    private static TypeDefinitionFactory typeFactory;
    private static MutableDocumentTypeDefinition cmisTypeDoc;
    private static MutableFolderTypeDefinition cmisTypeFolder;
    private static MutableRelationshipTypeDefinition cmisTypeRel;
    private static MutablePolicyTypeDefinition cmisTypePolicy;
    private static MutableItemTypeDefinition cmisTypeItem;
    private static MutableSecondaryTypeDefinition cmisTypeSecondary;

    public static DocumentTypeDefinition getCmisDocumentType() {
        return cmisTypeDoc;
    }

    public static FolderTypeDefinition getCmisFolderType() {
        return cmisTypeFolder;
    }

    public static RelationshipTypeDefinition getCmisRelationshipType() {
        return cmisTypeRel;
    }

    public static PolicyTypeDefinition getCmisPolicyType() {
        return cmisTypePolicy;
    }

    public static ItemTypeDefinition getCmisItemType() {
        return cmisTypeItem;
    }

    public static SecondaryTypeDefinition getCmisSecondaryType() {
        return cmisTypeSecondary;
    }

    private static void initType(MutableTypeDefinition type, TypeDefinition parentTypeDefinition) {
        type.setBaseTypeId(parentTypeDefinition.getBaseTypeId());
        type.setParentTypeId(parentTypeDefinition.getId());
        type.setIsControllableAcl(parentTypeDefinition.isControllableAcl());
        type.setIsControllablePolicy(parentTypeDefinition.isControllablePolicy());
        type.setIsCreatable(parentTypeDefinition.isCreatable());
        type.setDescription(null);
        type.setDisplayName(null);
        type.setIsFileable(parentTypeDefinition.isFileable());
        type.setIsFulltextIndexed(parentTypeDefinition.isFulltextIndexed());
        type.setIsIncludedInSupertypeQuery(parentTypeDefinition.isIncludedInSupertypeQuery());
        type.setLocalName(null);
        type.setLocalNamespace(parentTypeDefinition.getLocalNamespace());
        type.setIsQueryable(parentTypeDefinition.isQueryable());
        type.setQueryName(null);
        type.setId(null);
        type.setTypeMutability(parentTypeDefinition.getTypeMutability());
    }

    /*
     * Creates a new mutable document type definition, which is a child of the
     * provided type definition. Property definitions are not added which is
     * useful for creating additional types at runtime
     */
    public static MutableDocumentTypeDefinition createDocumentTypeDefinitionWithoutBaseProperties(
            DocumentTypeDefinition parentTypeDefinition) throws InstantiationException, IllegalAccessException {
        MutableDocumentTypeDefinition documentType = new InMemoryDocumentType();
        initType(documentType, parentTypeDefinition);

        documentType.setIsVersionable(parentTypeDefinition.isVersionable());
        documentType.setContentStreamAllowed(parentTypeDefinition.getContentStreamAllowed());
        return documentType;
    }

    private DocumentTypeCreationHelper() {
    }

    public static List<TypeDefinition> createMapWithDefaultTypes() {
        List<TypeDefinition> typesList = new LinkedList<TypeDefinition>();
        typesList.addAll(DEFAULT_TYPES);
        return typesList;
    }

    public static List<TypeDefinition> getDefaultTypes() {
        return DEFAULT_TYPES;
    }

    private static void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
        if (propertyDefinition.getId().equals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
            MutablePropertyDefinition<?> propDef = (MutablePropertyDefinition<?>) propertyDefinition;
            propDef.setUpdatability(Updatability.READWRITE);
        }
    }

    private static String getQueryName(String id) {
        if (null == id) {
            return null;
        }

        StringBuilder sb = new StringBuilder(id);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '.' || c == ' ') {
                sb.setCharAt(i, '_');
            }
        }
        return sb.toString();
    }

    public static void setDefaultTypeCapabilities(MutableTypeDefinition cmisType) {
        cmisType.setIsCreatable(true);
        cmisType.setIsFileable(true);
        cmisType.setIsFulltextIndexed(false);
    }

    static TypeMutabilityImpl getBaseTypeMutability() {
        TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
        typeMutability.setCanCreate(true);
        typeMutability.setCanUpdate(false);
        typeMutability.setCanDelete(false);
        return typeMutability;
    }

    private static List<TypeDefinition> createCmisDefaultTypes() {
        TypeDefinitionFactory typeFactoryLocal = getTypeDefinitionFactory();

        List<TypeDefinition> typesList = new LinkedList<TypeDefinition>();

        // create root types:
        try {
            cmisTypeDoc = typeFactoryLocal.createDocumentTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypeDoc);
            cmisTypeDoc.setTypeMutability(getBaseTypeMutability());
            cmisTypeDoc.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
            cmisTypeDoc.setIsVersionable(false);
            typesList.add(cmisTypeDoc);

            cmisTypeFolder = typeFactoryLocal.createFolderTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypeFolder);
            cmisTypeFolder.setTypeMutability(getBaseTypeMutability());
            typesList.add(cmisTypeFolder);

            cmisTypeRel = typeFactoryLocal.createRelationshipTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypeRel);
            cmisTypeRel.setTypeMutability(getBaseTypeMutability());
            cmisTypeRel.setIsFileable(false);
            typesList.add(cmisTypeRel);

            cmisTypePolicy = typeFactoryLocal.createPolicyTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypePolicy);
            cmisTypePolicy.setTypeMutability(getBaseTypeMutability());
            cmisTypePolicy.setIsFileable(false);
            typesList.add(cmisTypePolicy);

            cmisTypeItem = typeFactoryLocal.createItemTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypeItem);
            cmisTypeItem.setTypeMutability(getBaseTypeMutability());
            cmisTypeItem.setIsFileable(true);
            typesList.add(cmisTypeItem);

            cmisTypeSecondary = typeFactoryLocal.createSecondaryTypeDefinition(CmisVersion.CMIS_1_1, null);
            setDefaultTypeCapabilities(cmisTypeSecondary);
            cmisTypeSecondary.setTypeMutability(getBaseTypeMutability());
            cmisTypeSecondary.setIsFileable(false);
            cmisTypeSecondary.setIsCreatable(false);
            typesList.add(cmisTypeSecondary);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error when creating base types. ", e);
        }

        return typesList;
    }

    public static TypeDefinitionFactory getTypeDefinitionFactory() {
        if (null == typeFactory) {
            typeFactory = TypeDefinitionFactory.newInstance();
            typeFactory.setDefaultControllableAcl(true);
            typeFactory.setDefaultControllablePolicy(true);
            typeFactory.setDefaultNamespace("http://apache.org");
            typeFactory.setDefaultIsFulltextIndexed(false);
            typeFactory.setDefaultQueryable(true);
            TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
            typeMutability.setCanCreate(true);
            typeMutability.setCanUpdate(true);
            typeMutability.setCanDelete(true);
            typeFactory.setDefaultTypeMutability(typeMutability);
            typeFactory.setDocumentTypeDefinitionClass(InMemoryDocumentType.class);
            typeFactory.setFolderTypeDefinitionClass(InMemoryFolderType.class);
            typeFactory.setRelationshipTypeDefinitionClass(InMemoryRelationshipType.class);
            typeFactory.setPolicyTypeDefinitionClass(InMemoryPolicyType.class);
            typeFactory.setItemTypeDefinitionClass(InMemoryItemType.class);
            typeFactory.setSecondaryTypeDefinitionClass(InMemorySecondaryType.class);
        }
        return typeFactory;
    }

    /**
     * Create root types and a collection of sample types.
     * 
     * @return typesMap map filled with created types
     */
    public static List<TypeDefinition> createDefaultTypes() {
        List<TypeDefinition> typesList = createCmisDefaultTypes();

        return typesList;
    }

    public static void setBasicPropertyDefinitions(Map<String, PropertyDefinition<?>> propertyDefinitions) {

        PropertyStringDefinitionImpl propS = PropertyCreationHelper.createStringDefinition(PropertyIds.NAME, "Name",
                Updatability.READWRITE);
        propS.setIsRequired(true);
        propertyDefinitions.put(propS.getId(), propS);

        PropertyIdDefinitionImpl propId = PropertyCreationHelper.createIdDefinition(PropertyIds.OBJECT_ID, "Object Id",
                Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        propId = PropertyCreationHelper
                .createIdDefinition(PropertyIds.OBJECT_TYPE_ID, "Type-Id", Updatability.ONCREATE);
        propId.setIsRequired(true);
        propertyDefinitions.put(propId.getId(), propId);

        propId = PropertyCreationHelper.createIdDefinition(PropertyIds.BASE_TYPE_ID, "Base-Type-Id",
                Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.CREATED_BY, "Created By",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        PropertyDateTimeDefinitionImpl propD = PropertyCreationHelper.createDateTimeDefinition(
                PropertyIds.CREATION_DATE, "Creation Date", Updatability.READONLY);
        propertyDefinitions.put(propD.getId(), propD);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.LAST_MODIFIED_BY, "Modified By",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        propD = PropertyCreationHelper.createDateTimeDefinition(PropertyIds.LAST_MODIFICATION_DATE,
                "Modification Date", Updatability.READONLY);
        propertyDefinitions.put(propD.getId(), propD);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.CHANGE_TOKEN, "Change Token",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        // CMIS 1.1:
        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.DESCRIPTION, "Description",
                Updatability.READWRITE);
        propertyDefinitions.put(propS.getId(), propS);

        propId = PropertyCreationHelper.createIdMultiDefinition(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                "Secondary Type Ids", Updatability.READWRITE);
        propertyDefinitions.put(propId.getId(), propId);
    }

    public static void setBasicDocumentPropertyDefinitions(Map<String, PropertyDefinition<?>> propertyDefinitions) {

        setBasicPropertyDefinitions(propertyDefinitions);
        PropertyBooleanDefinitionImpl propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_IMMUTABLE,
                "Immutable", Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_LATEST_VERSION, "Is Latest Version",
                Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_MAJOR_VERSION, "Is Major Version",
                Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_LATEST_MAJOR_VERSION,
                "Is Latest Major Version", Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        PropertyStringDefinitionImpl propS = PropertyCreationHelper.createStringDefinition(PropertyIds.VERSION_LABEL,
                "Version Label", Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        PropertyIdDefinitionImpl propId = PropertyCreationHelper.createIdDefinition(PropertyIds.VERSION_SERIES_ID,
                "Version Series Id", Updatability.READONLY);
        propId.setIsQueryable(false);
        propertyDefinitions.put(propId.getId(), propId);

        propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                "Checked Out", Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                "Checked Out By", Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        propId = PropertyCreationHelper.createIdDefinition(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, "Checked Out Id",
                Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.CHECKIN_COMMENT, "Checkin Comment",
                Updatability.READONLY);
        // read-only, because
        // not set as property
        propertyDefinitions.put(propS.getId(), propS);

        PropertyIntegerDefinitionImpl propI = PropertyCreationHelper.createIntegerDefinition(
                PropertyIds.CONTENT_STREAM_LENGTH, "Content Length", Updatability.READONLY);
        propertyDefinitions.put(propI.getId(), propI);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.CONTENT_STREAM_MIME_TYPE, "Mime Type",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        propS = PropertyCreationHelper.createStringDefinition(PropertyIds.CONTENT_STREAM_FILE_NAME, "File Name",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);

        propId = PropertyCreationHelper.createIdDefinition(PropertyIds.CONTENT_STREAM_ID, "Stream Id",
                Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        // CMIS 1.1:
        propB = PropertyCreationHelper.createBooleanDefinition(PropertyIds.IS_PRIVATE_WORKING_COPY,
                "Private Working Copy", Updatability.READONLY);
        propertyDefinitions.put(propB.getId(), propB);

        propertyDefinitions.put(propS.getId(), propS);
    }

    public static void setBasicFolderPropertyDefinitions(Map<String, PropertyDefinition<?>> propertyDefinitions) {

        setBasicPropertyDefinitions(propertyDefinitions);
        PropertyIdDefinitionImpl propId = PropertyCreationHelper.createIdDefinition(PropertyIds.PARENT_ID, "Parent Id",
                Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        propId = PropertyCreationHelper.createIdMultiDefinition(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS,
                "Allowed Child Types", Updatability.READONLY);
        propertyDefinitions.put(propId.getId(), propId);

        PropertyStringDefinitionImpl propS = PropertyCreationHelper.createStringDefinition(PropertyIds.PATH, "Path",
                Updatability.READONLY);
        propertyDefinitions.put(propS.getId(), propS);
    }

    public static void setBasicPolicyPropertyDefinitions(Map<String, PropertyDefinition<?>> propertyDefinitions) {

        setBasicPropertyDefinitions(propertyDefinitions);
        PropertyStringDefinitionImpl propS = PropertyCreationHelper.createStringDefinition(PropertyIds.POLICY_TEXT,
                "Policy Text", Updatability.READWRITE);
        propS.setIsRequired(true);
        propertyDefinitions.put(propS.getId(), propS);
    }

    public static void setBasicRelationshipPropertyDefinitions(Map<String, PropertyDefinition<?>> propertyDefinitions) {

        setBasicPropertyDefinitions(propertyDefinitions);
        PropertyIdDefinitionImpl propId = PropertyCreationHelper.createIdDefinition(PropertyIds.SOURCE_ID, "Source Id",
                Updatability.READWRITE);
        propId.setIsRequired(true);
        propertyDefinitions.put(propId.getId(), propId);

        propId = PropertyCreationHelper.createIdDefinition(PropertyIds.TARGET_ID, "Target Id", Updatability.READWRITE);
        propId.setIsRequired(true);
        propertyDefinitions.put(propId.getId(), propId);
    }

    public static void mergePropertyDefinitions(Map<String, PropertyDefinition<?>> existingPpropertyDefinitions,
            Map<String, PropertyDefinition<?>> newPropertyDefinitions) {
        for (String propId : newPropertyDefinitions.keySet()) {
            if (existingPpropertyDefinitions.containsKey(propId)) {
                throw new CmisInvalidArgumentException("You can't set a property with id " + propId
                        + ". This property id already exists already or exists in supertype");
            }
        }
        existingPpropertyDefinitions.putAll(newPropertyDefinitions);
    }

}
