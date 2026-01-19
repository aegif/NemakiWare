package jp.aegif.nemaki.odata;

import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlAbstractEdmProvider;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainer;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainerInfo;
import org.apache.olingo.commons.api.edm.provider.CsdlEntitySet;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationPropertyBinding;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.apache.olingo.commons.api.edm.provider.CsdlSchema;
import org.apache.olingo.commons.api.ex.ODataException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OData EDM (Entity Data Model) Provider for CMIS objects.
 * 
 * This provider defines the OData metadata schema that maps CMIS types
 * to OData entity types. It supports:
 * - Documents (cmis:document)
 * - Folders (cmis:folder)
 * - Relationships (cmis:relationship)
 * - Policies (cmis:policy)
 * - Items (cmis:item)
 * 
 * The schema is dynamically generated based on CMIS type definitions.
 */
public class CmisEdmProvider extends CsdlAbstractEdmProvider {
    
    // Service Namespace
    public static final String NAMESPACE = "NemakiWare.CMIS";
    
    // Entity Container
    public static final String CONTAINER_NAME = "Container";
    public static final FullQualifiedName CONTAINER = new FullQualifiedName(NAMESPACE, CONTAINER_NAME);
    
    // Entity Types
    public static final String ET_OBJECT_NAME = "Object";
    public static final FullQualifiedName ET_OBJECT_FQN = new FullQualifiedName(NAMESPACE, ET_OBJECT_NAME);
    
    public static final String ET_DOCUMENT_NAME = "Document";
    public static final FullQualifiedName ET_DOCUMENT_FQN = new FullQualifiedName(NAMESPACE, ET_DOCUMENT_NAME);
    
    public static final String ET_FOLDER_NAME = "Folder";
    public static final FullQualifiedName ET_FOLDER_FQN = new FullQualifiedName(NAMESPACE, ET_FOLDER_NAME);
    
    public static final String ET_RELATIONSHIP_NAME = "Relationship";
    public static final FullQualifiedName ET_RELATIONSHIP_FQN = new FullQualifiedName(NAMESPACE, ET_RELATIONSHIP_NAME);
    
    public static final String ET_POLICY_NAME = "Policy";
    public static final FullQualifiedName ET_POLICY_FQN = new FullQualifiedName(NAMESPACE, ET_POLICY_NAME);
    
    public static final String ET_ITEM_NAME = "Item";
    public static final FullQualifiedName ET_ITEM_FQN = new FullQualifiedName(NAMESPACE, ET_ITEM_NAME);
    
    // Entity Sets
    public static final String ES_OBJECTS_NAME = "Objects";
    public static final String ES_DOCUMENTS_NAME = "Documents";
    public static final String ES_FOLDERS_NAME = "Folders";
    public static final String ES_RELATIONSHIPS_NAME = "Relationships";
    public static final String ES_POLICIES_NAME = "Policies";
    public static final String ES_ITEMS_NAME = "Items";
    
    @Override
    public CsdlEntityType getEntityType(FullQualifiedName entityTypeName) throws ODataException {
        if (entityTypeName.equals(ET_OBJECT_FQN)) {
            return createObjectEntityType();
        } else if (entityTypeName.equals(ET_DOCUMENT_FQN)) {
            return createDocumentEntityType();
        } else if (entityTypeName.equals(ET_FOLDER_FQN)) {
            return createFolderEntityType();
        } else if (entityTypeName.equals(ET_RELATIONSHIP_FQN)) {
            return createRelationshipEntityType();
        } else if (entityTypeName.equals(ET_POLICY_FQN)) {
            return createPolicyEntityType();
        } else if (entityTypeName.equals(ET_ITEM_FQN)) {
            return createItemEntityType();
        }
        return null;
    }
    
    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) throws ODataException {
        if (entityContainer.equals(CONTAINER)) {
            if (entitySetName.equals(ES_OBJECTS_NAME)) {
                return createEntitySet(ES_OBJECTS_NAME, ET_OBJECT_FQN);
            } else if (entitySetName.equals(ES_DOCUMENTS_NAME)) {
                return createEntitySet(ES_DOCUMENTS_NAME, ET_DOCUMENT_FQN);
            } else if (entitySetName.equals(ES_FOLDERS_NAME)) {
                return createEntitySet(ES_FOLDERS_NAME, ET_FOLDER_FQN);
            } else if (entitySetName.equals(ES_RELATIONSHIPS_NAME)) {
                return createEntitySet(ES_RELATIONSHIPS_NAME, ET_RELATIONSHIP_FQN);
            } else if (entitySetName.equals(ES_POLICIES_NAME)) {
                return createEntitySet(ES_POLICIES_NAME, ET_POLICY_FQN);
            } else if (entitySetName.equals(ES_ITEMS_NAME)) {
                return createEntitySet(ES_ITEMS_NAME, ET_ITEM_FQN);
            }
        }
        return null;
    }
    
    @Override
    public CsdlEntityContainer getEntityContainer() throws ODataException {
        List<CsdlEntitySet> entitySets = new ArrayList<>();
        entitySets.add(getEntitySet(CONTAINER, ES_OBJECTS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_DOCUMENTS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_FOLDERS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_RELATIONSHIPS_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_POLICIES_NAME));
        entitySets.add(getEntitySet(CONTAINER, ES_ITEMS_NAME));
        
        CsdlEntityContainer entityContainer = new CsdlEntityContainer();
        entityContainer.setName(CONTAINER_NAME);
        entityContainer.setEntitySets(entitySets);
        
        return entityContainer;
    }
    
    @Override
    public CsdlEntityContainerInfo getEntityContainerInfo(FullQualifiedName entityContainerName) throws ODataException {
        if (entityContainerName == null || entityContainerName.equals(CONTAINER)) {
            CsdlEntityContainerInfo entityContainerInfo = new CsdlEntityContainerInfo();
            entityContainerInfo.setContainerName(CONTAINER);
            return entityContainerInfo;
        }
        return null;
    }
    
    @Override
    public List<CsdlSchema> getSchemas() throws ODataException {
        CsdlSchema schema = new CsdlSchema();
        schema.setNamespace(NAMESPACE);
        
        List<CsdlEntityType> entityTypes = new ArrayList<>();
        entityTypes.add(getEntityType(ET_OBJECT_FQN));
        entityTypes.add(getEntityType(ET_DOCUMENT_FQN));
        entityTypes.add(getEntityType(ET_FOLDER_FQN));
        entityTypes.add(getEntityType(ET_RELATIONSHIP_FQN));
        entityTypes.add(getEntityType(ET_POLICY_FQN));
        entityTypes.add(getEntityType(ET_ITEM_FQN));
        schema.setEntityTypes(entityTypes);
        
        schema.setEntityContainer(getEntityContainer());
        
        return Collections.singletonList(schema);
    }
    
    /**
     * Create the base Object entity type with common CMIS properties.
     */
    private CsdlEntityType createObjectEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_OBJECT_NAME);
        
        // Key property
        CsdlPropertyRef propertyRef = new CsdlPropertyRef();
        propertyRef.setName("objectId");
        entityType.setKey(Collections.singletonList(propertyRef));
        
        // Properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("objectId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("objectTypeId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("baseTypeId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("name", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("description", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("createdBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("creationDate", EdmPrimitiveTypeKind.DateTimeOffset, true));
        properties.add(createProperty("lastModifiedBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("lastModificationDate", EdmPrimitiveTypeKind.DateTimeOffset, true));
        properties.add(createProperty("changeToken", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        return entityType;
    }
    
    /**
     * Create the Document entity type extending Object with document-specific properties.
     */
    private CsdlEntityType createDocumentEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_DOCUMENT_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Document-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("isImmutable", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isLatestVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isMajorVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isLatestMajorVersion", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("isPrivateWorkingCopy", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("versionLabel", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("versionSeriesId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("isVersionSeriesCheckedOut", EdmPrimitiveTypeKind.Boolean, true));
        properties.add(createProperty("versionSeriesCheckedOutBy", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("versionSeriesCheckedOutId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("checkinComment", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamLength", EdmPrimitiveTypeKind.Int64, true));
        properties.add(createProperty("contentStreamMimeType", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamFileName", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("contentStreamId", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("parent", ET_FOLDER_FQN, false));
        navProperties.add(createNavigationProperty("parents", ET_FOLDER_FQN, true));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Folder entity type extending Object with folder-specific properties.
     */
    private CsdlEntityType createFolderEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_FOLDER_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Folder-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("parentId", EdmPrimitiveTypeKind.String, true));
        properties.add(createProperty("path", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("parent", ET_FOLDER_FQN, false));
        navProperties.add(createNavigationProperty("children", ET_OBJECT_FQN, true));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Relationship entity type with source/target references.
     */
    private CsdlEntityType createRelationshipEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_RELATIONSHIP_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Relationship-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("sourceId", EdmPrimitiveTypeKind.String, false));
        properties.add(createProperty("targetId", EdmPrimitiveTypeKind.String, false));
        entityType.setProperties(properties);
        
        // Navigation properties
        List<CsdlNavigationProperty> navProperties = new ArrayList<>();
        navProperties.add(createNavigationProperty("source", ET_OBJECT_FQN, false));
        navProperties.add(createNavigationProperty("target", ET_OBJECT_FQN, false));
        entityType.setNavigationProperties(navProperties);
        
        return entityType;
    }
    
    /**
     * Create the Policy entity type with policy-specific properties.
     */
    private CsdlEntityType createPolicyEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_POLICY_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        // Policy-specific properties
        List<CsdlProperty> properties = new ArrayList<>();
        properties.add(createProperty("policyText", EdmPrimitiveTypeKind.String, true));
        entityType.setProperties(properties);
        
        return entityType;
    }
    
    /**
     * Create the Item entity type (generic CMIS item).
     */
    private CsdlEntityType createItemEntityType() {
        CsdlEntityType entityType = new CsdlEntityType();
        entityType.setName(ET_ITEM_NAME);
        entityType.setBaseType(ET_OBJECT_FQN);
        
        return entityType;
    }
    
    /**
     * Helper method to create a property.
     */
    private CsdlProperty createProperty(String name, EdmPrimitiveTypeKind type, boolean nullable) {
        return new CsdlProperty()
                .setName(name)
                .setType(type.getFullQualifiedName())
                .setNullable(nullable);
    }
    
    /**
     * Helper method to create a navigation property.
     */
    private CsdlNavigationProperty createNavigationProperty(String name, FullQualifiedName type, boolean isCollection) {
        return new CsdlNavigationProperty()
                .setName(name)
                .setType(type)
                .setCollection(isCollection)
                .setNullable(true);
    }
    
    /**
     * Helper method to create an entity set.
     */
    private CsdlEntitySet createEntitySet(String name, FullQualifiedName entityType) {
        CsdlEntitySet entitySet = new CsdlEntitySet();
        entitySet.setName(name);
        entitySet.setType(entityType);
        return entitySet;
    }
}
