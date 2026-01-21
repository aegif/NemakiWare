package jp.aegif.nemaki.cmis.tck;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.MutableDocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.AfterClass;
import static org.junit.Assert.*;

/**
 * Direct implementation of TCK Types tests without AbstractRunner
 */
public class DirectTypesTestGroup {

    private static Session session;
    private static List<String> createdTypeIds = new ArrayList<>();

    @BeforeClass
    public static void setUp() throws Exception {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        parameters.put(SessionParameter.CONNECT_TIMEOUT, "5000");
        parameters.put(SessionParameter.READ_TIMEOUT, "10000");

        SessionFactory factory = SessionFactoryImpl.newInstance();
        session = factory.createSession(parameters);

        System.out.println("=== TCK TYPES TEST GROUP START ===");
        System.out.println("Repository: " + session.getRepositoryInfo().getId());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // Clean up created types
        for (String typeId : createdTypeIds) {
            try {
                session.deleteType(typeId);
                System.out.println("Deleted test type: " + typeId);
            } catch (Exception e) {
                // Ignore deletion errors
            }
        }
        System.out.println("=== TCK TYPES TEST GROUP END ===");
    }

    @Test
    public void testBaseTypes() throws Exception {
        System.out.println("\n--- Test: Base Types ---");

        // Test all base types exist
        String[] baseTypeIds = {
            "cmis:document",
            "cmis:folder",
            "cmis:relationship",
            "cmis:policy"
        };

        for (String typeId : baseTypeIds) {
            TypeDefinition type = session.getTypeDefinition(typeId);
            assertNotNull(typeId + " type should exist", type);
            assertEquals(typeId, type.getId());
            assertTrue(typeId + " should have properties",
                type.getPropertyDefinitions() != null && !type.getPropertyDefinitions().isEmpty());
            System.out.println("✓ " + typeId + " found with " +
                type.getPropertyDefinitions().size() + " properties");
        }
    }

    @Test
    public void testCreateAndDeleteType() throws Exception {
        System.out.println("\n--- Test: Create and Delete Type ---");

        String typeId = "tck:testdoctype_" + System.currentTimeMillis();

        // Create a new document type
        MutableDocumentTypeDefinition newType = new DocumentTypeDefinitionImpl();
        newType.setId(typeId);
        newType.setLocalName("TCK Test Document Type");
        newType.setDisplayName("TCK Test Document Type");
        newType.setDescription("Created by TCK test");
        newType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        newType.setParentTypeId("cmis:document");
        newType.setIsCreatable(true);
        newType.setIsFileable(true);
        newType.setIsQueryable(true);
        newType.setIsControllablePolicy(false);
        newType.setIsControllableAcl(true);
        newType.setIsIncludedInSupertypeQuery(true);
        newType.setIsFulltextIndexed(false);
        newType.setIsVersionable(true);
        newType.setContentStreamAllowed(org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed.ALLOWED);

        // Add custom properties
        Map<String, PropertyDefinition<?>> propertyDefinitions = new HashMap<>();

        // Add string property
        PropertyStringDefinitionImpl stringProp = new PropertyStringDefinitionImpl();
        stringProp.setId("tck:string");
        stringProp.setLocalName("tck:string");
        stringProp.setDisplayName("TCK String Property");
        stringProp.setQueryName("tck:string");
        stringProp.setPropertyType(PropertyType.STRING);
        stringProp.setCardinality(Cardinality.SINGLE);
        stringProp.setUpdatability(Updatability.READWRITE);
        stringProp.setIsRequired(false);
        stringProp.setIsQueryable(true);
        stringProp.setIsOrderable(true);
        propertyDefinitions.put(stringProp.getId(), stringProp);

        // Add boolean property
        PropertyBooleanDefinitionImpl boolProp = new PropertyBooleanDefinitionImpl();
        boolProp.setId("tck:boolean");
        boolProp.setLocalName("tck:boolean");
        boolProp.setDisplayName("TCK Boolean Property");
        boolProp.setQueryName("tck:boolean");
        boolProp.setPropertyType(PropertyType.BOOLEAN);
        boolProp.setCardinality(Cardinality.SINGLE);
        boolProp.setUpdatability(Updatability.READWRITE);
        boolProp.setIsRequired(false);
        boolProp.setIsQueryable(true);
        boolProp.setIsOrderable(false);  // Boolean properties are typically not orderable
        propertyDefinitions.put(boolProp.getId(), boolProp);

        // Add integer property
        PropertyIntegerDefinitionImpl intProp = new PropertyIntegerDefinitionImpl();
        intProp.setId("tck:integer");
        intProp.setLocalName("tck:integer");
        intProp.setDisplayName("TCK Integer Property");
        intProp.setQueryName("tck:integer");
        intProp.setPropertyType(PropertyType.INTEGER);
        intProp.setCardinality(Cardinality.SINGLE);
        intProp.setUpdatability(Updatability.READWRITE);
        intProp.setIsRequired(false);
        intProp.setIsQueryable(true);
        intProp.setIsOrderable(true);
        propertyDefinitions.put(intProp.getId(), intProp);

        // Add property definitions to type
        for (PropertyDefinition<?> propDef : propertyDefinitions.values()) {
            newType.addPropertyDefinition(propDef);
        }

        System.out.println("Creating type: " + typeId);
        ObjectType createdType = null;
        try {
            createdType = session.createType(newType);
        } catch (Exception e) {
            System.err.println("Failed to create type: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        assertNotNull("Created type should not be null", createdType);
        assertEquals(typeId, createdType.getId());
        createdTypeIds.add(typeId);
        System.out.println("✓ Type created: " + createdType.getId());

        // Verify the type exists
        TypeDefinition fetchedType = session.getTypeDefinition(typeId);
        assertNotNull("Should be able to fetch created type", fetchedType);
        assertEquals(typeId, fetchedType.getId());
        System.out.println("✓ Type verified: " + fetchedType.getId());

        // Check properties
        Map<String, PropertyDefinition<?>> props = fetchedType.getPropertyDefinitions();
        assertNotNull("Type should have properties", props);

        // Check inherited CMIS properties
        assertTrue("Should have cmis:objectId", props.containsKey("cmis:objectId"));
        assertTrue("Should have cmis:name", props.containsKey("cmis:name"));
        System.out.println("✓ Inherited " + props.size() + " properties including CMIS properties");

        // Check custom properties
        boolean hasCustomString = false;
        boolean hasCustomBoolean = false;
        boolean hasCustomInteger = false;

        for (PropertyDefinition<?> prop : props.values()) {
            if (prop.getId().startsWith("tck:string")) {
                hasCustomString = true;
                assertEquals(PropertyType.STRING, prop.getPropertyType());
                System.out.println("✓ Custom string property found: " + prop.getId());
            } else if (prop.getId().startsWith("tck:boolean")) {
                hasCustomBoolean = true;
                assertEquals(PropertyType.BOOLEAN, prop.getPropertyType());
                System.out.println("✓ Custom boolean property found: " + prop.getId());
            } else if (prop.getId().startsWith("tck:integer")) {
                hasCustomInteger = true;
                assertEquals(PropertyType.INTEGER, prop.getPropertyType());
                System.out.println("✓ Custom integer property found: " + prop.getId());
            }
        }

        assertTrue("Should have custom string property", hasCustomString);
        assertTrue("Should have custom boolean property", hasCustomBoolean);
        assertTrue("Should have custom integer property", hasCustomInteger);

        // Delete the type
        System.out.println("Deleting type: " + typeId);
        session.deleteType(typeId);
        createdTypeIds.remove(typeId);
        System.out.println("✓ Type deleted");

        // Verify deletion
        try {
            session.getTypeDefinition(typeId);
            fail("Type should have been deleted");
        } catch (CmisObjectNotFoundException e) {
            System.out.println("✓ Type deletion verified");
        }
    }

    @Test
    public void testTypeInheritance() throws Exception {
        System.out.println("\n--- Test: Type Inheritance ---");

        String parentTypeId = "tck:parenttype_" + System.currentTimeMillis();
        String childTypeId = "tck:childtype_" + System.currentTimeMillis();

        try {
            // Create parent type
            MutableDocumentTypeDefinition parentType = new DocumentTypeDefinitionImpl();
            parentType.setId(parentTypeId);
            parentType.setLocalName("TCK Parent Type");
            parentType.setDisplayName("TCK Parent Type");
            parentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
            parentType.setParentTypeId("cmis:document");
            parentType.setIsCreatable(true);
            parentType.setIsFileable(true);
            parentType.setIsQueryable(true);

            // Add parent property
            Map<String, PropertyDefinition<?>> parentProps = new HashMap<>();
            PropertyStringDefinitionImpl parentProp = new PropertyStringDefinitionImpl();
            parentProp.setId("tck:parentprop");
            parentProp.setLocalName("tck:parentprop");
            parentProp.setDisplayName("Parent Property");
            parentProp.setQueryName("tck:parentprop");
            parentProp.setPropertyType(PropertyType.STRING);
            parentProp.setCardinality(Cardinality.SINGLE);
            parentProp.setUpdatability(Updatability.READWRITE);
            parentProp.setIsRequired(false);
            parentProp.setIsQueryable(true);
            parentProp.setIsOrderable(true);
            parentProps.put(parentProp.getId(), parentProp);
            for (PropertyDefinition<?> propDef : parentProps.values()) {
                parentType.addPropertyDefinition(propDef);
            }

            ObjectType createdParent = null;
            try {
                createdParent = session.createType(parentType);
            } catch (Exception e) {
                System.err.println("Failed to create parent type: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            createdTypeIds.add(parentTypeId);
            System.out.println("✓ Parent type created: " + createdParent.getId());

            // Verify parent type has its property
            TypeDefinition fetchedParent = session.getTypeDefinition(parentTypeId);
            System.out.println("Parent type has " + fetchedParent.getPropertyDefinitions().size() + " properties");
            boolean foundParentProp = false;
            for (PropertyDefinition<?> prop : fetchedParent.getPropertyDefinitions().values()) {
                if (prop.getId().startsWith("tck:parentprop")) {
                    foundParentProp = true;
                    System.out.println("✓ Parent type has its property: " + prop.getId());
                }
            }
            assertTrue("Parent type should have its custom property", foundParentProp);

            // Create child type
            MutableDocumentTypeDefinition childType = new DocumentTypeDefinitionImpl();
            childType.setId(childTypeId);
            childType.setLocalName("TCK Child Type");
            childType.setDisplayName("TCK Child Type");
            childType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
            childType.setParentTypeId(parentTypeId);

            // Debug: Check parent type definition before creating child
            System.out.println("\n=== DEBUG: Parent type before child creation ===");
            TypeDefinition parentCheck = session.getTypeDefinition(parentTypeId);
            for (PropertyDefinition<?> prop : parentCheck.getPropertyDefinitions().values()) {
                if (!prop.getId().startsWith("cmis:")) {
                    System.out.println("  Parent custom property: " + prop.getId());
                }
            }
            childType.setIsCreatable(true);
            childType.setIsFileable(true);
            childType.setIsQueryable(true);

            // Add child property
            Map<String, PropertyDefinition<?>> childProps = new HashMap<>();
            PropertyStringDefinitionImpl childProp = new PropertyStringDefinitionImpl();
            childProp.setId("tck:childprop");
            childProp.setLocalName("tck:childprop");
            childProp.setDisplayName("Child Property");
            childProp.setQueryName("tck:childprop");
            childProp.setPropertyType(PropertyType.STRING);
            childProp.setCardinality(Cardinality.SINGLE);
            childProp.setUpdatability(Updatability.READWRITE);
            childProp.setIsRequired(false);
            childProp.setIsQueryable(true);
            childProp.setIsOrderable(true);
            childProps.put(childProp.getId(), childProp);
            for (PropertyDefinition<?> propDef : childProps.values()) {
                childType.addPropertyDefinition(propDef);
            }

            ObjectType createdChild = session.createType(childType);
            createdTypeIds.add(childTypeId);
            System.out.println("✓ Child type created: " + createdChild.getId());

            // Debug: Check what properties the created child type actually has
            System.out.println("\n=== DEBUG: Created child type properties ===");
            for (PropertyDefinition<?> prop : createdChild.getPropertyDefinitions().values()) {
                if (!prop.getId().startsWith("cmis:")) {
                    System.out.println("  Child custom property after creation: " + prop.getId() + " (inherited=" + prop.isInherited() + ")");
                }
            }

            // Verify inheritance
            TypeDefinition fetchedChild = session.getTypeDefinition(childTypeId);
            Map<String, PropertyDefinition<?>> allProps = fetchedChild.getPropertyDefinitions();

            System.out.println("Child type has " + allProps.size() + " properties total");

            // Debug: List all properties
            for (PropertyDefinition<?> prop : allProps.values()) {
                System.out.println("  Property: " + prop.getId() + " (inherited=" + prop.isInherited() + ")");
            }

            // Should have CMIS properties
            assertTrue("Should have cmis:objectId", allProps.containsKey("cmis:objectId"));
            assertTrue("Should have cmis:name", allProps.containsKey("cmis:name"));

            // Check for parent and child properties
            boolean hasParentProp = false;
            boolean hasChildProp = false;

            for (PropertyDefinition<?> prop : allProps.values()) {
                if (prop.getId().startsWith("tck:parentprop")) {
                    hasParentProp = true;
                    System.out.println("✓ Inherited parent property: " + prop.getId());
                } else if (prop.getId().startsWith("tck:childprop")) {
                    hasChildProp = true;
                    System.out.println("✓ Child property: " + prop.getId());
                }
            }

            assertTrue("Child type should inherit parent property", hasParentProp);
            assertTrue("Child type should have its own property", hasChildProp);

        } finally {
            // Clean up child first, then parent
            try {
                session.deleteType(childTypeId);
                createdTypeIds.remove(childTypeId);
            } catch (Exception e) {
                // Ignore
            }
            try {
                session.deleteType(parentTypeId);
                createdTypeIds.remove(parentTypeId);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}