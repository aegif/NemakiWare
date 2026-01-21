/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.server.support;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableDocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableFolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutablePolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutablePropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableRelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableSecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Type definition factory.
 */
public final class TypeDefinitionFactory {

    private static final Set<String> NEW_CMIS11_PROPERTIES = new HashSet<String>();
    static {
        NEW_CMIS11_PROPERTIES.add(PropertyIds.DESCRIPTION);
        NEW_CMIS11_PROPERTIES.add(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        NEW_CMIS11_PROPERTIES.add(PropertyIds.IS_PRIVATE_WORKING_COPY);
    }

    private Class<? extends MutableDocumentTypeDefinition> documentTypeDefinitionClass;
    private Class<? extends MutableFolderTypeDefinition> folderTypeDefinitionClass;
    private Class<? extends MutablePolicyTypeDefinition> policyTypeDefinitionClass;
    private Class<? extends MutableRelationshipTypeDefinition> relationshipTypeDefinitionClass;
    private Class<? extends MutableItemTypeDefinition> itemTypeDefinitionClass;
    private Class<? extends MutableSecondaryTypeDefinition> secondaryTypeDefinitionClass;

    private String defaultNamespace;
    private boolean defaultControllableAcl;
    private boolean defaultIsFullTextIndexed;
    private boolean defaultControllablePolicy;
    private boolean defaultQueryable;
    private boolean defaultFulltextIndexed;
    private TypeMutability defaultTypeMutability;

    private TypeDefinitionFactory() {
        documentTypeDefinitionClass = DocumentTypeDefinitionImpl.class;
        folderTypeDefinitionClass = FolderTypeDefinitionImpl.class;
        policyTypeDefinitionClass = PolicyTypeDefinitionImpl.class;
        relationshipTypeDefinitionClass = RelationshipTypeDefinitionImpl.class;
        itemTypeDefinitionClass = ItemTypeDefinitionImpl.class;
        secondaryTypeDefinitionClass = SecondaryTypeDefinitionImpl.class;

        defaultNamespace = null;
        defaultControllableAcl = false;
        defaultControllablePolicy = false;
        defaultQueryable = true;
        defaultFulltextIndexed = false;

        TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
        typeMutability.setCanCreate(false);
        typeMutability.setCanUpdate(false);
        typeMutability.setCanDelete(false);
        defaultTypeMutability = typeMutability;
    }

    /**
     * Creates a new instance of the factory.
     */
    public static TypeDefinitionFactory newInstance() {
        return new TypeDefinitionFactory();
    }

    // --- definition classes ---

    public Class<? extends MutableDocumentTypeDefinition> getDocumentTypeDefinitionClass() {
        return documentTypeDefinitionClass;
    }

    public void setDocumentTypeDefinitionClass(
            Class<? extends MutableDocumentTypeDefinition> documentTypeDefinitionClass) {
        checkClass(documentTypeDefinitionClass);
        this.documentTypeDefinitionClass = documentTypeDefinitionClass;
    }

    protected MutableDocumentTypeDefinition createDocumentTypeDefinitionObject() {
        try {
            return documentTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    public Class<? extends MutableFolderTypeDefinition> getFolderTypeDefinitionClass() {
        return folderTypeDefinitionClass;
    }

    public void setFolderTypeDefinitionClass(Class<? extends MutableFolderTypeDefinition> folderTypeDefinitionClass) {
        checkClass(folderTypeDefinitionClass);
        this.folderTypeDefinitionClass = folderTypeDefinitionClass;
    }

    protected MutableFolderTypeDefinition createFolderTypeDefinitionObject() {
        try {
            return folderTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    public Class<? extends MutablePolicyTypeDefinition> getPolicyTypeDefinitionClass() {
        return policyTypeDefinitionClass;
    }

    public void setPolicyTypeDefinitionClass(Class<? extends MutablePolicyTypeDefinition> policyTypeDefinitionClass) {
        checkClass(policyTypeDefinitionClass);
        this.policyTypeDefinitionClass = policyTypeDefinitionClass;
    }

    protected MutablePolicyTypeDefinition createPolicyTypeDefinitionObject() {
        try {
            return policyTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    public Class<? extends MutableRelationshipTypeDefinition> getRelationshipTypeDefinitionClass() {
        return relationshipTypeDefinitionClass;
    }

    public void setRelationshipTypeDefinitionClass(
            Class<? extends MutableRelationshipTypeDefinition> relationshipTypeDefinitionClass) {
        checkClass(relationshipTypeDefinitionClass);
        this.relationshipTypeDefinitionClass = relationshipTypeDefinitionClass;
    }

    protected MutableRelationshipTypeDefinition createRelationshipTypeDefinitionObject() {
        try {
            return relationshipTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    public Class<? extends MutableItemTypeDefinition> getItemTypeDefinitionClass() {
        return itemTypeDefinitionClass;
    }

    public void setItemTypeDefinitionClass(Class<? extends MutableItemTypeDefinition> itemTypeDefinitionClass) {
        checkClass(itemTypeDefinitionClass);
        this.itemTypeDefinitionClass = itemTypeDefinitionClass;
    }

    protected MutableItemTypeDefinition createItemTypeDefinitionObject() {
        try {
            return itemTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    public Class<? extends MutableSecondaryTypeDefinition> getSecondaryTypeDefinitionClass() {
        return secondaryTypeDefinitionClass;
    }

    public void setSecondaryTypeDefinitionClass(
            Class<? extends MutableSecondaryTypeDefinition> secondaryTypeDefinitionClass) {
        checkClass(secondaryTypeDefinitionClass);
        this.secondaryTypeDefinitionClass = secondaryTypeDefinitionClass;
    }

    protected MutableSecondaryTypeDefinition createSecondaryTypeDefinitionObject() {
        try {
            return secondaryTypeDefinitionClass.newInstance();
        } catch (Exception e) {
            throw new CmisRuntimeException("Cannot create type defintion object: " + e.getMessage(), e);
        }
    }

    private void checkClass(Class<? extends MutableTypeDefinition> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must be set!");
        }

        // check for default constructor
        try {
            clazz.getConstructor(new Class[0]);
        } catch (Exception e) {
            throw new IllegalArgumentException("Class has no accessible default constructor!", e);
        }
    }

    // --- default values ---

    public String getDefaultNamespace() {
        return defaultNamespace;
    }

    public void setDefaultNamespace(String defaultNamespace) {
        this.defaultNamespace = defaultNamespace;
    }

    public boolean getDefaultIsFulltextIndexed() {
        return defaultIsFullTextIndexed;
    }

    public void setDefaultIsFulltextIndexed(boolean defaultIsFullTextIndexed) {
        this.defaultIsFullTextIndexed = defaultIsFullTextIndexed;
    }

    public boolean getDefaultControllableAcl() {
        return defaultControllableAcl;
    }

    public void setDefaultControllableAcl(boolean defaultControllableAcl) {
        this.defaultControllableAcl = defaultControllableAcl;
    }

    public boolean getDefaultControllablePolicy() {
        return defaultControllablePolicy;
    }

    public void setDefaultControllablePolicy(boolean defaultControllablePolicy) {
        this.defaultControllablePolicy = defaultControllablePolicy;
    }

    public boolean getDefaultQueryable() {
        return defaultQueryable;
    }

    public void setDefaultQueryable(boolean defaultQueryable) {
        this.defaultQueryable = defaultQueryable;
    }

    public boolean getDefaultFulltextIndexed() {
        return defaultFulltextIndexed;
    }

    public void setDefaultFulltextIndexed(boolean defaultFulltextIndexed) {
        this.defaultFulltextIndexed = defaultFulltextIndexed;
    }

    public TypeMutability getDefaultTypeMutability() {
        return defaultTypeMutability;
    }

    public void setDefaultTypeMutability(TypeMutability defaultTypeMutability) {
        this.defaultTypeMutability = defaultTypeMutability;
    }

    // --- create methods ---

    /**
     * Creates a new type mutability object.
     */
    public TypeMutability createTypeMutability(boolean canCreate, boolean canUpdate, boolean canDelete) {
        TypeMutabilityImpl result = new TypeMutabilityImpl();

        result.setCanCreate(canCreate);
        result.setCanUpdate(canUpdate);
        result.setCanDelete(canDelete);

        return result;
    }

    /**
     * Creates a new mutable base document type definition including all
     * property definitions defined in the CMIS specification.
     */
    public MutableDocumentTypeDefinition createBaseDocumentTypeDefinition(CmisVersion cmisVersion) {
        return createDocumentTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable document type definition including all base
     * property definitions defined in the CMIS specification.
     */
    public MutableDocumentTypeDefinition createDocumentTypeDefinition(CmisVersion cmisVersion, String parentId) {
        MutableDocumentTypeDefinition documentType = createDocumentTypeDefinitionObject();
        documentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        documentType.setParentTypeId(parentId);
        documentType.setIsControllableAcl(defaultControllableAcl);
        documentType.setIsControllablePolicy(defaultControllablePolicy);
        documentType.setIsCreatable(true);
        documentType.setDescription("Document");
        documentType.setDisplayName("Document");
        documentType.setIsFileable(true);
        documentType.setIsFulltextIndexed(defaultFulltextIndexed);
        documentType.setIsIncludedInSupertypeQuery(true);
        documentType.setLocalName("Document");
        documentType.setLocalNamespace(defaultNamespace);
        documentType.setIsQueryable(defaultQueryable);
        documentType.setQueryName("cmis:document");
        documentType.setId(BaseTypeId.CMIS_DOCUMENT.value());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            documentType.setTypeMutability(defaultTypeMutability);
        }

        documentType.setIsVersionable(false);
        documentType.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);

        addBasePropertyDefinitions(documentType, cmisVersion, parentId != null);
        addDocumentPropertyDefinitions(documentType, cmisVersion, parentId != null);

        return documentType;
    }

    /**
     * Creates a new mutable base folder type definition including all property
     * definitions defined in the CMIS specification.
     */
    public MutableFolderTypeDefinition createBaseFolderTypeDefinition(CmisVersion cmisVersion) {
        return createFolderTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable folder type definition including all base property
     * definitions defined in the CMIS specification.
     */
    public MutableFolderTypeDefinition createFolderTypeDefinition(CmisVersion cmisVersion, String parentId) {
        MutableFolderTypeDefinition folderType = createFolderTypeDefinitionObject();
        folderType.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
        folderType.setParentTypeId(parentId);
        folderType.setIsControllableAcl(defaultControllableAcl);
        folderType.setIsControllablePolicy(defaultControllablePolicy);
        folderType.setIsCreatable(true);
        folderType.setDescription("Folder");
        folderType.setDisplayName("Folder");
        folderType.setIsFileable(true);
        folderType.setIsFulltextIndexed(defaultFulltextIndexed);
        folderType.setIsIncludedInSupertypeQuery(true);
        folderType.setLocalName("Folder");
        folderType.setLocalNamespace(defaultNamespace);
        folderType.setIsQueryable(defaultQueryable);
        folderType.setQueryName("cmis:folder");
        folderType.setId(BaseTypeId.CMIS_FOLDER.value());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            folderType.setTypeMutability(defaultTypeMutability);
        }

        addBasePropertyDefinitions(folderType, cmisVersion, parentId != null);
        addFolderPropertyDefinitions(folderType, cmisVersion, parentId != null);

        return folderType;
    }

    /**
     * Creates a new mutable base policy type definition including all property
     * definitions defined in the CMIS specification.
     */
    public MutablePolicyTypeDefinition createBasePolicyTypeDefinition(CmisVersion cmisVersion) {
        return createPolicyTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable policy type definition including all base property
     * definitions defined in the CMIS specification.
     */
    public MutablePolicyTypeDefinition createPolicyTypeDefinition(CmisVersion cmisVersion, String parentId) {
        MutablePolicyTypeDefinition policyType = createPolicyTypeDefinitionObject();
        policyType.setBaseTypeId(BaseTypeId.CMIS_POLICY);
        policyType.setParentTypeId(parentId);
        policyType.setIsControllableAcl(defaultControllableAcl);
        policyType.setIsControllablePolicy(defaultControllablePolicy);
        policyType.setIsCreatable(false);
        policyType.setDescription("Policy");
        policyType.setDisplayName("Policy");
        policyType.setIsFileable(false);
        policyType.setIsFulltextIndexed(defaultFulltextIndexed);
        policyType.setIsIncludedInSupertypeQuery(true);
        policyType.setLocalName("Policy");
        policyType.setLocalNamespace(defaultNamespace);
        policyType.setIsQueryable(defaultQueryable);
        policyType.setQueryName("cmis:policy");
        policyType.setId(BaseTypeId.CMIS_POLICY.value());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            policyType.setTypeMutability(defaultTypeMutability);
        }

        addBasePropertyDefinitions(policyType, cmisVersion, parentId != null);
        addPolicyPropertyDefinitions(policyType, cmisVersion, parentId != null);

        return policyType;
    }

    /**
     * Creates a new mutable base relationship type definition including all
     * property definitions defined in the CMIS specification.
     */
    public MutableRelationshipTypeDefinition createBaseRelationshipTypeDefinition(CmisVersion cmisVersion) {
        return createRelationshipTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable relationship type definition including all base
     * property definitions defined in the CMIS specification.
     */
    public MutableRelationshipTypeDefinition createRelationshipTypeDefinition(CmisVersion cmisVersion, String parentId) {
        MutableRelationshipTypeDefinition relationshipType = createRelationshipTypeDefinitionObject();
        relationshipType.setBaseTypeId(BaseTypeId.CMIS_RELATIONSHIP);
        relationshipType.setParentTypeId(parentId);
        relationshipType.setIsControllableAcl(defaultControllableAcl);
        relationshipType.setIsControllablePolicy(defaultControllablePolicy);
        relationshipType.setIsCreatable(false);
        relationshipType.setDescription("Relationship");
        relationshipType.setDisplayName("Relationship");
        relationshipType.setIsFileable(false);
        relationshipType.setIsFulltextIndexed(defaultFulltextIndexed);
        relationshipType.setIsIncludedInSupertypeQuery(true);
        relationshipType.setLocalName("Relationship");
        relationshipType.setLocalNamespace(defaultNamespace);
        relationshipType.setIsQueryable(defaultQueryable);
        relationshipType.setQueryName("cmis:relationship");
        relationshipType.setId(BaseTypeId.CMIS_RELATIONSHIP.value());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            relationshipType.setTypeMutability(defaultTypeMutability);
        }

        addBasePropertyDefinitions(relationshipType, cmisVersion, parentId != null);
        addRelationshipPropertyDefinitions(relationshipType, cmisVersion, parentId != null);

        return relationshipType;
    }

    /**
     * Creates a new mutable base item type definition including all property
     * definitions defined in the CMIS specification.
     */
    public MutableItemTypeDefinition createBaseItemTypeDefinition(CmisVersion cmisVersion) {
        return createItemTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable item type definition including all base property
     * definitions defined in the CMIS specification.
     */
    public MutableItemTypeDefinition createItemTypeDefinition(CmisVersion cmisVersion, String parentId) {
        if (cmisVersion == CmisVersion.CMIS_1_0) {
            throw new IllegalArgumentException("CMIS 1.0 doesn't support item types!");
        }

        MutableItemTypeDefinition itemType = createItemTypeDefinitionObject();
        itemType.setBaseTypeId(BaseTypeId.CMIS_ITEM);
        itemType.setParentTypeId(parentId);
        itemType.setIsControllableAcl(defaultControllableAcl);
        itemType.setIsControllablePolicy(defaultControllablePolicy);
        itemType.setIsCreatable(true);
        itemType.setDescription("Item");
        itemType.setDisplayName("Item");
        itemType.setIsFileable(true);
        itemType.setIsFulltextIndexed(defaultFulltextIndexed);
        itemType.setIsIncludedInSupertypeQuery(true);
        itemType.setLocalName("Item");
        itemType.setLocalNamespace(defaultNamespace);
        itemType.setIsQueryable(defaultQueryable);
        itemType.setQueryName("cmis:item");
        itemType.setId(BaseTypeId.CMIS_ITEM.value());
        itemType.setTypeMutability(defaultTypeMutability);

        addBasePropertyDefinitions(itemType, cmisVersion, parentId != null);

        return itemType;
    }

    /**
     * Creates a new mutable base secondary type definition.
     */
    public MutableSecondaryTypeDefinition createBaseSecondaryTypeDefinition(CmisVersion cmisVersion) {
        return createSecondaryTypeDefinition(cmisVersion, null);
    }

    /**
     * Creates a new mutable secondary type definition.
     */
    public MutableSecondaryTypeDefinition createSecondaryTypeDefinition(CmisVersion cmisVersion, String parentId) {
        if (cmisVersion == CmisVersion.CMIS_1_0) {
            throw new IllegalArgumentException("CMIS 1.0 doesn't support secondary types!");
        }

        MutableSecondaryTypeDefinition secondaryType = createSecondaryTypeDefinitionObject();
        secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
        secondaryType.setParentTypeId(parentId);
        secondaryType.setIsControllableAcl(false);
        secondaryType.setIsControllablePolicy(false);
        secondaryType.setIsCreatable(false);
        secondaryType.setDescription("Secondary");
        secondaryType.setDisplayName("Secondary");
        secondaryType.setIsFileable(false);
        secondaryType.setIsFulltextIndexed(false);
        secondaryType.setIsIncludedInSupertypeQuery(true);
        secondaryType.setLocalName("Secondary");
        secondaryType.setLocalNamespace(defaultNamespace);
        secondaryType.setIsQueryable(defaultQueryable);
        secondaryType.setQueryName("cmis:secondary");
        secondaryType.setId(BaseTypeId.CMIS_SECONDARY.value());
        secondaryType.setTypeMutability(defaultTypeMutability);

        return secondaryType;
    }

    /**
     * Creates a new mutable type definition, which is a child of the provided
     * type definition. Property definitions are copied from the parent and
     * marked as inherited.
     * 
     * @param parentTypeDefinition
     *            the type definition of the parent
     * @param id
     *            the id of the child type definition
     * 
     * @return a mutable child type definition
     */
    public MutableTypeDefinition createChildTypeDefinition(TypeDefinition parentTypeDefinition, String id) {
        return createChildTypeDefinition(parentTypeDefinition, id, id, id, id, null, true, null);
    }

    /**
     * Creates a new mutable type definition, which is a child of the provided
     * type definition. If the parameter {@code includePropertyDefinitions} is
     * set to {@code true} property definitions are copied from the parent and
     * marked as inherited.
     */
    public MutableTypeDefinition createChildTypeDefinition(TypeDefinition parentTypeDefinition, String id,
            String localName, String queryName, String displayName, String description,
            boolean includePropertyDefinitions, CmisVersion cmisVersion) {
        if (parentTypeDefinition == null) {
            throw new IllegalArgumentException("Parent type must be set!");
        }

        if (id == null) {
            throw new IllegalArgumentException("Child id must be set!");
        }

        MutableTypeDefinition childType = copy(parentTypeDefinition, false);

        childType.setParentTypeId(parentTypeDefinition.getId());
        childType.setDescription(description);
        childType.setDisplayName(displayName);
        childType.setLocalName(localName);
        childType.setQueryName(queryName);
        childType.setId(id);

        if (includePropertyDefinitions) {
            copyPropertyDefinitions(parentTypeDefinition, childType, cmisVersion, true);
        }

        return childType;
    }

    /**
     * Creates a property definition object.
     * 
     * @param id
     *            the property ID, not {@code null}
     * @param displayName
     *            the display name, may be {@code null}
     * @param description
     *            the description, may be {@code null}
     * @param datatype
     *            the datatype, not {@code null}
     * @param cardinality
     *            the cardinality, not {@code null}
     * @param updateability
     *            the updateability, not {@code null}
     * @param inherited
     *            {@code true} if the property definition is inherited,
     *            {@code false} otherwise
     * @param required
     *            {@code true} if a property value is required, {@code false}
     *            otherwise
     * @param queryable
     *            {@code true} if the property can be used in the WHERE clause
     *            of a query, {@code false} otherwise
     * @param orderable
     *            {@code true} if the property can be used in the ORDER BY
     *            clause of a query, {@code false} otherwise
     * 
     * @return a mutable property definition object
     */
    public MutablePropertyDefinition<?> createPropertyDefinition(String id, String displayName, String description,
            PropertyType datatype, Cardinality cardinality, Updatability updateability, boolean inherited,
            boolean required, boolean queryable, boolean orderable) {
        if (id == null) {
            throw new IllegalArgumentException("ID must be set!");
        }
        if (datatype == null) {
            throw new IllegalArgumentException("Datatype must be set!");
        }
        if (cardinality == null) {
            throw new IllegalArgumentException("Cardinality must be set!");
        }
        if (updateability == null) {
            throw new IllegalArgumentException("Updateability must be set!");
        }

        MutablePropertyDefinition<?> result = null;

        switch (datatype) {
        case BOOLEAN:
            result = new PropertyBooleanDefinitionImpl();
            break;
        case DATETIME:
            result = new PropertyDateTimeDefinitionImpl();
            break;
        case DECIMAL:
            result = new PropertyDecimalDefinitionImpl();
            break;
        case HTML:
            result = new PropertyHtmlDefinitionImpl();
            break;
        case ID:
            result = new PropertyIdDefinitionImpl();
            break;
        case INTEGER:
            result = new PropertyIntegerDefinitionImpl();
            break;
        case STRING:
            result = new PropertyStringDefinitionImpl();
            break;
        case URI:
            result = new PropertyUriDefinitionImpl();
            break;
        default:
            throw new IllegalArgumentException("Unknown datatype! Spec change?");
        }

        result.setId(id);
        result.setLocalName(id);
        result.setDisplayName(displayName);
        result.setDescription(description);
        result.setPropertyType(datatype);
        result.setCardinality(cardinality);
        result.setUpdatability(updateability);
        result.setIsInherited(inherited);
        result.setIsRequired(required);
        result.setIsQueryable(queryable);
        result.setIsOrderable(orderable);
        result.setQueryName(id);

        return result;
    }

    /**
     * Creates a single value Choice object.
     * 
     * @param displayName
     *            the choice display name
     * @param value
     *            the value
     * 
     * @return the Choice object
     */
    public <T> Choice<T> createChoice(String displayName, T value) {
        ChoiceImpl<T> result = new ChoiceImpl<T>();
        result.setDisplayName(displayName);
        result.setValue(value);

        return result;
    }

    /**
     * Creates a multi value Choice object.
     * 
     * @param displayName
     *            the choice display name
     * @param value
     *            the value
     * 
     * @return the Choice object
     */
    public <T> Choice<T> createChoice(String displayName, List<T> value) {
        ChoiceImpl<T> result = new ChoiceImpl<T>();
        result.setDisplayName(displayName);
        result.setValue(value);

        return result;
    }

    /**
     * Creates a Choice object with sub choices.
     * 
     * @param displayName
     *            the choice display name
     * @param subChoice
     *            the sub choice list
     * 
     * @return the Choice object
     */
    public <T> Choice<T> createChoiceWithSubChoices(String displayName, List<Choice<T>> subChoice) {
        ChoiceImpl<T> result = new ChoiceImpl<T>();
        result.setDisplayName(displayName);
        result.setChoice(subChoice);

        return result;
    }

    /**
     * Creates a type definition list.
     * 
     * @param list
     *            the list of type definitions, not {@code null}
     * @param hasMoreItems
     *            {@code true} if there are more items, {@code false} otherwise
     * @param numItems
     *            the total (positive) number of types at this level or
     *            {@code null} if the number is unknown
     * 
     * @return the TypeDefinitionList object
     */
    public TypeDefinitionList createTypeDefinitionList(List<TypeDefinition> list, boolean hasMoreItems,
            BigInteger numItems) {
        if (list == null) {
            throw new IllegalArgumentException("List must be set!");
        }
        if (numItems != null && numItems.signum() < 0) {
            throw new IllegalArgumentException("Number of items is negative!");
        }

        TypeDefinitionListImpl result = new TypeDefinitionListImpl(list);
        result.setHasMoreItems(hasMoreItems);
        result.setNumItems(numItems);

        return result;
    }

    /**
     * Creates a {@link TypeDefinitionList} for
     * {@link RepositoryService#getTypeChildren(String, String, Boolean, BigInteger, BigInteger, ExtensionsData)}
     * .
     */
    public TypeDefinitionList createTypeDefinitionList(Map<String, TypeDefinition> allTypes, String typeId,
            Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {
        return createTypeDefinitionList(allTypes, typeId, includePropertyDefinitions, maxItems, skipCount, null);
    }

    /**
     * Creates a {@link TypeDefinitionList} for
     * {@link RepositoryService#getTypeChildren(String, String, Boolean, BigInteger, BigInteger, ExtensionsData)}
     * .
     */
    public TypeDefinitionList createTypeDefinitionList(Map<String, TypeDefinition> allTypes, String typeId,
            Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount, CmisVersion cmisVersion) {
        if (allTypes == null) {
            throw new IllegalArgumentException("Types map must be set!");
        }

        if (typeId != null && !allTypes.containsKey(typeId)) {
            throw new CmisObjectNotFoundException("Type '" + typeId + "' does not exist!");
        }

        TypeDefinitionListImpl result = new TypeDefinitionListImpl(Collections.<TypeDefinition> emptyList());
        result.setHasMoreItems(false);
        result.setNumItems(BigInteger.ZERO);

        if (allTypes.isEmpty()) {
            return result;
        }

        int maxItemsInt = maxItems == null ? Integer.MAX_VALUE : maxItems.intValue();
        if (maxItemsInt < 0) {
            maxItemsInt = 0;
        }

        int skipCountInt = skipCount == null ? 0 : skipCount.intValue();
        if (skipCountInt < 0) {
            skipCountInt = 0;
        }

        boolean includePropertyDefinitionsBool = (includePropertyDefinitions == null ? false
                : includePropertyDefinitions.booleanValue());

        List<TypeDefinition> targetList = new ArrayList<TypeDefinition>();
        for (TypeDefinition typeDef : allTypes.values()) {
            if ((typeId == null && typeDef.getParentTypeId() == null)
                    || (typeId != null && typeId.equals(typeDef.getParentTypeId()))) {
                targetList.add(copy(typeDef, includePropertyDefinitionsBool, cmisVersion));
            }
        }

        if (skipCountInt >= targetList.size()) {
            result.setHasMoreItems(false);
            result.setNumItems(BigInteger.valueOf(targetList.size()));
            return result;
        }

        Collections.sort(targetList, new Comparator<TypeDefinition>() {
            @Override
            public int compare(TypeDefinition td1, TypeDefinition td2) {
                String pid1 = td1.getParentTypeId();
                String pid2 = td2.getParentTypeId();
                if (pid1 == null) {
                    if (pid2 == null) {
                        return td1.getId().compareTo(td2.getId());
                    } else {
                        return -1;
                    }
                } else {
                    if (pid2 == null) {
                        return 1;
                    } else {
                        int c = pid1.compareTo(pid2);
                        if (c == 0) {
                            return td1.getId().compareTo(td2.getId());
                        }

                        return c;
                    }
                }
            }
        });

        result.setList(targetList.subList(skipCountInt, Math.min(skipCountInt + maxItemsInt, targetList.size())));
        result.setNumItems(BigInteger.valueOf(targetList.size()));
        result.setHasMoreItems(targetList.size() > skipCountInt + maxItemsInt);

        return result;
    }

    /**
     * Creates a type definition container.
     * 
     * @param typeDef
     *            the type definition, not {@code null}
     * @param children
     *            the child type definitions, may be {@code null}
     * 
     * @return the TypeDefinitionContainer object
     */
    public TypeDefinitionContainer createTypeDefinitionContainer(TypeDefinition typeDef,
            List<TypeDefinitionContainer> children) {
        if (typeDef == null) {
            throw new IllegalArgumentException("Type definition must be set!");
        }

        TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl(typeDef);
        result.setChildren(children);

        return result;
    }

    /**
     * Creates a list of {@link TypeDefinitionContainer} for
     * {@link RepositoryService#getTypeDescendants(String, String, BigInteger, Boolean, ExtensionsData)}
     * .
     */
    public List<TypeDefinitionContainer> createTypeDescendants(Map<String, TypeDefinition> allTypes, String typeId,
            BigInteger depth, Boolean includePropertyDefinitions) {
        return createTypeDescendants(allTypes, typeId, depth, includePropertyDefinitions, null);
    }

    /**
     * Creates a list of {@link TypeDefinitionContainer} for
     * {@link RepositoryService#getTypeDescendants(String, String, BigInteger, Boolean, ExtensionsData)}
     * .
     */
    public List<TypeDefinitionContainer> createTypeDescendants(Map<String, TypeDefinition> allTypes, String typeId,
            BigInteger depth, Boolean includePropertyDefinitions, CmisVersion cmisVersion) {
        if (allTypes == null) {
            throw new IllegalArgumentException("Types map must be set!");
        }

        int depthInt = depth == null ? -1 : depth.intValue();
        if (depthInt == 0) {
            throw new IllegalArgumentException("Depth must not be 0!");
        }
        if (typeId == null) {
            depthInt = -1;
        }

        if (typeId != null && !allTypes.containsKey(typeId)) {
            throw new CmisObjectNotFoundException("Type '" + typeId + "' does not exist!");
        }

        if (allTypes.isEmpty()) {
            return Collections.<TypeDefinitionContainer> emptyList();
        }

        boolean includePropertyDefinitionsBool = includePropertyDefinitions == null ? false
                : includePropertyDefinitions.booleanValue();

        // gather parent ids
        Map<String, Set<String>> typeDefChildren = new HashMap<String, Set<String>>();

        for (TypeDefinition typeDef : allTypes.values()) {
            Set<String> children = typeDefChildren.get(typeDef.getParentTypeId());
            if (children == null) {
                children = new HashSet<String>();
                typeDefChildren.put(typeDef.getParentTypeId(), children);
            }
            children.add(typeDef.getId());
        }

        Set<String> children = typeDefChildren.get(typeId);
        if (children == null) {
            return Collections.<TypeDefinitionContainer> emptyList();
        }

        // build container tree
        List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();
        for (String child : children) {
            result.add(createTypeDefinitionContainer(allTypes, typeDefChildren, child, depthInt - 1,
                    includePropertyDefinitionsBool, cmisVersion));
        }

        return result;
    }

    private TypeDefinitionContainer createTypeDefinitionContainer(Map<String, TypeDefinition> allTypes,
            Map<String, Set<String>> typeDefChildren, String typeId, int depth, boolean includePropertyDefinitions,
            CmisVersion cmisVersion) {
        TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl();
        result.setTypeDefinition(includePropertyDefinitions ? copy(allTypes.get(typeId), true, cmisVersion) : copy(
                allTypes.get(typeId), false, cmisVersion));

        if (depth != 0) {
            if (typeDefChildren.containsKey(typeId)) {
                for (String child : typeDefChildren.get(typeId)) {
                    result.getChildren().add(
                            createTypeDefinitionContainer(allTypes, typeDefChildren, child, depth < 0 ? -1 : depth - 1,
                                    includePropertyDefinitions, cmisVersion));
                }
            }
        }

        return result;
    }

    // --- copy methods ---

    /**
     * Copies the given type definition and returns a mutable object.
     */
    public MutableTypeDefinition copy(TypeDefinition sourceTypeDefintion, boolean includePropertyDefinitions) {
        return copy(sourceTypeDefintion, includePropertyDefinitions, null);
    }

    /**
     * Copies the given type definition and returns a mutable object.
     */
    public MutableTypeDefinition copy(TypeDefinition sourceTypeDefintion, boolean includePropertyDefinitions,
            CmisVersion cmisVersion) {
        if (sourceTypeDefintion == null) {
            throw new IllegalArgumentException("Source type must be set!");
        }

        if (sourceTypeDefintion.getBaseTypeId() == null) {
            throw new IllegalArgumentException("Source type has no base type!");
        }

        MutableTypeDefinition result = null;

        switch (sourceTypeDefintion.getBaseTypeId()) {
        case CMIS_DOCUMENT:
            result = createDocumentTypeDefinitionObject();
            ((MutableDocumentTypeDefinition) result).setIsVersionable(((DocumentTypeDefinition) sourceTypeDefintion)
                    .isVersionable());
            ((MutableDocumentTypeDefinition) result)
                    .setContentStreamAllowed(((DocumentTypeDefinition) sourceTypeDefintion).getContentStreamAllowed());
            break;
        case CMIS_FOLDER:
            result = createFolderTypeDefinitionObject();
            break;
        case CMIS_POLICY:
            result = createPolicyTypeDefinitionObject();
            break;
        case CMIS_RELATIONSHIP:
            result = createRelationshipTypeDefinitionObject();
            List<String> sourceTypeIds = ((RelationshipTypeDefinition) sourceTypeDefintion).getAllowedSourceTypeIds();
            if (sourceTypeIds != null) {
                ((MutableRelationshipTypeDefinition) result)
                        .setAllowedSourceTypes(new ArrayList<String>(sourceTypeIds));
            }
            List<String> targetTypeIds = ((RelationshipTypeDefinition) sourceTypeDefintion).getAllowedTargetTypeIds();
            if (targetTypeIds != null) {
                ((MutableRelationshipTypeDefinition) result)
                        .setAllowedTargetTypes(new ArrayList<String>(targetTypeIds));
            }
            break;
        case CMIS_ITEM:
            if (cmisVersion == CmisVersion.CMIS_1_0) {
                throw new IllegalArgumentException("CMIS 1.0 doesn't support item types!");
            }
            result = createItemTypeDefinitionObject();
            break;
        case CMIS_SECONDARY:
            if (cmisVersion == CmisVersion.CMIS_1_0) {
                throw new IllegalArgumentException("CMIS 1.0 doesn't support secondary types!");
            }
            result = createSecondaryTypeDefinitionObject();
            break;
        default:
            throw new RuntimeException("Unknown base type!");
        }

        result.setId(sourceTypeDefintion.getId());
        result.setLocalName(sourceTypeDefintion.getLocalName());
        result.setLocalNamespace(sourceTypeDefintion.getLocalNamespace());
        result.setDisplayName(sourceTypeDefintion.getDisplayName());
        result.setQueryName(sourceTypeDefintion.getQueryName());
        result.setDescription(sourceTypeDefintion.getDescription());
        result.setBaseTypeId(sourceTypeDefintion.getBaseTypeId());
        result.setParentTypeId(sourceTypeDefintion.getParentTypeId());
        result.setIsCreatable(sourceTypeDefintion.isCreatable());
        result.setIsFileable(sourceTypeDefintion.isFileable());
        result.setIsQueryable(sourceTypeDefintion.isQueryable());
        result.setIsFulltextIndexed(sourceTypeDefintion.isFulltextIndexed());
        result.setIsIncludedInSupertypeQuery(sourceTypeDefintion.isIncludedInSupertypeQuery());
        result.setIsControllablePolicy(sourceTypeDefintion.isControllablePolicy());
        result.setIsControllableAcl(sourceTypeDefintion.isControllableAcl());

        if (cmisVersion != CmisVersion.CMIS_1_0) {
            if (sourceTypeDefintion.getTypeMutability() != null) {
                result.setTypeMutability(createTypeMutability(sourceTypeDefintion.getTypeMutability().canCreate(),
                        sourceTypeDefintion.getTypeMutability().canUpdate(), sourceTypeDefintion.getTypeMutability()
                                .canDelete()));
            }
        }

        copyExtensions(sourceTypeDefintion, result);

        if (includePropertyDefinitions) {
            copyPropertyDefinitions(sourceTypeDefintion, result, cmisVersion, false);
        }

        return result;
    }

    /**
     * Copies the given property definition and returns a mutable object.
     */
    public MutablePropertyDefinition<?> copy(PropertyDefinition<?> sourcePropertyDefinition) {
        if (sourcePropertyDefinition == null) {
            throw new IllegalArgumentException("Source definition must be set!");
        }

        if (sourcePropertyDefinition.getPropertyType() == null) {
            throw new IllegalArgumentException("Source definition property type must be set!");
        }

        MutablePropertyDefinition<?> result = null;

        switch (sourcePropertyDefinition.getPropertyType()) {
        case BOOLEAN:
            result = new PropertyBooleanDefinitionImpl();
            ((PropertyBooleanDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyBooleanDefinition) sourcePropertyDefinition));
            ((PropertyBooleanDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyBooleanDefinition) sourcePropertyDefinition));
            break;
        case DATETIME:
            result = new PropertyDateTimeDefinitionImpl();
            ((PropertyDateTimeDefinitionImpl) result)
                    .setDateTimeResolution(((PropertyDateTimeDefinition) sourcePropertyDefinition)
                            .getDateTimeResolution());
            ((PropertyDateTimeDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyDateTimeDefinition) sourcePropertyDefinition));
            ((PropertyDateTimeDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyDateTimeDefinition) sourcePropertyDefinition));
            break;
        case DECIMAL:
            result = new PropertyDecimalDefinitionImpl();
            ((PropertyDecimalDefinitionImpl) result).setMinValue(((PropertyDecimalDefinition) sourcePropertyDefinition)
                    .getMinValue());
            ((PropertyDecimalDefinitionImpl) result).setMaxValue(((PropertyDecimalDefinition) sourcePropertyDefinition)
                    .getMaxValue());
            ((PropertyDecimalDefinitionImpl) result)
                    .setPrecision(((PropertyDecimalDefinition) sourcePropertyDefinition).getPrecision());
            ((PropertyDecimalDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyDecimalDefinition) sourcePropertyDefinition));
            ((PropertyDecimalDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyDecimalDefinition) sourcePropertyDefinition));
            break;
        case HTML:
            result = new PropertyHtmlDefinitionImpl();
            ((PropertyHtmlDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyHtmlDefinition) sourcePropertyDefinition));
            break;
        case ID:
            result = new PropertyIdDefinitionImpl();
            ((PropertyIdDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyIdDefinition) sourcePropertyDefinition));
            ((PropertyIdDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyIdDefinition) sourcePropertyDefinition));
            break;
        case INTEGER:
            result = new PropertyIntegerDefinitionImpl();
            ((PropertyIntegerDefinitionImpl) result).setMinValue(((PropertyIntegerDefinition) sourcePropertyDefinition)
                    .getMinValue());
            ((PropertyIntegerDefinitionImpl) result).setMaxValue(((PropertyIntegerDefinition) sourcePropertyDefinition)
                    .getMaxValue());
            ((PropertyIntegerDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyIntegerDefinition) sourcePropertyDefinition));
            ((PropertyIntegerDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyIntegerDefinition) sourcePropertyDefinition));
            break;
        case STRING:
            result = new PropertyStringDefinitionImpl();
            ((PropertyStringDefinitionImpl) result).setMaxLength((((PropertyStringDefinition) sourcePropertyDefinition)
                    .getMaxLength()));
            ((PropertyStringDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyStringDefinition) sourcePropertyDefinition));
            ((PropertyStringDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyStringDefinition) sourcePropertyDefinition));
            break;
        case URI:
            result = new PropertyUriDefinitionImpl();
            ((PropertyUriDefinitionImpl) result)
                    .setDefaultValue(copyDefaultValue((PropertyUriDefinition) sourcePropertyDefinition));
            ((PropertyUriDefinitionImpl) result)
                    .setChoices(copyChoices((PropertyUriDefinition) sourcePropertyDefinition));
            break;
        default:
            throw new RuntimeException("Unknown datatype!");
        }

        result.setId(sourcePropertyDefinition.getId());
        result.setLocalName(sourcePropertyDefinition.getLocalName());
        result.setLocalNamespace(sourcePropertyDefinition.getLocalNamespace());
        result.setDisplayName(sourcePropertyDefinition.getDisplayName());
        result.setDescription(sourcePropertyDefinition.getDescription());
        result.setPropertyType(sourcePropertyDefinition.getPropertyType());
        result.setCardinality(sourcePropertyDefinition.getCardinality());
        result.setUpdatability(sourcePropertyDefinition.getUpdatability());
        result.setIsInherited(sourcePropertyDefinition.isInherited());
        result.setIsRequired(sourcePropertyDefinition.isRequired());
        result.setIsQueryable(sourcePropertyDefinition.isQueryable());
        result.setIsOrderable(sourcePropertyDefinition.isOrderable());
        result.setQueryName(sourcePropertyDefinition.getQueryName());
        result.setIsOpenChoice(sourcePropertyDefinition.isOpenChoice());

        copyExtensions(sourcePropertyDefinition, result);

        return result;
    }

    // --- internal methods ---

    /**
     * Copies the property definitions from a source type to a target type.
     */
    protected void copyPropertyDefinitions(TypeDefinition source, MutableTypeDefinition target,
            CmisVersion cmisVersion, boolean markAsInherited) {
        assert source != null;
        assert target != null;

        if (source.getPropertyDefinitions() != null) {
            for (PropertyDefinition<?> propDef : source.getPropertyDefinitions().values()) {
                if (cmisVersion == CmisVersion.CMIS_1_0) {
                    if (NEW_CMIS11_PROPERTIES.contains(propDef.getId())) {
                        continue;
                    }
                }

                MutablePropertyDefinition<?> newPropDef = copy(propDef);
                if (markAsInherited) {
                    newPropDef.setIsInherited(true);
                }
                target.addPropertyDefinition(newPropDef);
            }
        }
    }

    /**
     * Returns a copy of a default value.
     */
    protected <T> List<T> copyDefaultValue(PropertyDefinition<T> source) {
        if (source == null || source.getDefaultValue() == null) {
            return null;
        }

        return new ArrayList<T>(source.getDefaultValue());
    }

    /**
     * Returns a copy of a choice tree.
     */
    protected <T> List<Choice<T>> copyChoices(PropertyDefinition<T> source) {
        if (source == null || source.getChoices() == null) {
            return null;
        }

        List<Choice<T>> result = new ArrayList<Choice<T>>(0);

        for (Choice<T> c : source.getChoices()) {
            result.add(copyChoice(c));
        }

        return result;
    }

    private <T> Choice<T> copyChoice(Choice<T> source) {
        if (source == null) {
            return null;
        }

        ChoiceImpl<T> result = new ChoiceImpl<T>();

        result.setDisplayName(source.getDisplayName());
        if (source.getValue() != null) {
            result.setValue(new ArrayList<T>(source.getValue()));
        }
        if (source.getChoice() != null) {
            List<Choice<T>> choices = new ArrayList<Choice<T>>();
            for (Choice<T> c : source.getChoice()) {
                choices.add(copyChoice(c));
            }
            result.setChoice(choices);
        }

        return result;
    }

    /**
     * Makes a deep copy of extension of a source object and adds them to a
     * target object.
     */
    protected void copyExtensions(ExtensionsData source, ExtensionsData target) {
        if (source == null || target == null) {
            return;
        }

        if (source.getExtensions() == null) {
            target.setExtensions(null);
            return;
        }

        List<CmisExtensionElement> elementList = new ArrayList<CmisExtensionElement>();
        for (CmisExtensionElement element : source.getExtensions()) {
            elementList.add(copy(element));
        }

        target.setExtensions(elementList);
    }

    /**
     * Makes a deep copy of an extension element.
     */
    private CmisExtensionElement copy(CmisExtensionElement element) {
        if (element == null) {
            return null;
        }

        Map<String, String> attrs = (element.getAttributes() != null ? new HashMap<String, String>(
                element.getAttributes()) : null);

        List<CmisExtensionElement> children = element.getChildren();
        if (isNotEmpty(children)) {
            List<CmisExtensionElement> copyChildren = new ArrayList<CmisExtensionElement>(children.size());

            for (CmisExtensionElement child : children) {
                copyChildren.add(copy(child));
            }

            return new CmisExtensionElementImpl(element.getNamespace(), element.getName(), attrs, copyChildren);
        } else {
            return new CmisExtensionElementImpl(element.getNamespace(), element.getName(), attrs, element.getValue());
        }
    }

    /**
     * Adds the base property definitions to a type definition.
     */
    protected void addBasePropertyDefinitions(MutableTypeDefinition type, CmisVersion cmisVersion, boolean inherited) {
        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.NAME, "Name", "Name", PropertyType.STRING,
                Cardinality.SINGLE, Updatability.READWRITE, inherited, true, true, true));

        if (cmisVersion != CmisVersion.CMIS_1_0) {
            type.addPropertyDefinition(createPropertyDefinition(PropertyIds.DESCRIPTION, "Description", "Description",
                    PropertyType.STRING, Cardinality.SINGLE, Updatability.READWRITE, inherited, false, false, false));
        }

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.OBJECT_ID, "Object Id", "Object Id",
                PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, inherited, false, true, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.BASE_TYPE_ID, "Base Type Id", "Base Type Id",
                PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, inherited, false, true, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.OBJECT_TYPE_ID, "Object Type Id",
                "Object Type Id", PropertyType.ID, Cardinality.SINGLE, Updatability.ONCREATE, inherited, true, true,
                false));

        if (cmisVersion != CmisVersion.CMIS_1_0) {
            type.addPropertyDefinition(createPropertyDefinition(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                    "Secondary Type Ids", "Secondary Type Ids", PropertyType.ID, Cardinality.MULTI,
                    Updatability.READONLY, inherited, false, true, false));
        }

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CREATED_BY, "Created By", "Created By",
                PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false, true, true));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CREATION_DATE, "Creation Date",
                "Creation Date", PropertyType.DATETIME, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                true, true));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.LAST_MODIFIED_BY, "Last Modified By",
                "Last Modified By", PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                true, true));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.LAST_MODIFICATION_DATE,
                "Last Modification Date", "Last Modification Date", PropertyType.DATETIME, Cardinality.SINGLE,
                Updatability.READONLY, inherited, false, true, true));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CHANGE_TOKEN, "Change Token", "Change Token",
                PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false, false, false));
    }

    protected void addDocumentPropertyDefinitions(MutableDocumentTypeDefinition type, CmisVersion cmisVersion,
            boolean inherited) {
        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_IMMUTABLE, "Is Immutable", "Is Immutable",
                PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY, inherited, false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_LATEST_VERSION, "Is Latest Version",
                "Is Latest Version", PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_MAJOR_VERSION, "Is Major Version",
                "Is Major Version", PropertyType.BOOLEAN, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_LATEST_MAJOR_VERSION,
                "Is Latest Major Version", "Is Latest Major Version", PropertyType.BOOLEAN, Cardinality.SINGLE,
                Updatability.READONLY, inherited, false, false, false));

        if (cmisVersion != CmisVersion.CMIS_1_0) {
            type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_PRIVATE_WORKING_COPY,
                    "Is Private Working Copy", "Is Private Working Copy", PropertyType.BOOLEAN, Cardinality.SINGLE,
                    Updatability.READONLY, inherited, false, true, false));
        }

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.VERSION_LABEL, "Version Label",
                "Version Label", PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                true, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.VERSION_SERIES_ID, "Version Series Id",
                "Version Series Id", PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                true, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
                "Is Verison Series Checked Out", "Is Verison Series Checked Out", PropertyType.BOOLEAN,
                Cardinality.SINGLE, Updatability.READONLY, inherited, false, true, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
                "Version Series Checked Out By", "Version Series Checked Out By", PropertyType.STRING,
                Cardinality.SINGLE, Updatability.READONLY, inherited, false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
                "Version Series Checked Out Id", "Version Series Checked Out Id", PropertyType.ID, Cardinality.SINGLE,
                Updatability.READONLY, inherited, false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CHECKIN_COMMENT, "Checkin Comment",
                "Checkin Comment", PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CONTENT_STREAM_LENGTH, "Content Stream Length",
                "Content Stream Length", PropertyType.INTEGER, Cardinality.SINGLE, Updatability.READONLY, inherited,
                false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CONTENT_STREAM_MIME_TYPE, "MIME Type",
                "MIME Type", PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false, false,
                false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CONTENT_STREAM_FILE_NAME, "Filename",
                "Filename", PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY, inherited, false, false,
                false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.CONTENT_STREAM_ID, "Content Stream Id",
                "Content Stream Id", PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, inherited, false,
                false, false));
    }

    protected void addFolderPropertyDefinitions(MutableFolderTypeDefinition type, CmisVersion cmisVersion,
            boolean inherited) {
        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.PARENT_ID, "Parent Id", "Parent Id",
                PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY, inherited, false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.PATH, "Path", "Path", PropertyType.STRING,
                Cardinality.SINGLE, Updatability.READONLY, inherited, false, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS,
                "Allowed Child Object Type Ids", "Allowed Child Object Type Ids", PropertyType.ID, Cardinality.MULTI,
                Updatability.READONLY, inherited, false, false, false));
    }

    protected void addPolicyPropertyDefinitions(MutablePolicyTypeDefinition type, CmisVersion cmisVersion,
            boolean inherited) {
        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.POLICY_TEXT, "Policy Text", "Policy Text",
                PropertyType.STRING, Cardinality.SINGLE, Updatability.READWRITE, inherited, false, false, false));
    }

    protected void addRelationshipPropertyDefinitions(MutableRelationshipTypeDefinition type, CmisVersion cmisVersion,
            boolean inherited) {
        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.SOURCE_ID, "Source Id", "Source Id",
                PropertyType.ID, Cardinality.SINGLE, Updatability.READWRITE, inherited, true, false, false));

        type.addPropertyDefinition(createPropertyDefinition(PropertyIds.TARGET_ID, "Target Id", "Target Id",
                PropertyType.ID, Cardinality.SINGLE, Updatability.READWRITE, inherited, true, false, false));
    }
}
