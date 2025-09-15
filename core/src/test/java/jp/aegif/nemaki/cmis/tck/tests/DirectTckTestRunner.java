package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTestGroup;
import org.apache.chemistry.opencmis.tck.tests.basics.RootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.basics.SecurityTest;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.junit.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.fail;

/**
 * Direct TCK Test Runner that bypasses JUnitRunner hanging issues
 * This class directly runs TCK tests without using @RunWith annotation
 */
public class DirectTckTestRunner {

    private static final String CMIS_URL = "http://localhost:8080/core/atom/bedroom";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final String REPOSITORY_ID = "bedroom";

    @Test
    public void testBaseTypes() throws Exception {
        System.out.println("=== DIRECT TCK TEST: BaseTypesTest ===");

        Session session = createSession();
        System.out.println("Session created successfully for BaseTypesTest");

        BaseTypesTest test = new BaseTypesTest();
        injectSession(test, session);

        runTest("BaseTypesTest", test, session);
    }


    @Test
    public void testCreateAndDeleteType() throws Exception {
        System.out.println("=== DIRECT TCK TEST: CreateAndDeleteTypeTest ===");

        Session session = createSession();
        System.out.println("Session created successfully for CreateAndDeleteTypeTest");

        CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
        injectSession(test, session);

        runTest("CreateAndDeleteTypeTest", test, session);
    }

    private Session createSession() {
        SessionFactory sessionFactory = SessionFactoryImpl.newInstance();

        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.ATOMPUB_URL, CMIS_URL);
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        parameters.put(SessionParameter.USER, USERNAME);
        parameters.put(SessionParameter.PASSWORD, PASSWORD);
        parameters.put(SessionParameter.REPOSITORY_ID, REPOSITORY_ID);

        // Debug inherited flags for cmis:document type
        Session session = sessionFactory.createSession(parameters);

        System.out.println("=== INHERITED FLAG DEBUG ===");
        TypeDefinition docType = session.getTypeDefinition("cmis:document");
        System.out.println("cmis:document properties:");

        for (PropertyDefinition<?> propDef : docType.getPropertyDefinitions().values()) {
            String id = propDef.getId();
            if (id.startsWith("cmis:")) {
                System.out.println("  " + id + ":");
                System.out.println("    - id: " + propDef.getId());
                System.out.println("    - queryName: " + propDef.getQueryName());
                System.out.println("    - localName: " + propDef.getLocalName());
                System.out.println("    - displayName: " + propDef.getDisplayName());
                System.out.println("    - inherited: " + propDef.isInherited());
                System.out.println("    - required: " + propDef.isRequired());
                System.out.println("    - queryable: " + propDef.isQueryable());
                System.out.println("    - updatability: " + propDef.getUpdatability());
            }
        }

        return session;
    }

    private void injectSession(Object test, Session session) {
        try {
            // Search through class hierarchy to find session field
            Field sessionField = findFieldInHierarchy(test.getClass(), "session");

            if (sessionField != null) {
                sessionField.setAccessible(true);
                sessionField.set(test, session);
            } else {
                System.err.println("Warning: Could not find session field in test class hierarchy");
            }
        } catch (Exception e) {
            System.err.println("Failed to inject session: " + e.getMessage());
        }
    }

    private Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Try parent class
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void runTest(String testName, CmisTest test, Session session) {
        System.out.println("Running test: " + testName);

        Map<String, String> context = new HashMap<>();
        context.put("session", session.toString());
        // Add SPI class to avoid "SPI class entry is missing" error
        context.put("org.apache.chemistry.opencmis.binding.spi.classname",
                   "org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubSpi");

        try {
            test.init(context);
            test.run();  // run() method takes no arguments
        } catch (Exception e) {
            System.err.println("Error running test: " + e.getMessage());
            e.printStackTrace();
            fail("Test execution failed: " + e.getMessage());
        }

        List<CmisTestResult> results = test.getResults();
        System.out.println("\n=== TEST RESULTS for " + testName + " ===");

        boolean hasFailures = false;

        try (PrintWriter outputWriter = new PrintWriter(
                new FileWriter("target/surefire-reports/" +
                DirectTckTestRunner.class.getName() + "-output.txt", true))) {

            for (CmisTestResult result : results) {
                String status = result.getStatus().toString();
                String message = result.getMessage();

                System.out.println(status + ": " + message);
                outputWriter.println(status + ": " + message);

                if (result.getStackTrace() != null && result.getStackTrace().length > 0) {
                    for (StackTraceElement trace : result.getStackTrace()) {
                        System.out.println("  " + trace.toString());
                        outputWriter.println("  " + trace.toString());
                    }
                }

                if (status.equals("FAILURE") || status.equals("UNEXPECTED_EXCEPTION")) {
                    hasFailures = true;
                }
            }

            System.out.println("Total results: " + results.size());
            outputWriter.println("Total results: " + results.size());

        } catch (IOException e) {
            System.err.println("Failed to write test output: " + e.getMessage());
        }

        // CRITICAL FIX: Fail the JUnit test if TCK test has failures
        if (hasFailures) {
            fail("TCK test " + testName + " detected failures");
        }
    }
}