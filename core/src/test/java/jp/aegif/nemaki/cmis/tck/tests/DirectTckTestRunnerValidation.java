package jp.aegif.nemaki.cmis.tck.tests;

import org.junit.Test;
import org.junit.Assert;
import org.junit.Ignore;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import java.util.HashMap;
import java.util.Map;

/**
 * DEPRECATED: This class was created as a validation tool for DirectTckTestRunner.
 * The hang issue has been resolved by static initialization fix in TestGroupBase.
 * Use standard test groups instead - this class is kept for reference only.
 *
 * Validation test to verify DirectTckTestRunner actually runs tests properly
 */
@Ignore("DEPRECATED: Use standard test groups - hang issue resolved by TestGroupBase static initialization fix")
public class DirectTckTestRunnerValidation {

    private Session createSession() throws Exception {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        parameters.put(SessionParameter.READ_TIMEOUT, "120000");

        SessionFactory factory = SessionFactoryImpl.newInstance();
        return factory.createSession(parameters);
    }

    @Test
    public void validateBaseTypesTestExecution() throws Exception {
        System.out.println("\n=== VALIDATION: Running BaseTypesTest with detailed logging ===");

        BaseTypesTest test = new BaseTypesTest();
        Session session = createSession();

        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        // Add all required parameters for TCK framework
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");

        // Initialize test
        test.init(parameters);

        // Inject session (find field in class hierarchy)
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
            System.out.println("Warning: Could not find session field - test may not work correctly");
        }

        // Run the actual test
        System.out.println("Running BaseTypesTest.run()...");
        test.run();

        // Detailed result analysis
        System.out.println("\n=== DETAILED RESULT ANALYSIS ===");
        System.out.println("Total results: " + test.getResults().size());

        int warningCount = 0;
        int failureCount = 0;
        int okCount = 0;
        int infoCount = 0;
        int skippedCount = 0;

        for (CmisTestResult result : test.getResults()) {
            CmisTestResultStatus status = result.getStatus();
            String message = result.getMessage();

            System.out.println("\nResult Status: " + status.name() + " (level=" + status.getLevel() + ")");
            System.out.println("Message: " + message);

            // Count by status type
            switch (status) {
                case OK:
                    okCount++;
                    break;
                case INFO:
                    infoCount++;
                    break;
                case WARNING:
                    warningCount++;
                    System.out.println("  ⚠️ WARNING FOUND: " + message);
                    break;
                case FAILURE:
                    failureCount++;
                    System.out.println("  ❌ FAILURE FOUND: " + message);
                    break;
                case UNEXPECTED_EXCEPTION:
                    failureCount++;
                    System.out.println("  ❌ EXCEPTION FOUND: " + message);
                    break;
                case SKIPPED:
                    skippedCount++;
                    break;
            }

            // Check children
            if (result.getChildren() != null && !result.getChildren().isEmpty()) {
                System.out.println("  Has " + result.getChildren().size() + " child results:");
                for (CmisTestResult child : result.getChildren()) {
                    System.out.println("    - Child: " + child.getStatus() + " - " + child.getMessage());
                    if (child.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                        failureCount++;
                    }
                }
            }

            // Check exception details
            if (result.getException() != null) {
                System.out.println("  Exception: " + result.getException().getClass().getName() +
                                  " - " + result.getException().getMessage());
            }
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("OK: " + okCount);
        System.out.println("INFO: " + infoCount);
        System.out.println("WARNING: " + warningCount);
        System.out.println("FAILURE: " + failureCount);
        System.out.println("SKIPPED: " + skippedCount);
        System.out.println("TOTAL: " + test.getResults().size());

        // Test assertions
        System.out.println("\n=== VALIDATION ASSERTIONS ===");

        // 1. Test must produce results
        Assert.assertTrue("Test must produce at least one result", test.getResults().size() > 0);
        System.out.println("✓ Test produced " + test.getResults().size() + " results");

        // 2. Test must have actually executed (check for OK or INFO results)
        Assert.assertTrue("Test must have some successful results", okCount + infoCount > 0);
        System.out.println("✓ Test has " + (okCount + infoCount) + " successful results");

        // 3. If there are failures, they should be properly detected
        if (failureCount > 0) {
            System.out.println("⚠️ Test has " + failureCount + " failures - this should fail in DirectTckTestRunner");
        } else {
            System.out.println("✓ No failures detected");
        }

        // 4. WARNING: DirectTckTestRunner doesn't Assert.fail() on failures!
        System.out.println("\n⚠️ IMPORTANT FINDING:");
        System.out.println("DirectTckTestRunner detects failures but does NOT call Assert.fail()");
        System.out.println("This means it will always report success in JUnit even if TCK tests fail!");

        // This test will only fail if TCK has actual failures
        // to demonstrate the issue
        if (failureCount > 0) {
            Assert.fail("TCK test has " + failureCount + " failures but DirectTckTestRunner would report success!");
        }
    }
}