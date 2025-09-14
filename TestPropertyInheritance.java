import org.apache.chemistry.opencmis.client.api.*;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;

import java.util.HashMap;
import java.util.Map;

public class TestPropertyInheritance {
    public static void main(String[] args) {
        try {
            // Create session parameters
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");

            // Create session
            SessionFactory factory = SessionFactoryImpl.newInstance();
            Session session = factory.createSession(parameters);

            // Test 1: Check base type (cmis:document)
            System.out.println("=== TEST 1: Base Type (cmis:document) ===");
            ObjectType documentType = session.getTypeDefinition("cmis:document");
            checkPropertyDefinitions(documentType);

            // Test 2: Create a derived type
            System.out.println("\n=== TEST 2: Creating Derived Type ===");
            DocumentTypeDefinitionImpl newType = new DocumentTypeDefinitionImpl();
            newType.setId("test:inherited_check_" + System.currentTimeMillis());
            newType.setParentTypeId("cmis:document");
            newType.setBaseTypeId(documentType.getBaseTypeId());
            newType.setLocalName("TestInheritedCheck");
            newType.setDisplayName("Test Inherited Check");
            newType.setQueryName("test:inherited_check");
            newType.setDescription("Test type for checking inherited flags");
            newType.setIsCreatable(true);
            newType.setIsFileable(true);
            newType.setIsQueryable(true);
            newType.setIsControllableAcl(true);
            newType.setIsControllablePolicy(false);
            newType.setIsFulltextIndexed(true);
            newType.setIsIncludedInSupertypeQuery(true);
            newType.setIsVersionable(false);
            newType.setContentStreamAllowed(org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed.ALLOWED);

            ObjectType createdType = session.createType(newType);
            System.out.println("Created type: " + createdType.getId());

            // Test 3: Retrieve the created type and check properties
            System.out.println("\n=== TEST 3: Retrieved Derived Type ===");
            ObjectType retrievedType = session.getTypeDefinition(createdType.getId());
            checkPropertyDefinitions(retrievedType);

            // Test 4: Compare object identities
            System.out.println("\n=== TEST 4: Object Identity Comparison ===");
            System.out.println("createdType == retrievedType: " + (createdType == retrievedType));

            PropertyDefinition<?> createdObjId = createdType.getPropertyDefinitions().get("cmis:objectId");
            PropertyDefinition<?> retrievedObjId = retrievedType.getPropertyDefinitions().get("cmis:objectId");
            System.out.println("cmis:objectId PropertyDefinition identity: " + (createdObjId == retrievedObjId));

            // Cleanup
            try {
                session.deleteType(createdType.getId());
                System.out.println("\nDeleted test type: " + createdType.getId());
            } catch (Exception e) {
                System.out.println("Could not delete type: " + e.getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void checkPropertyDefinitions(ObjectType type) {
        System.out.println("Type: " + type.getId());
        System.out.println("BaseTypeId: " + type.getBaseTypeId());
        System.out.println("ParentTypeId: " + type.getParentTypeId());

        // Check if this is a base type
        boolean isBaseType = type.getBaseTypeId().value().equals(type.getId());
        System.out.println("Is Base Type: " + isBaseType);

        // Check some CMIS properties
        String[] cmisProps = {"cmis:objectId", "cmis:baseTypeId", "cmis:name", "cmis:createdBy"};
        for (String propId : cmisProps) {
            PropertyDefinition<?> prop = type.getPropertyDefinitions().get(propId);
            if (prop != null) {
                boolean expectedInherited = !isBaseType;
                boolean actualInherited = prop.isInherited();
                String status = (expectedInherited == actualInherited) ? "✓" : "✗";
                System.out.println("  " + status + " " + propId + ": inherited=" + actualInherited +
                    " (expected=" + expectedInherited + ")");
            }
        }
    }
}