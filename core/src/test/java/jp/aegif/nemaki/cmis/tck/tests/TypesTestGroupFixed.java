package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.junit.Test;
import org.junit.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed TypesTestGroup that bypasses JUnitRunner hang issue
 * Based on DirectTckTestRunner approach but structured as traditional test class
 */
public class TypesTestGroupFixed {

    /**
     * Create a CMIS session directly, bypassing JUnitRunner
     */
    private Session createSession() throws Exception {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");

        // Set reasonable timeouts
        parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        parameters.put(SessionParameter.READ_TIMEOUT, "120000");

        SessionFactory factory = SessionFactoryImpl.newInstance();
        return factory.createSession(parameters);
    }

    /**
     * Run a TCK test directly without JUnitRunner
     */
    private void runTckTest(CmisTest test, String testName) throws Exception {
        System.out.println("=== TypesTestGroupFixed: Running " + testName + " ===");

        Session session = createSession();
        System.out.println("Session created successfully for " + testName);

        // Initialize test with parameters
        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        // Add SPI class parameters required by TCK framework
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        test.init(parameters);

        // Inject session using reflection (required by OpenCMIS TCK framework)
        // Try to find the session field in the class hierarchy
        java.lang.reflect.Field sessionField = null;
        Class<?> clazz = test.getClass();
        while (clazz != null && sessionField == null) {
            try {
                sessionField = clazz.getDeclaredField("session");
            } catch (NoSuchFieldException e) {
                // Try superclass
                clazz = clazz.getSuperclass();
            }
        }

        if (sessionField != null) {
            sessionField.setAccessible(true);
            sessionField.set(test, session);
        } else {
            System.out.println("Warning: Could not find session field in test class hierarchy");
            // Try alternative approach: call setSession if it exists
            try {
                java.lang.reflect.Method setSessionMethod = test.getClass().getMethod("setSession", Session.class);
                setSessionMethod.invoke(test, session);
            } catch (NoSuchMethodException e) {
                System.out.println("Warning: Could not set session via setSession method either");
            }
        }

        // Run the test
        System.out.println("Executing test: " + testName);
        test.run();

        // Check results
        System.out.println("=== Results for " + testName + " ===");
        boolean hasFailures = false;
        int resultCount = 0;
        StringBuilder failureMessages = new StringBuilder();

        for (CmisTestResult result : test.getResults()) {
            resultCount++;
            System.out.println("Result #" + resultCount + ": " + result.getStatus() + " - " + result.getMessage());

            // Check for failures
            if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                hasFailures = true;
                failureMessages.append("\n  FAILURE: ").append(result.getMessage());
                if (result.getException() != null) {
                    failureMessages.append(" (").append(result.getException().getMessage()).append(")");
                }
            }

            // Check child results
            if (result.getChildren() != null && !result.getChildren().isEmpty()) {
                for (CmisTestResult child : result.getChildren()) {
                    System.out.println("  Child: " + child.getStatus() + " - " + child.getMessage());
                    if (child.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                        hasFailures = true;
                        failureMessages.append("\n    CHILD FAILURE: ").append(child.getMessage());
                    }
                }
            }
        }

        System.out.println("=== " + testName + " Summary: " +
            (hasFailures ? "FAILED" : "PASSED") + " (" + resultCount + " results) ===\n");

        // Fail the test if there were any TCK failures
        if (hasFailures) {
            Assert.fail("TCK failures detected in " + testName + ":" + failureMessages.toString());
        }
    }

    @Test
    public void baseTypesTest() throws Exception {
        System.out.println("\n=== TypesTestGroupFixed: Starting baseTypesTest ===");
        runTckTest(new BaseTypesTest(), "BaseTypesTest");
        System.out.println("=== TypesTestGroupFixed: baseTypesTest completed successfully ===\n");
    }

    @Test
    public void createAndDeleteTypeTest() throws Exception {
        System.out.println("\n=== TypesTestGroupFixed: Starting createAndDeleteTypeTest ===");
        runTckTest(new CreateAndDeleteTypeTest(), "CreateAndDeleteTypeTest");
        System.out.println("=== TypesTestGroupFixed: createAndDeleteTypeTest completed successfully ===\n");
    }

    @Test
    public void secondaryTypesTest() throws Exception {
        System.out.println("\n=== TypesTestGroupFixed: Starting secondaryTypesTest ===");
        runTckTest(new SecondaryTypesTest(), "SecondaryTypesTest");
        System.out.println("=== TypesTestGroupFixed: secondaryTypesTest completed successfully ===\n");
    }
}