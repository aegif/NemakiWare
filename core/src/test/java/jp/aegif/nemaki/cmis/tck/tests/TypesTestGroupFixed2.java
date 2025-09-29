package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.junit.Test;
import org.junit.Before;
import org.junit.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * Alternative fixed TypesTestGroup that properly initializes TCK test context
 */
public class TypesTestGroupFixed2 {

    private Session session;
    private Map<String, String> parameters;

    @Before
    public void setUp() throws Exception {
        // Create session once for all tests
        parameters = new HashMap<>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        parameters.put(SessionParameter.READ_TIMEOUT, "120000");

        SessionFactory factory = SessionFactoryImpl.newInstance();
        session = factory.createSession(parameters);
    }

    private void runTest(AbstractSessionTest test, String testName) throws Exception {
        System.out.println("\n=== TypesTestGroupFixed2: Running " + testName + " ===");

        // Initialize test with parameters
        test.init(parameters);

        // Inject session using reflection (same as DirectTckTestRunner)
        java.lang.reflect.Field sessionField = null;
        Class<?> clazz = test.getClass();
        while (clazz != null && sessionField == null) {
            try {
                sessionField = clazz.getDeclaredField("session");
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }

        if (sessionField != null) {
            sessionField.setAccessible(true);
            sessionField.set(test, session);
            System.out.println("Session injected successfully");
        } else {
            System.out.println("Warning: Could not find session field");
        }

        // Run the test
        test.run();

        // Check results
        boolean hasFailures = false;
        StringBuilder failureMessages = new StringBuilder();

        for (CmisTestResult result : test.getResults()) {
            System.out.println("Result: " + result.getStatus() + " - " + result.getMessage());

            if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                hasFailures = true;
                failureMessages.append("\n  FAILURE: ").append(result.getMessage());
            }

            // Check child results
            if (result.getChildren() != null) {
                for (CmisTestResult child : result.getChildren()) {
                    if (child.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                        hasFailures = true;
                        failureMessages.append("\n    CHILD FAILURE: ").append(child.getMessage());
                    }
                }
            }
        }

        System.out.println("=== " + testName + " Summary: " + (hasFailures ? "FAILED" : "PASSED") + " ===\n");

        if (hasFailures) {
            Assert.fail("TCK failures in " + testName + ":" + failureMessages.toString());
        }
    }

    @Test
    public void baseTypesTest() throws Exception {
        BaseTypesTest test = new BaseTypesTest();
        runTest(test, "BaseTypesTest");
    }

    @Test
    public void createAndDeleteTypeTest() throws Exception {
        CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
        runTest(test, "CreateAndDeleteTypeTest");
    }

    @Test
    public void secondaryTypesTest() throws Exception {
        SecondaryTypesTest test = new SecondaryTypesTest();
        runTest(test, "SecondaryTypesTest");
    }
}