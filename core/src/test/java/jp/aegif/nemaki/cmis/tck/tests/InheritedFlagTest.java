package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class InheritedFlagTest {

    @Test
    public void testInheritedFlags() {
        System.out.println("=== INHERITED FLAG TEST ===");

        // Create session
        SessionFactory factory = SessionFactoryImpl.newInstance();
        Map<String, String> parameters = new HashMap<>();
        // FIX: Browser binding URL must include repository ID
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");

        Session session = factory.createSession(parameters);

        // Check cmis:document type
        TypeDefinition docType = session.getTypeDefinition("cmis:document");
        System.out.println("\n=== cmis:document type ===");
        checkPropertyDefinitions(docType);

        // Check cmis:folder type
        TypeDefinition folderType = session.getTypeDefinition("cmis:folder");
        System.out.println("\n=== cmis:folder type ===");
        checkPropertyDefinitions(folderType);

        // Check a derived type if it exists
        try {
            TypeDefinition customType = session.getTypeDefinition("custom:testType");
            System.out.println("\n=== custom:testType (derived) ===");
            checkPropertyDefinitions(customType);
        } catch (Exception e) {
            System.out.println("No custom:testType found");
        }
    }

    private void checkPropertyDefinitions(TypeDefinition type) {
        Map<String, PropertyDefinition<?>> props = type.getPropertyDefinitions();

        // Check critical properties
        String[] criticalProps = {"cmis:objectId", "cmis:baseTypeId", "cmis:name"};

        for (String propId : criticalProps) {
            PropertyDefinition<?> prop = props.get(propId);
            if (prop != null) {
                Boolean inherited = prop.isInherited();
                System.out.println(propId + " - inherited: " + inherited +
                    " (null? " + (inherited == null) + ")");
            } else {
                System.out.println(propId + " - NOT FOUND");
            }
        }

        // Count null inherited flags
        int nullCount = 0;
        int totalCount = 0;
        for (PropertyDefinition<?> prop : props.values()) {
            totalCount++;
            if (prop.isInherited() == null) {
                nullCount++;
                System.out.println("  NULL inherited: " + prop.getId());
            }
        }

        System.out.println("Total properties: " + totalCount + ", NULL inherited: " + nullCount);
    }
}